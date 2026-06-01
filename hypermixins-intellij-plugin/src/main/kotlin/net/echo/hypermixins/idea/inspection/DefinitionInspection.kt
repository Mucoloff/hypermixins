package net.echo.hypermixins.idea.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import net.echo.hypermixins.idea.util.MixinAnnotations
import net.echo.hypermixins.idea.util.MixinPsiUtil

/**
 * Validates `@Definition` shape — the semantic checks the runtime's `ExpressionMatcher.compile`
 * enforces at transform time, surfaced at edit time:
 *
 * - exactly one of `method` / `field` / `type` is non-empty,
 * - `id` is non-empty,
 * - no duplicate `id` among the `@Definition`s on the same handler.
 */
class DefinitionInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                val defs = collectDefinitions(method)
                if (defs.isEmpty()) return

                val seenIds = HashMap<String, Int>()
                for (def in defs) {
                    val id = MixinPsiUtil.stringAttr(def, "id").orEmpty()
                    if (id.isBlank()) {
                        holder.registerProblem(def, "@Definition id() must not be empty")
                    } else {
                        seenIds[id] = (seenIds[id] ?: 0) + 1
                    }

                    val method0 = MixinPsiUtil.stringAttr(def, "method").orEmpty()
                    val field0 = MixinPsiUtil.stringAttr(def, "field").orEmpty()
                    val type0 = MixinPsiUtil.stringAttr(def, "type").orEmpty()
                    val set = listOf(method0, field0, type0).count { it.isNotBlank() }
                    if (set != 1) {
                        holder.registerProblem(def,
                            "@Definition must set exactly one of method() / field() / type()")
                    }
                }
                for ((id, count) in seenIds) {
                    if (count > 1) {
                        for (def in defs) {
                            if (MixinPsiUtil.stringAttr(def, "id") == id) {
                                holder.registerProblem(def, "Duplicate @Definition id '$id' on this handler")
                            }
                        }
                    }
                }
            }

            /** Collects @Definition annotations directly on the method (repeatable). */
            private fun collectDefinitions(method: PsiMethod): List<PsiAnnotation> =
                method.annotations.filter { it.qualifiedName == MixinAnnotations.DEFINITION }
        }
}
