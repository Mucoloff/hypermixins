package net.echo.hypermixins.idea.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import net.echo.hypermixins.idea.util.MixinAnnotations
import net.echo.hypermixins.idea.util.MixinPsiUtil

class TargetNotFoundInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {

            override fun visitClass(cls: PsiClass) {
                val annotation = MixinPsiUtil.getMixinAnnotation(cls) ?: return
                val targetName = MixinPsiUtil.getTargetClassName(cls)
                if (targetName.isNullOrBlank()) {
                    holder.registerProblem(annotation, "@Mixin value() must not be empty")
                    return
                }
                if (MixinPsiUtil.resolveTargetClass(cls) == null) {
                    val valueExpr = annotation.findAttributeValue("value") ?: annotation
                    holder.registerProblem(valueExpr, "Mixin target class '$targetName' not found")
                }
            }

            override fun visitMethod(method: PsiMethod) {
                val cls = method.containingClass ?: return
                if (!MixinPsiUtil.isMixinClass(cls)) return
                val targetClass = MixinPsiUtil.resolveTargetClass(cls) ?: return

                for (fqn in MixinAnnotations.METHOD_TARGETING) {
                    val ann = method.getAnnotation(fqn) ?: continue
                    checkAnnotationTarget(ann, fqn, method, targetClass)
                }
                // @At#desc shape (legacy @Redirect-style) — only flag obvious malformed descriptors.
                method.getAnnotation(MixinAnnotations.AT)?.let { atAnn ->
                    val desc = MixinPsiUtil.stringAttr(atAnn, "desc")
                    if (!desc.isNullOrEmpty() && !desc.contains('(') && !desc.contains(':')) {
                        holder.registerProblem(atAnn, "@At desc '$desc' is not a valid invoke / field descriptor")
                    }
                }
            }

            private fun checkAnnotationTarget(
                ann: PsiAnnotation, fqn: String, handler: PsiMethod, targetClass: PsiClass
            ) {
                val targetName = MixinPsiUtil.stringAttr(ann, "method")
                    ?: MixinPsiUtil.stringAttr(ann, "value")
                    ?: handler.name
                if (targetName.isEmpty()) return
                if (targetClass.findMethodsByName(targetName, true).isEmpty()) {
                    holder.registerProblem(ann, "No method '$targetName' found in target '${targetClass.name}'")
                }
            }
        }
}
