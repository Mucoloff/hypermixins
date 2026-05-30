package net.echo.hypermixins.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescriptorBuildersTest {

    @Test
    fun emptyEntriesReturnsBareListOf() {
        val spec = entriesMethod("overwriteEntries", emptyList())
        val source = spec.toString()
        // Shape should be: public static List<String[]> overwriteEntries() { return List.<String[]>of(); }
        assertTrue("public static" in source) { "missing modifiers: $source" }
        assertTrue("overwriteEntries()" in source) { "missing method name: $source" }
        assertTrue("List.<java.lang.String[]>of(" in source) { "unexpected body: $source" }
    }

    @Test
    fun rowsBecomeStringArrayLiterals() {
        val spec = entriesMethod(
            "syntheticNames",
            listOf(arrayOf("foo", "()V", "__original\$foo\$hash", "__dispatch\$foo\$hash")),
        )
        val source = spec.toString()
        assertTrue("\"foo\"" in source) { source }
        assertTrue("\"()V\"" in source) { source }
        assertTrue("__original\$foo\$hash" in source) { source }
        assertTrue("__dispatch\$foo\$hash" in source) { source }
        assertEquals(1, source.count { it == ';' }) // one return statement
    }
}
