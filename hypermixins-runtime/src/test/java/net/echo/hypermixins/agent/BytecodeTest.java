package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeTest {

    @Test
    void intConstUsesShortestEncoding() {
        for (int n = -1; n <= 5; n++) {
            final int captured = n;
            AbstractInsnNode insn = Bytecode.intConst(captured);
            assertInstanceOf(InsnNode.class, insn, () -> "n=" + captured + " should be InsnNode");
            assertEquals(Opcodes.ICONST_0 + captured, insn.getOpcode());
        }
        // 6 spills out of ICONST_* into BIPUSH
        AbstractInsnNode six = Bytecode.intConst(6);
        assertInstanceOf(IntInsnNode.class, six);
        assertEquals(Opcodes.BIPUSH, six.getOpcode());
        assertEquals(6, ((IntInsnNode) six).operand);

        // 200 spills into SIPUSH
        AbstractInsnNode large = Bytecode.intConst(200);
        assertInstanceOf(IntInsnNode.class, large);
        assertEquals(Opcodes.SIPUSH, large.getOpcode());

        // 100_000 spills into LDC
        AbstractInsnNode huge = Bytecode.intConst(100_000);
        assertInstanceOf(LdcInsnNode.class, huge);
        assertEquals(100_000, ((LdcInsnNode) huge).cst);
    }

    @Test
    void isLoadOpcodeAcceptsEveryLoad() {
        assertTrue(Bytecode.isLoadOpcode(Opcodes.ILOAD));
        assertTrue(Bytecode.isLoadOpcode(Opcodes.LLOAD));
        assertTrue(Bytecode.isLoadOpcode(Opcodes.FLOAD));
        assertTrue(Bytecode.isLoadOpcode(Opcodes.DLOAD));
        assertTrue(Bytecode.isLoadOpcode(Opcodes.ALOAD));
    }

    @Test
    void isLoadOpcodeRejectsStoresAndIinc() {
        for (int op : new int[]{Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE,
                                 Opcodes.DSTORE, Opcodes.ASTORE, Opcodes.IINC, Opcodes.RET}) {
            assertEquals(false, Bytecode.isLoadOpcode(op),
                () -> "expected false for opcode " + op);
        }
    }
}
