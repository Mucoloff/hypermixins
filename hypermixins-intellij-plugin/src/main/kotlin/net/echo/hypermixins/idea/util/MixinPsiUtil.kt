package net.echo.hypermixins.idea.util

import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope

/** Fully-qualified annotation names — source of truth for all plugin lookups. */
object MixinAnnotations {
    const val MIXIN      = "net.echo.hypermixins.annotations.Mixin"
    const val OVERWRITE  = "net.echo.hypermixins.annotations.Overwrite"
    const val ORIGINAL   = "net.echo.hypermixins.annotations.Original"
    const val REDIRECT   = "net.echo.hypermixins.annotations.Redirect"
    const val AT         = "net.echo.hypermixins.annotations.At"
    const val INJECT     = "net.echo.hypermixins.annotations.Inject"
    const val WRAP_OP    = "net.echo.hypermixins.annotations.WrapOperation"
    const val WRAP_MTH   = "net.echo.hypermixins.annotations.WrapMethod"
    const val CANCELLABLE = "net.echo.hypermixins.annotations.Cancellable"
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
