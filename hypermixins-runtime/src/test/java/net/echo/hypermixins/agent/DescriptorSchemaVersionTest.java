package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the processor↔runtime schema handshake: {@link DescriptorReader#validateSchemaVersion}
 * must reject a descriptor whose {@code schemaVersion()} differs from the runtime's
 * {@link MixinDescriptor#SCHEMA_VERSION}, and a descriptor missing the method entirely (emitted
 * by a pre-versioning processor).
 */
class DescriptorSchemaVersionTest {

    /** Stand-in $$Descriptor whose schemaVersion() is stale (2 vs current 3). */
    public static final class StaleStub {
        public static int schemaVersion() { return 2; }
    }

    /** Stand-in whose schemaVersion() matches the runtime. */
    public static final class CurrentStub {
        public static int schemaVersion() { return MixinDescriptor.SCHEMA_VERSION; }
    }

    /** Pre-versioning descriptor: no schemaVersion() method at all. */
    public static final class NoVersionStub {
    }

    @Test
    void rejectsStaleSchemaVersion() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DescriptorReader.validateSchemaVersion(
                MethodHandles.publicLookup(), StaleStub.class, DescriptorSchemaVersionTest.class));
        assertTrue(ex.getMessage().contains("schema version"),
            "message should explain the version mismatch: " + ex.getMessage());
    }

    @Test
    void rejectsMissingSchemaVersion() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DescriptorReader.validateSchemaVersion(
                MethodHandles.publicLookup(), NoVersionStub.class, DescriptorSchemaVersionTest.class));
        assertTrue(ex.getMessage().contains("older processor"),
            "message should flag the pre-versioning processor: " + ex.getMessage());
    }

    @Test
    void acceptsCurrentSchemaVersion() {
        assertDoesNotThrow(() -> DescriptorReader.validateSchemaVersion(
            MethodHandles.publicLookup(), CurrentStub.class, DescriptorSchemaVersionTest.class));
    }
}
