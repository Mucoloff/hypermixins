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
        generateDescriptor(cls, targetClass, overwrites, originals, redirects, injects, injectLocals, shadows, shadowFields, shadowStaticFields, modifyRvs, modifyConsts, modifyArgs, modifyExprs, modifyArgsList, modifyReceivers, accessors, invokers, staticTargets, privateShadowTargets)
    }

    // ---- Code generation ----

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

}
