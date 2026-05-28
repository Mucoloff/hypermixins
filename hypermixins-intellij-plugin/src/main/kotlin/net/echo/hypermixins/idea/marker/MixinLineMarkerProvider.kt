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
            when (fqn) {
                MixinAnnotations.OVERWRITE,
                MixinAnnotations.REDIRECT,
                MixinAnnotations.INJECT,
                MixinAnnotations.ORIGINAL,
                MixinAnnotations.WRAP_OP,
                MixinAnnotations.WRAP_MTH -> fqn
                else -> null
            }
        } ?: return

        val methodName = resolveTargetMethodName(method, annotationFqn) ?: method.name
        val targets = targetClass.findMethodsByName(methodName, true).toList()
        if (targets.isEmpty()) return

        val tooltip = when (annotationFqn) {
            MixinAnnotations.OVERWRITE -> "Overwrites ${targetClass.name}.$methodName"
            MixinAnnotations.REDIRECT  -> "Redirects call in ${targetClass.name}.$methodName"
            MixinAnnotations.INJECT    -> "Injects into ${targetClass.name}.$methodName"
            MixinAnnotations.ORIGINAL  -> "Original of ${targetClass.name}.$methodName"
            else                       -> "Targets ${targetClass.name}.$methodName"
        }

        val marker = NavigationGutterIconBuilder.create(MixinIcons.MIXIN)
            .setTargets(targets)
            .setTooltipText(tooltip)
            .createLineMarkerInfo(id)
        result.add(marker)
    }

    private fun resolveTargetMethodName(method: PsiMethod, annotationFqn: String): String? {
        val annotation = method.getAnnotation(annotationFqn) ?: return null
        return MixinPsiUtil.stringAttr(annotation, "value") ?: method.name
    }
}
