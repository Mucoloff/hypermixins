package net.echo.hypermixins.processor

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Pure JVM-descriptor helpers used by every per-annotation collector and by the descriptor
 * generator. No KSP state, no I/O — kept as a top-level object so collectors can be split out
 * of [MixinSymbolProcessor] without dragging the whole processor's `this` along.
 */
internal object JvmDescriptors {

    /** Target-method descriptor: drops the leading mixin-self receiver. */
    fun buildTargetDescriptor(fn: KSFunctionDeclaration): String {
        val params = fn.parameters.drop(1)
        return "(" + params.joinToString("") { toJvmDesc(it.type.resolve()) } +
                ")" + toJvmDesc(fn.returnType?.resolve())
    }

    /** Full handler descriptor (self included). */
    fun descriptor(fn: KSFunctionDeclaration): String =
        "(" + fn.parameters.joinToString("") { toJvmDesc(it.type.resolve()) } +
                ")" + toJvmDesc(fn.returnType?.resolve())

    /** Drops the leading {@code Ljava/lang/Object;} receiver from a handler descriptor. */
    fun dropFirstArgDesc(desc: String): String {
        if (!desc.startsWith("(Ljava/lang/Object;")) return desc
        return "(" + desc.removePrefix("(Ljava/lang/Object;")
    }

    fun toJvmDesc(type: KSType?): String {
        if (type == null) return "V"
        val decl = type.declaration
        val fqn  = decl.qualifiedName?.asString() ?: return "Ljava/lang/Object;"
        if (type.isMarkedNullable && fqn == "kotlin.Unit") return "V"
        return when (fqn) {
            "kotlin.Unit", "java.lang.Void", "void"   -> "V"
            "kotlin.Boolean", "java.lang.Boolean", "boolean" -> "Z"
            "kotlin.Byte",    "java.lang.Byte",    "byte"    -> "B"
            "kotlin.Char",    "java.lang.Character","char"   -> "C"
            "kotlin.Short",   "java.lang.Short",   "short"   -> "S"
            "kotlin.Int",     "java.lang.Integer",  "int"    -> "I"
            "kotlin.Long",    "java.lang.Long",     "long"   -> "J"
            "kotlin.Float",   "java.lang.Float",    "float"  -> "F"
            "kotlin.Double",  "java.lang.Double",   "double" -> "D"
            "kotlin.Any",     "java.lang.Object"            -> "Ljava/lang/Object;"
            "kotlin.String",  "java.lang.String"            -> "Ljava/lang/String;"
            "java.util.List", "kotlin.collections.List", "kotlin.collections.MutableList" -> "Ljava/util/List;"
            "java.util.Map",  "kotlin.collections.Map",  "kotlin.collections.MutableMap"  -> "Ljava/util/Map;"
            "java.util.Set",  "kotlin.collections.Set",  "kotlin.collections.MutableSet"  -> "Ljava/util/Set;"
            "java.util.Collection", "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "Ljava/util/Collection;"
            "java.lang.Iterable", "kotlin.collections.Iterable" -> "Ljava/lang/Iterable;"
            else -> {
                if (decl is KSClassDeclaration && decl.classKind == ClassKind.ENUM_CLASS)
                    "L${fqn.replace('.', '/')};"
                else
                    "L${fqn.replace('.', '/')};"
            }
        }
    }
}
