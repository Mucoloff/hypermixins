package net.echo.hypermixins.processor

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import net.echo.hypermixins.processor.JvmDescriptors.descriptor
import net.echo.hypermixins.processor.JvmDescriptors.dropFirstArgDesc

/**
 * KSP-classpath probes that flag {@code @Shadow}/{@code @Invoker} targets visible as private
 * and {@code @Overwrite}/{@code @Original} targets visible as static. Both probes silently
 * skip targets the resolver cannot see — runtime falls back to reflective discovery.
 */
internal object TargetProbes {

    fun privateShadowTargets(
        resolver: Resolver,
        targetClass: String,
        shadows: List<ShadowEntry>,
        invokers: List<InvokerEntry>
    ): Set<String> {
        val result = mutableSetOf<String>()
        val targetDecl = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(targetClass)
        ) ?: return result
        for (sh in shadows) recordIfPrivate(targetDecl, sh.targetName, dropFirstArgDesc(sh.handlerDesc), result)
        for (iv in invokers) recordIfPrivate(targetDecl, iv.targetName, dropFirstArgDesc(iv.handlerDesc), result)
        return result
    }

    fun staticTargets(
        resolver: Resolver,
        targetClass: String,
        originals: List<OriginalEntry>,
        overwrites: List<OverwriteEntry>
    ): Set<String> {
        val result = mutableSetOf<String>()
        val targetDecl = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(targetClass)
        ) ?: return result
        val pairs = mutableSetOf<Pair<String, String>>()
        for (oe in originals) {
            val td = dropFirstArgDesc(oe.handlerDesc)
            pairs += oe.targetName to td
        }
        for (oe in overwrites) {
            pairs += oe.targetName to oe.targetDesc
        }
        for ((name, desc) in pairs) {
            val match = targetDecl.getDeclaredFunctions().firstOrNull {
                it.simpleName.asString() == name && descriptor(it) == desc
            }
            if (match != null && Modifier.JAVA_STATIC in match.modifiers) {
                result += name + desc
            }
        }
        return result
    }

    private fun recordIfPrivate(
        targetDecl: KSClassDeclaration,
        name: String, desc: String,
        result: MutableSet<String>
    ) {
        val match = targetDecl.getDeclaredFunctions().firstOrNull {
            it.simpleName.asString() == name && descriptor(it) == desc
        } ?: return
        if (Modifier.PRIVATE in match.modifiers) {
            result += name + desc
        }
    }
}
