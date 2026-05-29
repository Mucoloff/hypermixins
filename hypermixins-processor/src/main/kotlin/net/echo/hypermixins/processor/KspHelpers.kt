package net.echo.hypermixins.processor

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import javax.lang.model.element.Modifier

/**
 * KSP convenience extensions kept out of [MixinSymbolProcessor] so per-annotation collectors
 * can reuse them without dragging the processor's instance state into a top-level function.
 */

internal fun KSAnnotated.findAnnotation(fqn: String): KSAnnotation? =
    annotations.firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn }

internal fun KSAnnotated.hasAnnotation(fqn: String): Boolean = findAnnotation(fqn) != null

internal fun KSAnnotation.arg(name: String): Any? =
    arguments.firstOrNull { it.name?.asString() == name }?.value

internal fun readEnumArg(value: Any?, default: String): String = when (value) {
    null -> default
    is String -> value
    is KSType -> value.declaration.simpleName.asString()
    is KSClassDeclaration -> value.simpleName.asString()
    is KSDeclaration -> value.simpleName.asString()
    else -> value.toString().substringAfterLast('.').ifBlank { default }
}

internal fun KSFunctionDeclaration.javaModifiers(): Set<Modifier> {
    val result = mutableSetOf<Modifier>()
    if (com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC in this.modifiers) result += Modifier.STATIC
    return result
}
