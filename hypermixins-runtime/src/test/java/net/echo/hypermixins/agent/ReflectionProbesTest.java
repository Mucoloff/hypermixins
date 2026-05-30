package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReflectionProbesTest {

    @Test
    void dropFirstArgStripsLeadingObjectSelf() {
        assertEquals("(II)V", ReflectionProbes.dropFirstArg("(Ljava/lang/Object;II)V"));
        assertEquals("()V",   ReflectionProbes.dropFirstArg("(Ljava/lang/Object;)V"));
    }

    @Test
    void dropFirstArgIsNoOpWhenNoLeadingArg() {
        assertEquals("()V", ReflectionProbes.dropFirstArg("()V"));
    }

    @Test
    void dropFirstArgStripsAnyFirstArgWhenPresent() {
        // The runtime variant doesn't pin the leading type to Object — it just drops
        // arg[0]. Mirror the contract the call sites rely on.
        assertEquals("()V", ReflectionProbes.dropFirstArg("(Ljava/lang/String;)V"));
        assertEquals("(I)V", ReflectionProbes.dropFirstArg("(JI)V"));
    }
}
