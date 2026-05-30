package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifyConstantPassTest {

    @Test
    void intIconstShortForm() {
        assertTrue(ModifyConstantPass.matchesConstantLoad(new InsnNode(Opcodes.ICONST_3), "I", "3"));
        assertFalse(ModifyConstantPass.matchesConstantLoad(new InsnNode(Opcodes.ICONST_3), "I", "4"));
    }

    @Test
    void intBipushAndSipush() {
        assertTrue(ModifyConstantPass.matchesConstantLoad(new IntInsnNode(Opcodes.BIPUSH, 42), "I", "42"));
        assertTrue(ModifyConstantPass.matchesConstantLoad(new IntInsnNode(Opcodes.SIPUSH, 1234), "I", "1234"));
        assertFalse(ModifyConstantPass.matchesConstantLoad(new IntInsnNode(Opcodes.BIPUSH, 42), "I", "43"));
    }

    @Test
    void intLdc() {
        assertTrue(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode(100_000), "I", "100000"));
        assertFalse(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode(100_000L), "I", "100000"));
    }

    @Test
    void longLconstAndLdc() {
        assertTrue(ModifyConstantPass.matchesConstantLoad(new InsnNode(Opcodes.LCONST_0), "J", "0"));
        assertTrue(ModifyConstantPass.matchesConstantLoad(new InsnNode(Opcodes.LCONST_1), "J", "1"));
        assertTrue(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode(123_456_789_012L), "J", "123456789012"));
    }

    @Test
    void doubleAndFloatLdc() {
        assertTrue(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode(3.14f), "F", "3.14"));
        assertTrue(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode(2.718), "D", "2.718"));
    }

    @Test
    void stringLdc() {
        assertTrue(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode("hello"), "Ljava/lang/String;", "hello"));
        assertFalse(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode("hello"), "Ljava/lang/String;", "world"));
    }

    @Test
    void unsupportedTypeReturnsFalse() {
        assertFalse(ModifyConstantPass.matchesConstantLoad(new LdcInsnNode("x"), "B", "x"));
    }
}
