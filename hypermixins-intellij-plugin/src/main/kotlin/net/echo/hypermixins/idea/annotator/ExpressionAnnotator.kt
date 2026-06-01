package net.echo.hypermixins.idea.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import net.echo.hypermixins.agent.ExpressionValidator
import net.echo.hypermixins.idea.util.MixinAnnotations

/**
 * Flags `@Expression("…")` strings whose DSL syntax does not parse, using the runtime
 * [ExpressionValidator] facade so the IDE shows the same parse error the transform would throw.
 *
 * Syntax only — semantic checks (definition-id resolution) need a resolved handler descriptor
 * and run at transform time.
 */
class ExpressionAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiLiteralExpression) return
        val value = element.value as? String ?: return
        if (!isExpressionValue(element)) return

        val error = ExpressionValidator.parseError(value) ?: return
        holder.newAnnotation(HighlightSeverity.ERROR, "Invalid @Expression: $error")
            .range(element)
            .create()
    }

    /** True when [literal] is the `value` argument of an `@Expression` annotation. */
    private fun isExpressionValue(literal: PsiLiteralExpression): Boolean {
        // literal -> (name value pair | annotation params) -> annotation
        var node: PsiElement? = literal.parent
        while (node != null && node !is PsiAnnotation) {
            node = node.parent
        }
        val annotation = node as? PsiAnnotation ?: return false
        return annotation.qualifiedName == MixinAnnotations.EXPRESSION
    }
}
