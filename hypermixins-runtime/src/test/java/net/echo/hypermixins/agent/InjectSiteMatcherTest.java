package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InjectSiteMatcherTest {

    private static InjectMapping mockInject(At.Point point, String atDesc) throws NoSuchMethodException {
        Method handler = InjectSiteMatcherTest.class.getDeclaredMethod("dummyHandler", Object.class);
        return new InjectMapping("ignored", point, 0, atDesc, false, false, handler);
    }

    @SuppressWarnings("unused")
    private static void dummyHandler(Object self) {}

    @Test
    void invokeMatcherUsesOwnerNameDesc() throws Exception {
        InjectMapping ij = mockInject(At.Point.INVOKE, "java/lang/Integer.parseInt(Ljava/lang/String;)I");
        MethodInsnNode mi = new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        assertTrue(InjectSiteMatcher.matchesInvoke(mi, ij));
        // Wrong owner.
        MethodInsnNode wrong = new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Long", "parseInt", "(Ljava/lang/String;)I", false);
        assertFalse(InjectSiteMatcher.matchesInvoke(wrong, ij));
    }

    @Test
    void fieldMatcherUsesOwnerNameDesc() throws Exception {
        InjectMapping ij = mockInject(At.Point.FIELD, "Owner.foo:I");
        FieldInsnNode fi = new FieldInsnNode(Opcodes.GETFIELD, "Owner", "foo", "I");
        assertTrue(InjectSiteMatcher.matchesField(fi, ij));
        FieldInsnNode wrong = new FieldInsnNode(Opcodes.GETFIELD, "Owner", "bar", "I");
        assertFalse(InjectSiteMatcher.matchesField(wrong, ij));
    }

    @Test
    void constantMatcherDecodesTypeValuePair() throws Exception {
        InjectMapping ij = mockInject(At.Point.CONSTANT, "I:42");
        assertTrue(InjectSiteMatcher.matchesConstant(new LdcInsnNode(42), ij));
        assertFalse(InjectSiteMatcher.matchesConstant(new LdcInsnNode(43), ij));

        InjectMapping str = mockInject(At.Point.CONSTANT, "Ljava/lang/String;:hello");
        assertTrue(InjectSiteMatcher.matchesConstant(new LdcInsnNode("hello"), str));
        assertFalse(InjectSiteMatcher.matchesConstant(new LdcInsnNode("world"), str));
    }

    @Test
    void newMatcherChecksAllocatedInternalName() throws Exception {
        InjectMapping ij = mockInject(At.Point.NEW, "java/util/HashMap");
        TypeInsnNode tn = new TypeInsnNode(Opcodes.NEW, "java/util/HashMap");
        assertTrue(InjectSiteMatcher.matchesNew(tn, ij));
        TypeInsnNode wrong = new TypeInsnNode(Opcodes.NEW, "java/util/TreeMap");
        assertFalse(InjectSiteMatcher.matchesNew(wrong, ij));
        // CHECKCAST must not match.
        TypeInsnNode notNew = new TypeInsnNode(Opcodes.CHECKCAST, "java/util/HashMap");
        assertFalse(InjectSiteMatcher.matchesNew(notNew, ij));
    }

    @Test
    void conditionalJumpAcceptsAllBranchesButGotoAndJsr() {
        for (int op : List.of(Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                               Opcodes.IFGT, Opcodes.IFLE, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                               Opcodes.IFNULL, Opcodes.IFNONNULL)) {
            assertTrue(InjectSiteMatcher.isConditionalJump(new JumpInsnNode(op, new LabelNode())));
        }
        assertFalse(InjectSiteMatcher.isConditionalJump(new JumpInsnNode(Opcodes.GOTO, new LabelNode())));
        assertFalse(InjectSiteMatcher.isConditionalJump(new InsnNode(Opcodes.ICONST_0)));
    }
}
