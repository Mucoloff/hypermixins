package net.echo.hypermixins.idea.util

import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope

/** Fully-qualified annotation names — source of truth for all plugin lookups. */
object MixinAnnotations {
    private const val PKG = "net.echo.hypermixins.annotations"

    // Class-level
    const val MIXIN       = "$PKG.Mixin"
    const val IMPLEMENTS  = "$PKG.Implements"

    // Method-targeting (carry `method = "..."` or `value = "..."` naming the target)
    const val OVERWRITE              = "$PKG.Overwrite"
    const val ORIGINAL               = "$PKG.Original"
    const val REDIRECT               = "$PKG.Redirect"
    const val INJECT                 = "$PKG.Inject"
    const val WRAP_OP                = "$PKG.WrapOperation"
    const val WRAP_MTH               = "$PKG.WrapMethod"
    const val WRAP_WITH_CONDITION    = "$PKG.WrapWithCondition"
    const val MODIFY_RETURN_VALUE    = "$PKG.ModifyReturnValue"
    const val MODIFY_CONSTANT        = "$PKG.ModifyConstant"
    const val MODIFY_ARG             = "$PKG.ModifyArg"
    const val MODIFY_ARGS            = "$PKG.ModifyArgs"
    const val MODIFY_EXPRESSION      = "$PKG.ModifyExpressionValue"
    const val MODIFY_RECEIVER        = "$PKG.ModifyReceiver"
    const val SURROGATE              = "$PKG.Surrogate"
    const val SHADOW                 = "$PKG.Shadow"
    const val ACCESSOR               = "$PKG.Accessor"
    const val INVOKER                = "$PKG.Invoker"

    // Site / handler metadata (no target on their own)
    const val AT          = "$PKG.At"
    const val SLICE       = "$PKG.Slice"
    const val CANCELLABLE = "$PKG.Cancellable"
    const val DEFINITION  = "$PKG.Definition"
    const val DEFINITIONS = "$PKG.Definitions"
    const val EXPRESSION  = "$PKG.Expression"

    // Pure markers (parameter / method)
    const val SOFT    = "$PKG.Soft"
    const val FINAL   = "$PKG.Final"
    const val MUTABLE = "$PKG.Mutable"
    const val COERCE  = "$PKG.Coerce"
    const val SHARE   = "$PKG.Share"
    const val UNIQUE  = "$PKG.Unique"
    const val LOCAL   = "$PKG.Local"

    /** Method-targeting annotations eligible for gutter / target-not-found resolution. */
    val METHOD_TARGETING: Set<String> = setOf(
        OVERWRITE, ORIGINAL, REDIRECT, INJECT, WRAP_OP, WRAP_MTH, WRAP_WITH_CONDITION,
        MODIFY_RETURN_VALUE, MODIFY_CONSTANT, MODIFY_ARG, MODIFY_ARGS,
        MODIFY_EXPRESSION, MODIFY_RECEIVER, SURROGATE, SHADOW, ACCESSOR, INVOKER
    )
}

object MixinPsiUtil {

    /** Returns the `@Mixin` annotation or null. */
    fun getMixinAnnotation(cls: PsiClass): PsiAnnotation? =
        cls.getAnnotation(MixinAnnotations.MIXIN)

    fun isMixinClass(cls: PsiClass): Boolean =
        cls.hasAnnotation(MixinAnnotations.MIXIN)

    /** Reads `@Mixin("com.example.Foo")` → `"com.example.Foo"`. */
    fun getTargetClassName(cls: PsiClass): String? =
        getMixinAnnotation(cls)
            ?.findAttributeValue("value")
            ?.let { it as? PsiLiteralExpression }
            ?.value as? String

    /** Resolves the target `PsiClass` from `@Mixin.value()`. */
    fun resolveTargetClass(cls: PsiClass): PsiClass? {
        val name = getTargetClassName(cls) ?: return null
        return JavaPsiFacade.getInstance(cls.project)
            .findClass(name, GlobalSearchScope.allScope(cls.project))
    }

    /** Reads a string literal from a named annotation attribute. */
    fun stringAttr(annotation: PsiAnnotation, attribute: String): String? =
        (annotation.findAttributeValue(attribute) as? PsiLiteralExpression)?.value as? String

    /**
     * Converts a PsiType to a JVM method descriptor fragment (e.g. `Ljava/lang/String;`, `I`, `[B`).
     */
    fun toJvmDesc(type: PsiType): String = when (type) {
        PsiTypes.voidType()    -> "V"
        PsiTypes.booleanType() -> "Z"
        PsiTypes.byteType()    -> "B"
        PsiTypes.charType()    -> "C"
        PsiTypes.shortType()   -> "S"
        PsiTypes.intType()     -> "I"
        PsiTypes.longType()    -> "J"
        PsiTypes.floatType()   -> "F"
        PsiTypes.doubleType()  -> "D"
        is PsiArrayType        -> "[" + toJvmDesc(type.componentType)
        is PsiClassType        -> {
            val resolved = type.resolve()
            val fqn = resolved?.qualifiedName ?: type.canonicalText
            "L${fqn.replace('.', '/')};"
        }
        else -> type.canonicalText
    }

    /**
     * Formats a method as `owner/Class.name(args)ret` — the format used in `@At(desc = "...")`.
     */
    fun formatInvokeDesc(ownerClass: PsiClass, method: PsiMethod): String {
        val owner = (ownerClass.qualifiedName ?: return "").replace('.', '/')
        val args  = method.parameterList.parameters.joinToString("") { toJvmDesc(it.type) }
        val ret   = method.returnType?.let { toJvmDesc(it) } ?: "V"
        return "$owner.${method.name}($args)$ret"
    }

    /**
     * Collects all unique invoke descriptors reachable from method call expressions
     * in the body of [targetMethod]. Falls back to all declared methods of [fallbackClass]
     * when the method has no source body.
     */
    fun collectInvokeDescs(targetMethod: PsiMethod, fallbackClass: PsiClass): List<String> {
        val body = targetMethod.body
        if (body != null) {
            val results = mutableListOf<String>()
            body.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    val resolved = expression.resolveMethod() ?: return
                    val owner = resolved.containingClass ?: return
                    results += formatInvokeDesc(owner, resolved)
                }
            })
            return results.distinct()
        }
        // No source: suggest all methods of the target class
        return fallbackClass.allMethods.map { m ->
            formatInvokeDesc(m.containingClass ?: fallbackClass, m)
        }.distinct()
    }
}
