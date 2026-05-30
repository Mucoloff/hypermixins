package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NameHashTest {

    @Test
    void hashHexProducesSixteenLowercaseHexChars() {
        String hex = NameHash.hashHex("getPlayers()Ljava/util/List;");
        assertEquals(16, hex.length(),
            () -> "expected 16 chars, got " + hex.length() + " for " + hex);
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            assertEquals(true, (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'),
                () -> "non-hex char in " + hex);
        }
    }

    @Test
    void hashIsDeterministic() {
        assertEquals(NameHash.hashHex("foo()V"), NameHash.hashHex("foo()V"));
    }

    @Test
    void distinctDescriptorsHashToDistinctValues() {
        assertNotEquals(
            NameHash.hashHex("foo()V"),
            NameHash.hashHex("bar()V"));
    }

    /**
     * Lock the first-16-hex prefix of SHA-1("()Ljava/util/List;"). The
     * processor-side NameMangling.sha1Hex16 must produce the same string —
     * if either side drifts, $$Descriptor.syntheticNames stops matching the
     * synthetic methods OverwritePass generates at transform time. The input
     * is the bare method descriptor because both call sites hash the desc
     * alone (target name is concatenated unhashed).
     */
    @Test
    void hashMatchesProcessorPipelineForKnownDescriptor() {
        assertEquals("26fc8d8cbd4e22af", NameHash.hashHex("()Ljava/util/List;"));
    }
}
