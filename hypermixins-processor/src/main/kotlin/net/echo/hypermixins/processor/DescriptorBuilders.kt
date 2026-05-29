package net.echo.hypermixins.processor

import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import javax.lang.model.element.Modifier

/**
 * Generates the {@code public static List<String[]> <name>()} accessors emitted on each
 * mixin's $$Descriptor class. Pure JavaPoet — no KSP state.
 */
internal fun entriesMethod(name: String, entries: List<Array<String>>): MethodSpec {
    val stringArrayType = ArrayTypeName.of(String::class.java)
    val listType = ParameterizedTypeName.get(ClassName.get(List::class.java), stringArrayType)
    val block = CodeBlock.builder().add("return \$T.<\$T[]>of(\n", List::class.java, String::class.java)
    entries.forEachIndexed { i, e ->
        block.add("    new String[]{${e.joinToString(", ") { "\$S" }}}", *e)
        if (i < entries.size - 1) block.add(",\n")
    }
    block.add("\n)")
    return MethodSpec.methodBuilder(name)
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .returns(listType)
        .addStatement(block.build())
        .build()
}
