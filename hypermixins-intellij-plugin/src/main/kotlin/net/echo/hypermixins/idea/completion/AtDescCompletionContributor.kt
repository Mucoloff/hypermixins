package net.echo.hypermixins.idea.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import net.echo.hypermixins.idea.MixinIcons
import net.echo.hypermixins.idea.util.MixinAnnotations
import net.echo.hypermixins.idea.util.MixinPsiUtil

class AtDescCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(PsiJavaToken::class.java),
            AtDescCompletionProvider()
        )
    }

    private class AtDescCompletionProvider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            val position = parameters.position
            val atAnnotation = findAtAnnotation(position) ?: return
            if (!isDescAttribute(position, atAnnotation)) return

            val handlerMethod = findEnclosingHandlerMethod(atAnnotation) ?: return
            val mixinClass = handlerMethod.containingClass ?: return
            if (!MixinPsiUtil.isMixinClass(mixinClass)) return
            val targetClass = MixinPsiUtil.resolveTargetClass(mixinClass) ?: return

            val targetMethodName = resolveTargetMethodName(handlerMethod) ?: return
            val targetMethods = targetClass.findMethodsByName(targetMethodName, true)

            val descs: List<String> = when {
                targetMethods.isNotEmpty() ->
                    targetMethods.flatMap { MixinPsiUtil.collectInvokeDescs(it, targetClass) }.distinct()
                else ->
                    MixinPsiUtil.collectInvokeDescs(
                        targetClass.methods.firstOrNull() ?: return,
                        targetClass
                    )
            }

            for (desc in descs) {
                val ownerSimple = desc.substringBefore('.').substringAfterLast('/')
                result.addElement(
                    LookupElementBuilder.create(desc)
                        .withIcon(MixinIcons.MIXIN)
                        .withTypeText(ownerSimple)
                        .withPresentableText(desc)
                )
            }
        }

        private fun findAtAnnotation(element: PsiElement): PsiAnnotation? {
            var current: PsiElement? = element
            while (current != null) {
                if (current is PsiAnnotation && current.qualifiedName == MixinAnnotations.AT) {
                    return current
                }
                current = current.parent
            }
            return null
        }

        private fun isDescAttribute(element: PsiElement, atAnnotation: PsiAnnotation): Boolean {
            var current: PsiElement? = element.parent
            while (current != null && current != atAnnotation) {
                if (current is PsiNameValuePair) {
                    return current.name == "desc" || current.name == null
                }
                current = current.parent
            }
            return false
        }

        private fun findEnclosingHandlerMethod(element: PsiElement): PsiMethod? {
            var current: PsiElement? = element
            while (current != null) {
                if (current is PsiMethod) return current
                current = current.parent
            }
            return null
        }

        private fun resolveTargetMethodName(method: PsiMethod): String? {
            val redirectAnn = method.getAnnotation(MixinAnnotations.REDIRECT)
                ?: method.getAnnotation(MixinAnnotations.INJECT)
                ?: method.getAnnotation(MixinAnnotations.WRAP_OP)
                ?: method.getAnnotation(MixinAnnotations.WRAP_MTH)
                ?: return null
            return MixinPsiUtil.stringAttr(redirectAnn, "value")
        }
    }
}
