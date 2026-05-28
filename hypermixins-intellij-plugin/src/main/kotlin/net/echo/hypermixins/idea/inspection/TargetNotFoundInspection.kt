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

                checkOverwrite(method, targetClass)
                checkRedirect(method, targetClass)
                checkInject(method, targetClass)
            }

            private fun checkOverwrite(method: PsiMethod, targetClass: PsiClass) {
                val ann = method.getAnnotation(MixinAnnotations.OVERWRITE) ?: return
                val targetName = MixinPsiUtil.stringAttr(ann, "value") ?: method.name
                if (targetClass.findMethodsByName(targetName, true).isEmpty()) {
                    holder.registerProblem(ann, "No method '$targetName' found in target '${targetClass.name}'")
                }
            }

            private fun checkRedirect(method: PsiMethod, targetClass: PsiClass) {
                val ann = method.getAnnotation(MixinAnnotations.REDIRECT) ?: return
                val atAnn = method.getAnnotation(MixinAnnotations.AT) ?: return
                val targetMethodName = MixinPsiUtil.stringAttr(ann, "value") ?: return
                if (targetClass.findMethodsByName(targetMethodName, true).isEmpty()) {
                    holder.registerProblem(ann, "No method '$targetMethodName' found in target '${targetClass.name}'")
                }
                val desc = MixinPsiUtil.stringAttr(atAnn, "desc")
                if (desc != null && !desc.contains('(')) {
                    holder.registerProblem(atAnn, "@At desc '$desc' is not a valid method descriptor (missing '(')")
                }
            }

            private fun checkInject(method: PsiMethod, targetClass: PsiClass) {
                val ann = method.getAnnotation(MixinAnnotations.INJECT) ?: return
                val targetName = MixinPsiUtil.stringAttr(ann, "value") ?: return
                if (targetClass.findMethodsByName(targetName, true).isEmpty()) {
                    holder.registerProblem(ann, "No method '$targetName' found in target '${targetClass.name}'")
                }
            }
        }
}
