package net.echo.hypermixins.idea.marker

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.psi.*
import net.echo.hypermixins.idea.MixinIcons
import net.echo.hypermixins.idea.util.MixinAnnotations
import net.echo.hypermixins.idea.util.MixinPsiUtil

class MixinLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val identifier = element as? PsiIdentifier ?: return

        when (val parent = identifier.parent) {
            is PsiClass  -> handleClass(identifier, parent, result)
            is PsiMethod -> handleMethod(identifier, parent, result)
        }
    }

    private fun handleClass(
        id: PsiIdentifier,
        cls: PsiClass,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        if (!MixinPsiUtil.isMixinClass(cls)) return
        val target = MixinPsiUtil.resolveTargetClass(cls) ?: return

        val marker = NavigationGutterIconBuilder.create(MixinIcons.MIXIN)
            .setTargets(target)
            .setTooltipText("Navigate to mixin target: ${target.qualifiedName}")
            .createLineMarkerInfo(id)
        result.add(marker)
    }

    private fun handleMethod(
        id: PsiIdentifier,
        method: PsiMethod,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val cls = method.containingClass ?: return
        if (!MixinPsiUtil.isMixinClass(cls)) return
        val targetClass = MixinPsiUtil.resolveTargetClass(cls) ?: return

        val annotationFqn = method.annotations.firstNotNullOfOrNull { ann ->
            val fqn = ann.qualifiedName ?: return@firstNotNullOfOrNull null
            if (fqn in MixinAnnotations.METHOD_TARGETING) fqn else null
        } ?: return

        val methodName = resolveTargetMethodName(method, annotationFqn) ?: method.name
        val targets = targetClass.findMethodsByName(methodName, true).toList()
        if (targets.isEmpty()) return

        val tooltip = when (annotationFqn) {
            MixinAnnotations.OVERWRITE         -> "Overwrites ${targetClass.name}.$methodName"
            MixinAnnotations.REDIRECT          -> "Redirects call in ${targetClass.name}.$methodName"
            MixinAnnotations.INJECT            -> "Injects into ${targetClass.name}.$methodName"
            MixinAnnotations.ORIGINAL          -> "Original of ${targetClass.name}.$methodName"
            MixinAnnotations.SURROGATE         -> "Surrogate for ${targetClass.name}.$methodName"
            MixinAnnotations.SHADOW            -> "Shadows ${targetClass.name}.$methodName"
            MixinAnnotations.WRAP_WITH_CONDITION -> "Wraps with condition in ${targetClass.name}.$methodName"
            MixinAnnotations.WRAP_OP           -> "Wraps operation in ${targetClass.name}.$methodName"
            MixinAnnotations.WRAP_MTH          -> "Wraps method ${targetClass.name}.$methodName"
            MixinAnnotations.MODIFY_RETURN_VALUE -> "Modifies return value in ${targetClass.name}.$methodName"
            MixinAnnotations.MODIFY_CONSTANT   -> "Modifies constant in ${targetClass.name}.$methodName"
            MixinAnnotations.MODIFY_ARG        -> "Modifies arg in ${targetClass.name}.$methodName"
            MixinAnnotations.MODIFY_ARGS       -> "Modifies args in ${targetClass.name}.$methodName"
            MixinAnnotations.MODIFY_EXPRESSION -> "Modifies expression in ${targetClass.name}.$methodName"
            MixinAnnotations.MODIFY_RECEIVER   -> "Modifies receiver in ${targetClass.name}.$methodName"
            MixinAnnotations.ACCESSOR          -> "Accessor for ${targetClass.name}.$methodName"
            MixinAnnotations.INVOKER           -> "Invoker for ${targetClass.name}.$methodName"
            else                               -> "Targets ${targetClass.name}.$methodName"
        }

        val marker = NavigationGutterIconBuilder.create(MixinIcons.MIXIN)
            .setTargets(targets)
            .setTooltipText(tooltip)
            .createLineMarkerInfo(id)
        result.add(marker)
    }

    private fun resolveTargetMethodName(method: PsiMethod, annotationFqn: String): String? {
        val annotation = method.getAnnotation(annotationFqn) ?: return null
        // Most newer annotations use `method = "..."`; legacy ones use `value = "..."`.
        return MixinPsiUtil.stringAttr(annotation, "method")
            ?: MixinPsiUtil.stringAttr(annotation, "value")
            ?: method.name
    }
}
