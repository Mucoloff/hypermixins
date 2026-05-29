package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFrameAnalyzerTest {

    @Test
    void emptyLocalVariableTableYieldsEmptyMap() {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        LabelNode probe = new LabelNode();
        m.instructions.add(probe);
        // localVariables intentionally null — mirrors a target compiled without -g.
        LocalFrameAnalyzer a = new LocalFrameAnalyzer(m);
        assertTrue(a.liveLocalsAt(probe).isEmpty());
    }

    @Test
    void liveLocalsAtRespectsVariableTableRanges() {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "run", "(I)V", null, null);
        LabelNode start = new LabelNode(new Label());
        LabelNode mid   = new LabelNode(new Label());
        LabelNode end   = new LabelNode(new Label());
        m.instructions.add(start);
        m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));   // read param
        m.instructions.add(mid);
        m.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));  // mid local lives from here
        m.instructions.add(end);
        m.localVariables = new java.util.ArrayList<>();
        m.localVariables.add(new LocalVariableNode("seed", "I", null, start, end, 1));
        m.localVariables.add(new LocalVariableNode("mid",  "I", null, mid,   end, 2));

        LocalFrameAnalyzer a = new LocalFrameAnalyzer(m);
        Map<Integer, Type> atStart = a.liveLocalsAt(start);
        Map<Integer, Type> atMid   = a.liveLocalsAt(mid);

        assertEquals(Type.INT_TYPE, atStart.get(1));
        assertNull(atStart.get(2)); // mid not yet in scope
        assertEquals(Type.INT_TYPE, atMid.get(1));
        assertEquals(Type.INT_TYPE, atMid.get(2));
    }
}
