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

class MixinSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val collectors = Collectors(logger)
    private val emitter = DescriptorEmitter(codeGenerator)
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
        val wrapConditions = mutableListOf<WrapConditionEntry>()
        val wrapOperations = mutableListOf<WrapOperationEntry>()

        cls.declarations.filterIsInstance<KSFunctionDeclaration>().forEach { fn ->
            when {
                fn.hasAnnotation(OVERWRITE_FQN)  -> collectors.overwrite(fn, targetClass, overwrites)
                fn.hasAnnotation(ORIGINAL_FQN)   -> collectors.original(fn, originals)
                fn.hasAnnotation(REDIRECT_FQN)   -> collectors.redirect(fn, redirects)
                fn.hasAnnotation(INJECT_FQN)     -> collectors.inject(fn, injects, injectLocals)
                fn.hasAnnotation(SHADOW_FQN)     -> collectors.shadow(fn, shadows)
                fn.hasAnnotation(MODIFY_RV_FQN)  -> collectors.modifyReturnValue(fn, modifyRvs)
                fn.hasAnnotation(ACCESSOR_FQN)   -> collectors.accessor(fn, accessors)
                fn.hasAnnotation(INVOKER_FQN)    -> collectors.invoker(fn, invokers)
                fn.hasAnnotation(MODIFY_CONST_FQN) -> collectors.modifyConstant(fn, modifyConsts)
                fn.hasAnnotation(MODIFY_ARG_FQN) -> collectors.modifyArg(fn, modifyArgs)
                fn.hasAnnotation(MODIFY_EXPR_FQN) -> collectors.modifyExpr(fn, modifyExprs)
                fn.hasAnnotation(MODIFY_ARGS_FQN) -> collectors.modifyArgs(fn, modifyArgsList)
                fn.hasAnnotation(MODIFY_RECV_FQN) -> collectors.modifyReceiver(fn, modifyReceivers)
                fn.hasAnnotation(WRAP_COND_FQN)  -> collectors.wrapWithCondition(fn, wrapConditions)
                fn.hasAnnotation(WRAP_OP_FQN)    -> collectors.wrapOperation(fn, wrapOperations)
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
        val staticTargets = TargetProbes.staticTargets(resolver, targetClass, originals, overwrites)
        val privateShadowTargets = TargetProbes.privateShadowTargets(resolver, targetClass, shadows, invokers)
        val harvest = MixinHarvest(
            cls, targetClass, overwrites, originals, redirects, injects, injectLocals,
            shadows, shadowFields, shadowStaticFields, modifyRvs, modifyConsts, modifyArgs,
            modifyExprs, modifyArgsList, modifyReceivers, wrapConditions, wrapOperations, accessors, invokers,
            staticTargets, privateShadowTargets,
        )
        val result = emitter.emit(harvest) ?: return
        generatedDescriptors += result.descriptorFqn
        generatedMixinFqns += result.mixinFqn
        result.containingFile?.let { containingFiles += it }
    }

}
