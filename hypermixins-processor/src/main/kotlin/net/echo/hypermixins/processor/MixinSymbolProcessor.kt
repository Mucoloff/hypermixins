package net.echo.hypermixins.processor

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.javapoet.*
import java.io.OutputStreamWriter
import java.lang.reflect.Modifier as JMod
import javax.lang.model.element.Modifier

// Generated descriptor ABI (must match MixinDescriptor.java in the runtime module):
//   overwriteEntries:  [targetName, targetDesc, handlerName, handlerDesc]
//   originalEntries:   [handlerName, handlerDesc, targetName]
//   redirectEntries:   [targetMethod, invokeDesc, index, call, handlerName, handlerDesc]
//   injectEntries:     [targetMethod, point, atDesc, atIndex,
//                       cancellable, returnable, handlerName, handlerDesc]
//   injectCaptureLocals:[handlerName, handlerDesc, paramIndex, slot]
//   injectShifts:      [handlerName, handlerDesc, shift]   — rows only for non-BEFORE
//   modifyReturnValueEntries: [targetMethod, invokeDesc, index, handlerName, handlerDesc]
//   shadowEntries:     [handlerName, handlerDesc, targetName]
//   shadowFieldEntries:[mixinFieldName, fieldDesc, targetFieldName]
//   shadowStaticFieldEntries:[mixinFieldName, fieldDesc, targetFieldName]
//   staticTargetMethods:[name, desc]   — only entries the resolver could see as static
//   syntheticNames:    [targetName, targetDesc, mangledOriginalName, dispatchName]

private const val MIXIN_FQN       = "net.echo.hypermixins.annotations.Mixin"
private const val OVERWRITE_FQN   = "net.echo.hypermixins.annotations.Overwrite"
private const val ORIGINAL_FQN    = "net.echo.hypermixins.annotations.Original"
private const val REDIRECT_FQN    = "net.echo.hypermixins.annotations.Redirect"
private const val INJECT_FQN      = "net.echo.hypermixins.annotations.Inject"
private const val CANCELLABLE_FQN = "net.echo.hypermixins.annotations.Cancellable"
private const val SHADOW_FQN      = "net.echo.hypermixins.annotations.Shadow"
private const val MODIFY_RV_FQN   = "net.echo.hypermixins.annotations.ModifyReturnValue"

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

        cls.declarations.filterIsInstance<KSFunctionDeclaration>().forEach { fn ->
            when {
                fn.hasAnnotation(OVERWRITE_FQN)  -> validateAndCollectOverwrite(fn, targetClass, overwrites)
                fn.hasAnnotation(ORIGINAL_FQN)   -> validateAndCollectOriginal(fn, originals)
                fn.hasAnnotation(REDIRECT_FQN)   -> validateAndCollectRedirect(fn, redirects)
                fn.hasAnnotation(INJECT_FQN)     -> validateAndCollectInject(fn, injects, injectLocals)
                fn.hasAnnotation(SHADOW_FQN)     -> validateAndCollectShadow(fn, shadows)
                fn.hasAnnotation(MODIFY_RV_FQN)  -> validateAndCollectModifyReturnValue(fn, modifyRvs)
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
        generateDescriptor(cls, targetClass, overwrites, originals, redirects, injects, injectLocals, shadows, shadowFields, shadowStaticFields, modifyRvs, staticTargets)
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
                if (slot >= 0) {
                    localsOut += InjectLocalEntry(handlerName, handlerDesc, i, slot)
                }
            }
        }
    }

    // ---- Descriptor helpers ----

    private fun KSFunctionDeclaration.modifiers(): Set<Modifier> {
        val result = mutableSetOf<Modifier>()
        if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC in this.modifiers) result += Modifier.STATIC
        return result
    }

    private fun buildTargetDescriptor(fn: KSFunctionDeclaration): String {
        val params = fn.parameters.drop(1)
        return "(" + params.joinToString("") { toJvmDesc(it.type.resolve()) } +
                ")" + toJvmDesc(fn.returnType?.resolve())
    }

    private fun descriptor(fn: KSFunctionDeclaration): String =
        "(" + fn.parameters.joinToString("") { toJvmDesc(it.type.resolve()) } +
                ")" + toJvmDesc(fn.returnType?.resolve())

    private fun toJvmDesc(type: KSType?): String {
        if (type == null) return "V"
        val decl = type.declaration
        val fqn  = decl.qualifiedName?.asString() ?: return "Ljava/lang/Object;"
        if (type.isMarkedNullable && fqn == "kotlin.Unit") return "V"
        return when (fqn) {
            "kotlin.Unit", "java.lang.Void", "void"   -> "V"
            "kotlin.Boolean", "java.lang.Boolean", "boolean" -> "Z"
            "kotlin.Byte",    "java.lang.Byte",    "byte"    -> "B"
            "kotlin.Char",    "java.lang.Character","char"   -> "C"
            "kotlin.Short",   "java.lang.Short",   "short"   -> "S"
            "kotlin.Int",     "java.lang.Integer",  "int"    -> "I"
            "kotlin.Long",    "java.lang.Long",     "long"   -> "J"
            "kotlin.Float",   "java.lang.Float",    "float"  -> "F"
            "kotlin.Double",  "java.lang.Double",   "double" -> "D"
            "kotlin.Any",     "java.lang.Object"            -> "Ljava/lang/Object;"
            "kotlin.String",  "java.lang.String"            -> "Ljava/lang/String;"
            "java.util.List", "kotlin.collections.List", "kotlin.collections.MutableList" -> "Ljava/util/List;"
            "java.util.Map",  "kotlin.collections.Map",  "kotlin.collections.MutableMap"  -> "Ljava/util/Map;"
            "java.util.Set",  "kotlin.collections.Set",  "kotlin.collections.MutableSet"  -> "Ljava/util/Set;"
            "java.util.Collection", "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "Ljava/util/Collection;"
            "java.lang.Iterable", "kotlin.collections.Iterable" -> "Ljava/lang/Iterable;"
            else -> {
                if (decl is KSClassDeclaration && decl.classKind == ClassKind.ENUM_CLASS)
                    "L${fqn.replace('.', '/')};"
                else
                    "L${fqn.replace('.', '/')};"
            }
        }
    }

    // ---- Code generation ----

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

    private fun dropFirstArgDesc(desc: String): String {
        // (Ljava/lang/Object;...args)ret → (args)ret
        if (!desc.startsWith("(Ljava/lang/Object;")) return desc
        return "(" + desc.removePrefix("(Ljava/lang/Object;")
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
        staticTargets: Set<String>
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
            arrayOf(it.handlerName, it.handlerDesc, it.paramIndex.toString(), it.slot.toString())
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
        classBuilder.addMethod(entriesMethod("staticTargetMethods", staticTargets.map {
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
    private data class InjectLocalEntry(val handlerName: String, val handlerDesc: String, val paramIndex: Int, val slot: Int)
    private data class ModifyReturnValueEntry(val targetMethod: String, val invokeDesc: String, val index: Int, val handlerName: String, val handlerDesc: String)
}
