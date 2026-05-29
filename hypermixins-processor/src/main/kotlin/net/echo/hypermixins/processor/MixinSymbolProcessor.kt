package net.echo.hypermixins.processor

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.javapoet.*
import java.io.OutputStreamWriter
import java.lang.reflect.Modifier as JMod
import javax.lang.model.element.Modifier
import net.echo.hypermixins.processor.JvmDescriptors.buildTargetDescriptor
import net.echo.hypermixins.processor.JvmDescriptors.descriptor
import net.echo.hypermixins.processor.JvmDescriptors.dropFirstArgDesc
import net.echo.hypermixins.processor.JvmDescriptors.toJvmDesc

// Generated descriptor ABI (must match MixinDescriptor.java in the runtime module):
//   overwriteEntries:  [targetName, targetDesc, handlerName, handlerDesc]
//   originalEntries:   [handlerName, handlerDesc, targetName]
//   redirectEntries:   [targetMethod, invokeDesc, index, call, handlerName, handlerDesc]
//   injectEntries:     [targetMethod, point, atDesc, atIndex,
//                       cancellable, returnable, handlerName, handlerDesc]
//   injectCaptureLocals:[handlerName, handlerDesc, paramIndex, slot, ordinal, argsOnly]
//   injectShifts:      [handlerName, handlerDesc, shift]   — rows only for non-BEFORE
//   modifyReturnValueEntries: [targetMethod, invokeDesc, index, handlerName, handlerDesc]
//   accessorEntries:   [handlerName, handlerDesc, kind, targetField]   kind = GET | SET
//   invokerEntries:    [handlerName, handlerDesc, targetName]
//   modifyConstantEntries: [targetMethod, type, value, index, handlerName, handlerDesc]
//   modifyArgEntries:  [targetMethod, invokeDesc, argIndex, handlerName, handlerDesc]
//   modifyExpressionValueEntries: [targetMethod, point, atDesc, index, handlerName, handlerDesc]
//   modifyArgsEntries: [targetMethod, invokeDesc, handlerName, handlerDesc]
//   modifyReceiverEntries: [targetMethod, invokeDesc, handlerName, handlerDesc]
//   shadowEntries:     [handlerName, handlerDesc, targetName]
//   shadowFieldEntries:[mixinFieldName, fieldDesc, targetFieldName]
//   shadowStaticFieldEntries:[mixinFieldName, fieldDesc, targetFieldName]
//   staticTargetMethods:[name, desc]   — only entries the resolver could see as static
//   privateShadowTargetMethods:[name, desc]   — @Shadow targets the resolver saw as private
//   syntheticNames:    [targetName, targetDesc, mangledOriginalName, dispatchName]

private const val MIXIN_FQN       = "net.echo.hypermixins.annotations.Mixin"
private const val OVERWRITE_FQN   = "net.echo.hypermixins.annotations.Overwrite"
private const val ORIGINAL_FQN    = "net.echo.hypermixins.annotations.Original"
private const val REDIRECT_FQN    = "net.echo.hypermixins.annotations.Redirect"
private const val INJECT_FQN      = "net.echo.hypermixins.annotations.Inject"
private const val CANCELLABLE_FQN = "net.echo.hypermixins.annotations.Cancellable"
private const val SHADOW_FQN      = "net.echo.hypermixins.annotations.Shadow"
private const val MODIFY_RV_FQN   = "net.echo.hypermixins.annotations.ModifyReturnValue"
private const val ACCESSOR_FQN    = "net.echo.hypermixins.annotations.Accessor"
private const val INVOKER_FQN     = "net.echo.hypermixins.annotations.Invoker"
private const val MODIFY_CONST_FQN = "net.echo.hypermixins.annotations.ModifyConstant"
private const val MODIFY_ARG_FQN   = "net.echo.hypermixins.annotations.ModifyArg"
private const val MODIFY_EXPR_FQN  = "net.echo.hypermixins.annotations.ModifyExpressionValue"
private const val MODIFY_ARGS_FQN  = "net.echo.hypermixins.annotations.ModifyArgs"
private const val MODIFY_RECV_FQN  = "net.echo.hypermixins.annotations.ModifyReceiver"

class MixinSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val generatedDescriptors = mutableListOf<String>()
    private val generatedMixinFqns = mutableListOf<String>()
    private val containingFiles = mutableListOf<com.google.devtools.ksp.symbol.KSFile>()
    private var indexWritten = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(MIXIN_FQN)
        val deferred = symbols.filter { !it.validate() }.toList()

        symbols
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.validate() }
            .forEach { processMixin(it, resolver) }

        return deferred
    }

    override fun finish() {
        if (generatedMixinFqns.isEmpty() || indexWritten) return
        indexWritten = true

        // Group mixins by package so each module-level YAML stays compact.
        val byPackage: Map<String, List<String>> = generatedMixinFqns
            .groupBy { it.substringBeforeLast('.', missingDelimiterValue = "") }

        // One yml per package — produces `<pkgSlug>.mixins.yml` resources under META-INF/hypermixins/.
        for ((pkg, fqns) in byPackage) {
            val slug = if (pkg.isEmpty()) "default" else pkg.replace('.', '-')
            val yaml = buildString {
                if (pkg.isNotEmpty()) appendLine("package: $pkg")
                appendLine("mixins:")
                fqns.sorted().forEach { fqn ->
                    val simple = fqn.substringAfterLast('.')
                    if (pkg.isEmpty()) appendLine("  - $fqn") else appendLine("  - $simple")
                }
            }
            val deps = if (containingFiles.isEmpty()) Dependencies(false)
                       else Dependencies(false, *containingFiles.toTypedArray())
            val out = codeGenerator.createNewFile(deps, "", "META-INF/hypermixins/$slug.mixins", "yml")
            OutputStreamWriter(out).use { it.write(yaml) }
        }
    }

    // ---- Main processing ----

    private fun processMixin(cls: KSClassDeclaration, resolver: Resolver) {
        val mixinAnn = cls.findAnnotation(MIXIN_FQN) ?: return
        val targetClass = mixinAnn.arg("value") as? String ?: run {
            logger.error("@Mixin#value() must not be empty", cls)
            return
        }
        if (targetClass.isBlank()) {
            logger.error("@Mixin#value() must not be empty", cls)
            return
        }

        val hasNoArgCtor = cls.primaryConstructor?.parameters?.isEmpty() == true
                || cls.declarations.filterIsInstance<KSFunctionDeclaration>()
                    .filter { it.simpleName.asString() == "<init>" }
                    .any { it.parameters.isEmpty() }
        if (!hasNoArgCtor) {
            logger.error("@Mixin class must declare a public no-arg constructor ()V: ${cls.qualifiedName?.asString()}", cls)
            return
        }

        val overwrites = mutableListOf<OverwriteEntry>()
        val originals  = mutableListOf<OriginalEntry>()
        val redirects  = mutableListOf<RedirectEntry>()
        val injects    = mutableListOf<InjectEntry>()
        val injectLocals = mutableListOf<InjectLocalEntry>()
        val shadows    = mutableListOf<ShadowEntry>()
        val shadowFields = mutableListOf<ShadowFieldEntry>()
        val shadowStaticFields = mutableListOf<ShadowFieldEntry>()
        val modifyRvs = mutableListOf<ModifyReturnValueEntry>()
        val accessors = mutableListOf<AccessorEntry>()
        val invokers = mutableListOf<InvokerEntry>()
        val modifyConsts = mutableListOf<ModifyConstantEntry>()
        val modifyArgs = mutableListOf<ModifyArgEntry>()
        val modifyExprs = mutableListOf<ModifyExpressionValueEntry>()
        val modifyArgsList = mutableListOf<ModifyArgsEntry>()
        val modifyReceivers = mutableListOf<ModifyReceiverEntry>()

        cls.declarations.filterIsInstance<KSFunctionDeclaration>().forEach { fn ->
            when {
                fn.hasAnnotation(OVERWRITE_FQN)  -> validateAndCollectOverwrite(fn, targetClass, overwrites)
                fn.hasAnnotation(ORIGINAL_FQN)   -> validateAndCollectOriginal(fn, originals)
                fn.hasAnnotation(REDIRECT_FQN)   -> validateAndCollectRedirect(fn, redirects)
                fn.hasAnnotation(INJECT_FQN)     -> validateAndCollectInject(fn, injects, injectLocals)
                fn.hasAnnotation(SHADOW_FQN)     -> validateAndCollectShadow(fn, shadows)
                fn.hasAnnotation(MODIFY_RV_FQN)  -> validateAndCollectModifyReturnValue(fn, modifyRvs)
                fn.hasAnnotation(ACCESSOR_FQN)   -> validateAndCollectAccessor(fn, accessors)
                fn.hasAnnotation(INVOKER_FQN)    -> validateAndCollectInvoker(fn, invokers)
                fn.hasAnnotation(MODIFY_CONST_FQN) -> validateAndCollectModifyConstant(fn, modifyConsts)
                fn.hasAnnotation(MODIFY_ARG_FQN) -> validateAndCollectModifyArg(fn, modifyArgs)
                fn.hasAnnotation(MODIFY_EXPR_FQN) -> validateAndCollectModifyExpr(fn, modifyExprs)
                fn.hasAnnotation(MODIFY_ARGS_FQN) -> validateAndCollectModifyArgs(fn, modifyArgsList)
                fn.hasAnnotation(MODIFY_RECV_FQN) -> validateAndCollectModifyReceiver(fn, modifyReceivers)
            }
        }
        cls.declarations.filterIsInstance<KSPropertyDeclaration>().forEach { prop ->
            if (prop.hasAnnotation(SHADOW_FQN)) {
                val ann = prop.findAnnotation(SHADOW_FQN)!!
                val explicit = ann.arg("value") as? String ?: ""
                val prefix = (ann.arg("prefix") as? String).orEmpty()
                val simple = prop.simpleName.asString()
                val targetName = when {
                    explicit.isNotBlank() -> explicit
                    prefix.isNotEmpty() && simple.startsWith(prefix) -> simple.removePrefix(prefix)
                    else -> simple
                }
                val fieldDesc = toJvmDesc(prop.type.resolve())
                val entry = ShadowFieldEntry(prop.simpleName.asString(), fieldDesc, targetName)
                if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC in prop.modifiers) {
                    shadowStaticFields += entry
                } else {
                    shadowFields += entry
                }
            }
        }

        // Probe static-target methods via KSP's classpath resolver. Targets reachable from the
        // compile classpath (i.e., declared as compileOnly or implementation in Gradle) light up;
        // everything else falls back to instance dispatch.
        val staticTargets = probeStaticTargets(resolver, targetClass, originals, overwrites)
        val privateShadowTargets = probePrivateShadowTargets(resolver, targetClass, shadows, invokers)
        generateDescriptor(cls, targetClass, overwrites, originals, redirects, injects, injectLocals, shadows, shadowFields, shadowStaticFields, modifyRvs, modifyConsts, modifyArgs, modifyExprs, modifyArgsList, modifyReceivers, accessors, invokers, staticTargets, privateShadowTargets)
    }

    // ---- Validation + collection ----

    private fun validateAndCollectOverwrite(
        fn: KSFunctionDeclaration, targetClass: String, out: MutableList<OverwriteEntry>
    ) {
        if (Modifier.STATIC in fn.modifiers()) {
            logger.error("@Overwrite method must not be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val ann = fn.findAnnotation(OVERWRITE_FQN)!!
        val targetName = ann.arg("value") as? String ?: run {
            logger.error("@Overwrite#value() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (targetName.isBlank()) {
            logger.error("@Overwrite#value() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        val params = fn.parameters
        if (params.isEmpty()) {
            logger.error("@Overwrite method must have 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        val firstType = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstType != "kotlin.Any" && firstType != "java.lang.Object") {
            logger.error("@Overwrite first parameter must be Object/Any (found $firstType): ${fn.simpleName.asString()}", fn)
            return
        }
        params.forEach { p ->
            if (p.type.resolve().declaration.qualifiedName?.asString() == targetClass) {
                logger.error("@Overwrite parameters must not reference target class $targetClass directly; use Object self", fn)
                return
            }
        }
        val targetDesc = buildTargetDescriptor(fn)
        out += OverwriteEntry(targetName, targetDesc, fn.simpleName.asString(), descriptor(fn))
    }

    private fun validateAndCollectOriginal(fn: KSFunctionDeclaration, out: MutableList<OriginalEntry>) {
        val ann = fn.findAnnotation(ORIGINAL_FQN)!!
        val targetName = ann.arg("value") as? String ?: ""
        if (targetName.isBlank()) {
            logger.error("@Original#value() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (fn.parameters.isEmpty()) {
            logger.error("@Original method must have at least 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        out += OriginalEntry(fn.simpleName.asString(), descriptor(fn), targetName)
    }

    private fun validateAndCollectRedirect(fn: KSFunctionDeclaration, out: MutableList<RedirectEntry>) {
        if (Modifier.STATIC !in fn.modifiers()) {
            logger.error("@Redirect method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val ann = fn.findAnnotation(REDIRECT_FQN)!!
        val method = ann.arg("method") as? String ?: ""
        if (method.isBlank()) {
            logger.error("@Redirect#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc  = atAnn?.arg("desc") as? String ?: ""
        if (desc.isBlank()) {
            logger.error("@At#desc() must not be empty on @Redirect ${fn.simpleName.asString()}", fn)
            return
        }
        val index = (atAnn?.arg("index") as? Int) ?: 0
        val call  = readEnumArg(atAnn?.arg("call"), "INVOKEVIRTUAL")
        val handlerDesc = descriptor(fn)
        val isField = call == "GETFIELD" || call == "PUTFIELD" || call == "GETSTATIC" || call == "PUTSTATIC"
        if (isField) {
            val colon = desc.indexOf(':')
            if (colon < 0) {
                logger.error("@At#desc() for field redirect must be \"owner/Class.field:Ldesc;\" on ${fn.simpleName.asString()}", fn)
                return
            }
            val fieldDesc = desc.substring(colon + 1)
            val expected = when (call) {
                "GETFIELD"  -> "(Ljava/lang/Object;)$fieldDesc"
                "PUTFIELD"  -> "(Ljava/lang/Object;$fieldDesc)V"
                "GETSTATIC" -> "()$fieldDesc"
                else        -> "($fieldDesc)V"
            }
            if (handlerDesc != expected) {
                logger.error(
                    "@Redirect field handler signature mismatch on ${fn.simpleName.asString()}: expected $expected found $handlerDesc", fn
                )
            }
        } else {
            val parenIdx = desc.indexOf('(')
            if (parenIdx < 0) {
                logger.error("@At#desc() missing '(' in @Redirect on ${fn.simpleName.asString()}", fn)
                return
            }
            val invokeSignature = desc.substring(parenIdx)
            if (handlerDesc != invokeSignature) {
                logger.error(
                    "@Redirect handler signature mismatch on ${fn.simpleName.asString()}: expected $invokeSignature found $handlerDesc", fn
                )
            }
        }
        out += RedirectEntry(method, desc, index, call, fn.simpleName.asString(), handlerDesc)
    }

    private fun validateAndCollectModifyReceiver(fn: KSFunctionDeclaration, out: MutableList<ModifyReceiverEntry>) {
        val ann = fn.findAnnotation(MODIFY_RECV_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyReceiver#method() empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyReceiver must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() empty on @ModifyReceiver ${fn.simpleName.asString()}", fn)
            return
        }
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        out += ModifyReceiverEntry(method, desc, handlerName, handlerDesc)
    }

    private fun validateAndCollectModifyArgs(fn: KSFunctionDeclaration, out: MutableList<ModifyArgsEntry>) {
        val ann = fn.findAnnotation(MODIFY_ARGS_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyArgs#method() empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyArgs must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() empty on @ModifyArgs ${fn.simpleName.asString()}", fn)
            return
        }
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        // Handler must be (Object[])void.
        if (handlerDesc != "([Ljava/lang/Object;)V") {
            logger.error("@ModifyArgs handler must be (Object[]): void on $handlerName (got $handlerDesc)", fn)
            return
        }
        out += ModifyArgsEntry(method, desc, handlerName, handlerDesc)
    }

    private fun validateAndCollectModifyExpr(fn: KSFunctionDeclaration, out: MutableList<ModifyExpressionValueEntry>) {
        val ann = fn.findAnnotation(MODIFY_EXPR_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyExpressionValue#method() empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyExpressionValue must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val point = readEnumArg(atAnn?.arg("point"), "INVOKE")
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() empty on @ModifyExpressionValue ${fn.simpleName.asString()}", fn)
            return
        }
        val idx = (atAnn?.arg("index") as? Int) ?: 0
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        out += ModifyExpressionValueEntry(method, point, desc, idx, handlerName, handlerDesc)
    }

    private fun validateAndCollectModifyArg(fn: KSFunctionDeclaration, out: MutableList<ModifyArgEntry>) {
        val ann = fn.findAnnotation(MODIFY_ARG_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyArg#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyArg method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = (atAnn?.arg("desc") as? String).orEmpty()
        if (desc.isBlank()) {
            logger.error("@At#desc() must not be empty on @ModifyArg ${fn.simpleName.asString()}", fn)
            return
        }
        val idx = (ann.arg("index") as? Int) ?: 0
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        out += ModifyArgEntry(method, desc, idx, handlerName, handlerDesc)
    }

    private fun validateAndCollectModifyConstant(fn: KSFunctionDeclaration, out: MutableList<ModifyConstantEntry>) {
        val ann = fn.findAnnotation(MODIFY_CONST_FQN)!!
        val method = (ann.arg("method") as? String).orEmpty()
        if (method.isBlank()) {
            logger.error("@ModifyConstant#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyConstant method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val constAnn = ann.arg("constant") as? KSAnnotation
        val intValue = (constAnn?.arg("intValue") as? Int) ?: Int.MIN_VALUE
        val longValue = (constAnn?.arg("longValue") as? Long) ?: Long.MIN_VALUE
        val floatValue = (constAnn?.arg("floatValue") as? Float) ?: Float.NaN
        val doubleValue = (constAnn?.arg("doubleValue") as? Double) ?: Double.NaN
        val stringValue = (constAnn?.arg("stringValue") as? String).orEmpty()
        val type: String
        val value: String
        when {
            intValue != Int.MIN_VALUE -> { type = "I"; value = intValue.toString() }
            longValue != Long.MIN_VALUE -> { type = "J"; value = longValue.toString() }
            !floatValue.isNaN() -> { type = "F"; value = floatValue.toString() }
            !doubleValue.isNaN() -> { type = "D"; value = doubleValue.toString() }
            stringValue.isNotEmpty() -> { type = "Ljava/lang/String;"; value = stringValue }
            else -> {
                logger.error("@Constant must specify exactly one value on ${fn.simpleName.asString()}", fn)
                return
            }
        }
        val index = (ann.arg("index") as? Int) ?: 0
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        out += ModifyConstantEntry(method, type, value, index, handlerName, handlerDesc)
    }

    private fun validateAndCollectInvoker(fn: KSFunctionDeclaration, out: MutableList<InvokerEntry>) {
        val ann = fn.findAnnotation(INVOKER_FQN)!!
        val explicit = (ann.arg("value") as? String).orEmpty()
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        val params = fn.parameters
        if (params.isEmpty()) {
            logger.error("@Invoker method must take Object self as first parameter: $handlerName", fn)
            return
        }
        val firstFqn = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstFqn != "kotlin.Any" && firstFqn != "java.lang.Object") {
            logger.error("@Invoker first parameter must be Object/Any: $handlerName", fn)
            return
        }
        val targetName = when {
            explicit.isNotBlank() -> explicit
            else -> deriveInvokerName(handlerName)
        }
        if (targetName.isBlank()) {
            logger.error("@Invoker cannot derive target method from $handlerName — set value()", fn)
            return
        }
        out += InvokerEntry(handlerName, handlerDesc, targetName)
    }

    private fun deriveInvokerName(method: String): String {
        for (prefix in listOf("invoke", "call")) {
            if (method.startsWith(prefix) && method.length > prefix.length
                && method[prefix.length].isUpperCase()
            ) {
                val tail = method.substring(prefix.length)
                return tail[0].lowercase() + tail.substring(1)
            }
        }
        return method
    }

    private fun validateAndCollectAccessor(fn: KSFunctionDeclaration, out: MutableList<AccessorEntry>) {
        val ann = fn.findAnnotation(ACCESSOR_FQN)!!
        val explicit = (ann.arg("value") as? String).orEmpty()
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        val args = fn.parameters
        if (args.isEmpty()) {
            logger.error("@Accessor method must take Object self as first parameter: $handlerName", fn)
            return
        }
        val firstFqn = args[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstFqn != "kotlin.Any" && firstFqn != "java.lang.Object") {
            logger.error("@Accessor first parameter must be Object/Any: $handlerName", fn)
            return
        }
        val returnsVoid = (fn.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: "") in listOf("kotlin.Unit", "java.lang.Void", "void")
        val isSetter = returnsVoid && args.size == 2
        val isGetter = !returnsVoid && args.size == 1
        if (!isGetter && !isSetter) {
            logger.error("@Accessor must be (Object): T (getter) or (Object, T): void (setter): $handlerName", fn)
            return
        }
        val targetField = if (explicit.isNotBlank()) explicit else deriveAccessorField(handlerName)
        if (targetField.isBlank()) {
            logger.error("@Accessor cannot derive target field name from $handlerName — set value()", fn)
            return
        }
        out += AccessorEntry(handlerName, handlerDesc, if (isGetter) "GET" else "SET", targetField)
    }

    private fun deriveAccessorField(method: String): String {
        for (prefix in listOf("get", "set", "is")) {
            if (method.startsWith(prefix) && method.length > prefix.length
                && method[prefix.length].isUpperCase()
            ) {
                val tail = method.substring(prefix.length)
                return tail[0].lowercase() + tail.substring(1)
            }
        }
        return method
    }

    private fun validateAndCollectModifyReturnValue(fn: KSFunctionDeclaration, out: MutableList<ModifyReturnValueEntry>) {
        val ann = fn.findAnnotation(MODIFY_RV_FQN)!!
        val method = ann.arg("method") as? String ?: ""
        if (method.isBlank()) {
            logger.error("@ModifyReturnValue#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in fn.modifiers) {
            logger.error("@ModifyReturnValue method must be static: ${fn.simpleName.asString()}", fn)
            return
        }
        val atAnn = ann.arg("at") as? KSAnnotation
        val desc = atAnn?.arg("desc") as? String ?: ""
        if (desc.isBlank()) {
            logger.error("@At#desc() must not be empty on @ModifyReturnValue ${fn.simpleName.asString()}", fn)
            return
        }
        val parenIdx = desc.indexOf('(')
        if (parenIdx < 0) {
            logger.error("@At#desc() must be the invoke form on @ModifyReturnValue ${fn.simpleName.asString()}", fn)
            return
        }
        val invokeSig = desc.substring(parenIdx)
        val handlerDesc = descriptor(fn)
        val invokeReturn = invokeSig.substring(invokeSig.indexOf(')') + 1)
        val expected = "($invokeReturn)$invokeReturn"
        if (handlerDesc != expected) {
            logger.error("@ModifyReturnValue handler signature must match (${invokeReturn})${invokeReturn} (got $handlerDesc): ${fn.simpleName.asString()}", fn)
            return
        }
        val index = (atAnn?.arg("index") as? Int) ?: 0
        out += ModifyReturnValueEntry(method, desc, index, fn.simpleName.asString(), handlerDesc)
    }

    private fun validateAndCollectShadow(fn: KSFunctionDeclaration, out: MutableList<ShadowEntry>) {
        val ann = fn.findAnnotation(SHADOW_FQN)!!
        val explicit = ann.arg("value") as? String ?: ""
        val prefix = (ann.arg("prefix") as? String).orEmpty()
        val simple = fn.simpleName.asString()
        val targetName = when {
            explicit.isNotBlank() -> explicit
            prefix.isNotEmpty() && simple.startsWith(prefix) -> simple.removePrefix(prefix)
            else -> simple
        }
        val params = fn.parameters
        if (params.isEmpty()) {
            logger.error("@Shadow method must have 'Object self' as first parameter: ${fn.simpleName.asString()}", fn)
            return
        }
        val firstType = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (firstType != "kotlin.Any" && firstType != "java.lang.Object") {
            logger.error("@Shadow first parameter must be Object/Any (found $firstType): ${fn.simpleName.asString()}", fn)
            return
        }
        out += ShadowEntry(fn.simpleName.asString(), descriptor(fn), targetName)
    }

    private fun validateAndCollectInject(
        fn: KSFunctionDeclaration,
        out: MutableList<InjectEntry>,
        localsOut: MutableList<InjectLocalEntry>
    ) {
        val ann = fn.findAnnotation(INJECT_FQN)!!
        val method = ann.arg("method") as? String ?: ""
        if (method.isBlank()) {
            logger.error("@Inject#method() must not be empty on ${fn.simpleName.asString()}", fn)
            return
        }
        val cancellable = (ann.arg("cancellable") as? Boolean) == true
                || fn.hasAnnotation(CANCELLABLE_FQN)
        var returnable = false
        if (cancellable) {
            val lastParam = fn.parameters.lastOrNull()
            val lastFqn = lastParam?.type?.resolve()?.declaration?.qualifiedName?.asString() ?: ""
            if (!lastFqn.endsWith("CallbackInfo") && !lastFqn.endsWith("CallbackInfoReturnable")) {
                logger.error("@Inject with cancellable=true requires CallbackInfo or CallbackInfoReturnable as last parameter: ${fn.simpleName.asString()}", fn)
            }
            returnable = lastFqn.endsWith("CallbackInfoReturnable")
        }
        val atAnn = fn.findAnnotation("net.echo.hypermixins.annotations.At")
        val point = readEnumArg(atAnn?.arg("point"), "HEAD")
        val atDesc = (atAnn?.arg("desc") as? String) ?: ""
        val atIndex = (atAnn?.arg("index") as? Int) ?: 0
        val shift = readEnumArg(atAnn?.arg("shift"), "BEFORE")
        if ((point == "INVOKE" || point == "FIELD" || point == "CONSTANT" || point == "NEW") && atDesc.isBlank()) {
            logger.error("@Inject point $point requires @At#desc() on ${fn.simpleName.asString()}", fn)
            return
        }
        val handlerName = fn.simpleName.asString()
        val handlerDesc = descriptor(fn)
        out += InjectEntry(method, point, atDesc, atIndex, cancellable, returnable, handlerName, handlerDesc, shift)
        fn.parameters.forEachIndexed { i, p ->
            val localAnn = p.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == "net.echo.hypermixins.annotations.Local"
            }
            if (localAnn != null) {
                val slot = (localAnn.arguments.firstOrNull { it.name?.asString() == "index" }?.value as? Int) ?: -1
                val ord = (localAnn.arguments.firstOrNull { it.name?.asString() == "ordinal" }?.value as? Int) ?: -1
                val argsOnly = (localAnn.arguments.firstOrNull { it.name?.asString() == "argsOnly" }?.value as? Boolean) == true
                localsOut += InjectLocalEntry(handlerName, handlerDesc, i, slot, ord, argsOnly)
            }
        }
    }

    // ---- Descriptor helpers ----

    private fun KSFunctionDeclaration.modifiers(): Set<Modifier> {
        val result = mutableSetOf<Modifier>()
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC in this.modifiers) result += Modifier.STATIC
        return result
    }

    // ---- Code generation ----

    private fun probePrivateShadowTargets(
        resolver: Resolver,
        targetClass: String,
        shadows: List<ShadowEntry>,
        invokers: List<InvokerEntry>
    ): Set<String> {
        val result = mutableSetOf<String>()
        val targetDecl = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(targetClass)
        ) ?: return result
        for (sh in shadows) recordIfPrivate(targetDecl, sh.targetName, dropFirstArgDesc(sh.handlerDesc), result)
        for (iv in invokers) recordIfPrivate(targetDecl, iv.targetName, dropFirstArgDesc(iv.handlerDesc), result)
        return result
    }

    private fun recordIfPrivate(
        targetDecl: KSClassDeclaration,
        name: String, desc: String,
        result: MutableSet<String>
    ) {
        val match = targetDecl.getDeclaredFunctions().firstOrNull {
            it.simpleName.asString() == name && descriptor(it) == desc
        } ?: return
        if (com.google.devtools.ksp.symbol.Modifier.PRIVATE in match.modifiers) {
            result += name + desc
        }
    }

    private fun probeStaticTargets(
        resolver: Resolver,
        targetClass: String,
        originals: List<OriginalEntry>,
        overwrites: List<OverwriteEntry>
    ): Set<String> {
        val result = mutableSetOf<String>()
        val targetDecl = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(targetClass)
        ) ?: return result
        val pairs = mutableSetOf<Pair<String, String>>()
        for (oe in originals) {
            // handlerDesc starts with (Ljava/lang/Object;...); drop the leading Object self to recover target desc.
            val td = dropFirstArgDesc(oe.handlerDesc)
            pairs += oe.targetName to td
        }
        for (oe in overwrites) {
            pairs += oe.targetName to oe.targetDesc
        }
        for ((name, desc) in pairs) {
            val match = targetDecl.getDeclaredFunctions().firstOrNull {
                it.simpleName.asString() == name && descriptor(it) == desc
            }
            if (match != null
                && com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC in match.modifiers
            ) {
                result += name + desc
            }
        }
        return result
    }

    private fun generateDescriptor(
        cls: KSClassDeclaration,
        targetClass: String,
        overwrites: List<OverwriteEntry>,
        originals: List<OriginalEntry>,
        redirects: List<RedirectEntry>,
        injects: List<InjectEntry>,
        injectLocals: List<InjectLocalEntry>,
        shadows: List<ShadowEntry>,
        shadowFields: List<ShadowFieldEntry>,
        shadowStaticFields: List<ShadowFieldEntry>,
        modifyRvs: List<ModifyReturnValueEntry>,
        modifyConsts: List<ModifyConstantEntry>,
        modifyArgs: List<ModifyArgEntry>,
        modifyExprs: List<ModifyExpressionValueEntry>,
        modifyArgsList: List<ModifyArgsEntry>,
        modifyReceivers: List<ModifyReceiverEntry>,
        accessors: List<AccessorEntry>,
        invokers: List<InvokerEntry>,
        staticTargets: Set<String>,
        privateShadowTargets: Set<String>
    ) {
        val mixinFqn = cls.qualifiedName?.asString() ?: return
        val descriptorFqn = "$mixinFqn\$\$Descriptor"
        val pkg = if ('.' in descriptorFqn) descriptorFqn.substringBeforeLast('.') else ""
        val simpleName = descriptorFqn.substringAfterLast('.')

        val classBuilder = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Generated by HyperMixins KSP processor. Do not edit.")

        classBuilder.addMethod(
            MethodSpec.methodBuilder("mixinClass")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(String::class.java)
                .addStatement("return \$S", mixinFqn)
                .build()
        )
        classBuilder.addMethod(
            MethodSpec.methodBuilder("targetClass")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(String::class.java)
                .addStatement("return \$S", targetClass.replace('.', '/'))
                .build()
        )

        classBuilder.addMethod(entriesMethod("overwriteEntries", overwrites.map {
            arrayOf(it.targetName, it.targetDesc, it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("originalEntries", originals.map {
            arrayOf(it.handlerName, it.handlerDesc, it.targetName)
        }))
        classBuilder.addMethod(entriesMethod("redirectEntries", redirects.map {
            arrayOf(it.targetMethod, it.invokeDesc, it.index.toString(), it.call, it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("injectEntries", injects.map {
            arrayOf(it.targetMethod, it.point, it.atDesc, it.atIndex.toString(),
                it.cancellable.toString(), it.returnable.toString(), it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("injectShifts",
            injects.filter { it.shift == "AFTER" }.map { arrayOf(it.handlerName, it.handlerDesc, it.shift) }))
        classBuilder.addMethod(entriesMethod("injectCaptureLocals", injectLocals.map {
            arrayOf(it.handlerName, it.handlerDesc, it.paramIndex.toString(), it.slot.toString(), it.ordinal.toString(), it.argsOnly.toString())
        }))
        classBuilder.addMethod(entriesMethod("shadowEntries", shadows.map {
            arrayOf(it.handlerName, it.handlerDesc, it.targetName)
        }))
        classBuilder.addMethod(entriesMethod("shadowFieldEntries", shadowFields.map {
            arrayOf(it.mixinFieldName, it.fieldDesc, it.targetFieldName)
        }))
        classBuilder.addMethod(entriesMethod("shadowStaticFieldEntries", shadowStaticFields.map {
            arrayOf(it.mixinFieldName, it.fieldDesc, it.targetFieldName)
        }))
        classBuilder.addMethod(entriesMethod("modifyReturnValueEntries", modifyRvs.map {
            arrayOf(it.targetMethod, it.invokeDesc, it.index.toString(), it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("accessorEntries", accessors.map {
            arrayOf(it.handlerName, it.handlerDesc, it.kind, it.targetField)
        }))
        classBuilder.addMethod(entriesMethod("invokerEntries", invokers.map {
            arrayOf(it.handlerName, it.handlerDesc, it.targetName)
        }))
        classBuilder.addMethod(entriesMethod("modifyConstantEntries", modifyConsts.map {
            arrayOf(it.targetMethod, it.type, it.value, it.index.toString(), it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("modifyArgEntries", modifyArgs.map {
            arrayOf(it.targetMethod, it.invokeDesc, it.argIndex.toString(), it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("modifyExpressionValueEntries", modifyExprs.map {
            arrayOf(it.targetMethod, it.point, it.atDesc, it.index.toString(), it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("modifyArgsEntries", modifyArgsList.map {
            arrayOf(it.targetMethod, it.invokeDesc, it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("modifyReceiverEntries", modifyReceivers.map {
            arrayOf(it.targetMethod, it.invokeDesc, it.handlerName, it.handlerDesc)
        }))
        classBuilder.addMethod(entriesMethod("staticTargetMethods", staticTargets.map {
            val paren = it.indexOf('(')
            arrayOf(it.substring(0, paren), it.substring(paren))
        }))
        classBuilder.addMethod(entriesMethod("privateShadowTargetMethods", privateShadowTargets.map {
            val paren = it.indexOf('(')
            arrayOf(it.substring(0, paren), it.substring(paren))
        }))
        classBuilder.addMethod(entriesMethod("syntheticNames", overwrites.map {
            arrayOf(
                it.targetName, it.targetDesc,
                NameMangling.mangledOriginalName(it.targetName, it.targetDesc),
                NameMangling.dispatchName(it.targetName, it.targetDesc)
            )
        }))

        val file = JavaFile.builder(pkg, classBuilder.build()).build()
        val out = codeGenerator.createNewFile(
            Dependencies(false, cls.containingFile!!),
            pkg, simpleName, "java"
        )
        OutputStreamWriter(out).use { file.writeTo(it) }
        generatedDescriptors += descriptorFqn
        generatedMixinFqns += mixinFqn
        cls.containingFile?.let { containingFiles += it }
    }

    private fun entriesMethod(name: String, entries: List<Array<String>>): MethodSpec {
        val stringArrayType = ArrayTypeName.of(String::class.java)
        val listType = ParameterizedTypeName.get(ClassName.get(List::class.java), stringArrayType)
        val block = CodeBlock.builder().add("return \$T.<\$T[]>of(\n", List::class.java, String::class.java)
        entries.forEachIndexed { i, e ->
            block.add("    new String[]{${e.joinToString(", ") { "\$S" }}}", *e)
            if (i < entries.size - 1) block.add(",\n")
        }
        block.add("\n)")
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listType)
            .addStatement(block.build())
            .build()
    }

    // ---- KSP helpers ----

    private fun KSAnnotated.findAnnotation(fqn: String): KSAnnotation? =
        annotations.firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn }

    private fun KSAnnotated.hasAnnotation(fqn: String): Boolean = findAnnotation(fqn) != null

    private fun KSAnnotation.arg(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value

    private fun readEnumArg(value: Any?, default: String): String = when (value) {
        null -> default
        is String -> value
        is KSType -> value.declaration.simpleName.asString()
        is KSClassDeclaration -> value.simpleName.asString()
        is KSDeclaration -> value.simpleName.asString()
        else -> value.toString().substringAfterLast('.').ifBlank { default }
    }

    // ---- Internal records ----

    private data class OverwriteEntry(val targetName: String, val targetDesc: String, val handlerName: String, val handlerDesc: String)
    private data class OriginalEntry(val handlerName: String, val handlerDesc: String, val targetName: String)
    private data class RedirectEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val call: String, val handlerName: String, val handlerDesc: String)
    private data class InjectEntry(val targetMethod: String, val point: String, val atDesc: String, val atIndex: Int, val cancellable: Boolean, val returnable: Boolean, val handlerName: String, val handlerDesc: String, val shift: String)
    private data class ShadowEntry(val handlerName: String, val handlerDesc: String, val targetName: String)
    private data class ShadowFieldEntry(val mixinFieldName: String, val fieldDesc: String, val targetFieldName: String)
    private data class InjectLocalEntry(val handlerName: String, val handlerDesc: String, val paramIndex: Int, val slot: Int, val ordinal: Int, val argsOnly: Boolean)
    private data class ModifyReturnValueEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
    private data class AccessorEntry(val handlerName: String, val handlerDesc: String, val kind: String, val targetField: String)
    private data class InvokerEntry(val handlerName: String, val handlerDesc: String, val targetName: String)
    private data class ModifyConstantEntry(val targetMethod: String, val type: String, val value: String, val index: Int, val handlerName: String, val handlerDesc: String)
    private data class ModifyArgEntry(val targetMethod: String, val invokeDesc: String, val argIndex: Int, val handlerName: String, val handlerDesc: String)
    private data class ModifyExpressionValueEntry(val targetMethod: String, val point: String, val atDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
    private data class ModifyArgsEntry(val targetMethod: String, val invokeDesc: String, val handlerName: String, val handlerDesc: String)
    private data class ModifyReceiverEntry(val targetMethod: String, val invokeDesc: String, val handlerName: String, val handlerDesc: String)
}
