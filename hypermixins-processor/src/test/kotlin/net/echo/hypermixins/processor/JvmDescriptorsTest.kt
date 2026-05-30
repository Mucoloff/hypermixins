package net.echo.hypermixins.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmDescriptorsTest {

    @Test
    fun dropFirstArgDescStripsLeadingObjectSelf() {
        assertEquals(
            "(II)V",
            JvmDescriptors.dropFirstArgDesc("(Ljava/lang/Object;II)V"),
        )
        assertEquals(
            "()V",
            JvmDescriptors.dropFirstArgDesc("(Ljava/lang/Object;)V"),
        )
    }

    @Test
    fun dropFirstArgDescIsNoOpWhenNoObjectSelf() {
        assertEquals(
            "(I)V",
            JvmDescriptors.dropFirstArgDesc("(I)V"),
        )
        assertEquals(
            "()V",
            JvmDescriptors.dropFirstArgDesc("()V"),
        )
        // Don't strip a non-Object reference.
        assertEquals(
            "(Ljava/lang/String;)V",
            JvmDescriptors.dropFirstArgDesc("(Ljava/lang/String;)V"),
        )
    }
}
