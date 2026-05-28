package net.echo.hypermixins.idea.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import net.echo.hypermixins.idea.util.MixinAnnotations
import net.echo.hypermixins.idea.util.MixinPsiUtil

private const val CALLBACK_INFO     = "net.echo.hypermixins.annotations.CallbackInfo"
private const val CALLBACK_INFO_RET = "net.echo.hypermixins.annotations.CallbackInfoReturnable"

class SignatureMismatchInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                val cls = method.containingClass ?: return
                if (!MixinPsiUtil.isMixinClass(cls)) return

                checkOverwriteSelf(method, holder)
                checkRedirectDescriptor(method, holder)
                checkInjectCancellable(method, holder)
            }
        }

    private fun checkOverwriteSelf(method: PsiMethod, holder: ProblemsHolder) {
        method.getAnnotation(MixinAnnotations.OVERWRITE) ?: return
        val params = method.parameterList.parameters
        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "@Overwrite must have first parameter of type Object (self)",
                AddObjectSelfFix(method)
            )
            return
        }
        if (params[0].type.canonicalText != "java.lang.Object") {
            holder.registerProblem(
                params[0],
                "@Overwrite first parameter must be Object (self), found '${params[0].type.presentableText}'",
                FixFirstParamToObjectFix(method)
            )
        }
    }

    private fun checkRedirectDescriptor(method: PsiMethod, holder: ProblemsHolder) {
        method.getAnnotation(MixinAnnotations.REDIRECT) ?: return
        val atAnn = method.getAnnotation(MixinAnnotations.AT) ?: return
        val desc = MixinPsiUtil.stringAttr(atAnn, "desc") ?: return

        val parenIdx = desc.indexOf('(')
        if (parenIdx < 0) {
            holder.registerProblem(atAnn, "@At desc '$desc' missing '(' — not a method descriptor")
            return
        }
        val dotIdx = desc.indexOf('.')
        if (dotIdx < 0 || dotIdx > parenIdx) return

        val expectedArgDesc = desc.substring(parenIdx)
        val params = method.parameterList.parameters
        val handlerArgTypes = if (params.size > 1) params.drop(1) else emptyList()
        val handlerArgDesc = "(" +
                handlerArgTypes.joinToString("") { MixinPsiUtil.toJvmDesc(it.type) } +
                ")" +
                (method.returnType?.let { MixinPsiUtil.toJvmDesc(it) } ?: "V")

        if (handlerArgDesc != expectedArgDesc) {
            holder.registerProblem(
                method.parameterList,
                "@Redirect handler descriptor $handlerArgDesc doesn't match @At desc $expectedArgDesc"
            )
        }
    }

    private fun checkInjectCancellable(method: PsiMethod, holder: ProblemsHolder) {
        val injectAnn = method.getAnnotation(MixinAnnotations.INJECT) ?: return
        val cancellableAttr = injectAnn.findAttributeValue("cancellable")
        val flaggedCancellable = (cancellableAttr as? PsiLiteralExpression)?.value == true
                || method.getAnnotation(MixinAnnotations.CANCELLABLE) != null
        if (!flaggedCancellable) return

        val hasCallbackParam = method.parameterList.parameters.any {
            val t = it.type.canonicalText
            t == CALLBACK_INFO || t.startsWith(CALLBACK_INFO_RET)
        }
        if (!hasCallbackParam) {
            holder.registerProblem(
                method.parameterList,
                "@Inject with cancellable=true requires a CallbackInfo or CallbackInfoReturnable parameter"
            )
        }
    }

    private class AddObjectSelfFix(private val method: PsiMethod) : LocalQuickFix {
        override fun getFamilyName() = "Add Object self parameter"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val factory = JavaPsiFacade.getElementFactory(project)
            val scope = GlobalSearchScope.allScope(project)
            val param = factory.createParameter(
                "self",
                factory.createTypeByFQClassName("java.lang.Object", scope)
            )
            val list = method.parameterList
            val first = list.parameters.firstOrNull()
            if (first != null) list.addBefore(param, first) else list.add(param)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(list)
        }
    }

    private class FixFirstParamToObjectFix(private val method: PsiMethod) : LocalQuickFix {
        override fun getFamilyName() = "Change first parameter to Object"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val params = method.parameterList.parameters
            if (params.isEmpty()) return
            val factory = JavaPsiFacade.getElementFactory(project)
            val scope = GlobalSearchScope.allScope(project)
            val newParam = factory.createParameter(
                params[0].name ?: "self",
                factory.createTypeByFQClassName("java.lang.Object", scope)
            )
            params[0].replace(newParam)
        }
    }
}
