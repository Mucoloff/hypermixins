package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrivateShadowAccessorPassTest {

    @Test
    void accessorNameComposesPrefixTargetAndDigest() {
        String name = PrivateShadowAccessorPass.accessorName("doStuff", "(I)V");
        assertEquals("__access$doStuff$" + NameHash.hashHex("(I)V"), name);
    }

    @Test
    void dropFirstArgFromDescriptorRemovesArgZero() {
        assertEquals("(I)V", PrivateShadowAccessorPass.dropFirstArgFromDescriptor("(Ljava/lang/Object;I)V"));
        assertEquals("()V",  PrivateShadowAccessorPass.dropFirstArgFromDescriptor("(Ljava/lang/Object;)V"));
        // No args → identity.
        assertEquals("()V",  PrivateShadowAccessorPass.dropFirstArgFromDescriptor("()V"));
    }

    @Test
    void dropFirstArgPreservesReturnType() {
        assertEquals("()Ljava/lang/String;",
            PrivateShadowAccessorPass.dropFirstArgFromDescriptor("(Ljava/lang/Object;)Ljava/lang/String;"));
    }
}
