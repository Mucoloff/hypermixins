package net.echo.hypermixins.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Lock the processor-side digest to the same vector the runtime-side
 * NameHash test asserts. If either side drifts to a different algorithm
 * or slicing the $$Descriptor.syntheticNames table stops matching the
 * runtime-emitted synthetic methods.
 */
class NameManglingTest {

    @Test
    fun mangledOriginalIncludesKnownDigestPrefix() {
        assertEquals(
            "__original\$getPlayers\$26fc8d8cbd4e22af",
            NameMangling.mangledOriginalName("getPlayers", "()Ljava/util/List;"),
        )
    }

    @Test
    fun dispatchUsesSameDigest() {
        assertEquals(
            "__dispatch\$getPlayers\$26fc8d8cbd4e22af",
            NameMangling.dispatchName("getPlayers", "()Ljava/util/List;"),
        )
    }
}
