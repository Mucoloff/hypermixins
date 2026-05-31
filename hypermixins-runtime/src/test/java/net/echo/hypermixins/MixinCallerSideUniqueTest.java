package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinMapping;
import net.echo.hypermixins.agent.MixinTransformer;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Unique;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixinCallerSideUniqueTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static class CallerSideTarget {
        public int compute(int x) {
            return x;
        }
    }

    @Mixin("net.echo.hypermixins.MixinCallerSideUniqueTest$CallerSideTarget")
    public static class CallerSideMixin {

        @Unique
        public int triple(int v) {
            return v * 3;
        }

        @Original("compute")
        public native int computeOrig(Object self, int x);

        @Overwrite("compute")
        public int compute(Object self, int x) {
            int base = computeOrig(self, x);
            return triple(base);
        }
    }

    @Test
    void callerRewriteEmitsInvokeStatic() throws Exception {
        byte[] transformedMixin = transformMixinOnly(CallerSideMixin.class);
        ClassNode mixinNode = new ClassNode();
        new ClassReader(transformedMixin).accept(mixinNode, 0);
        MethodNode handler = findHandler(mixinNode);
        boolean foundStatic = false;
        for (AbstractInsnNode insn = handler.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            if (mi.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (!mi.name.startsWith("__unique$")) continue;
            assertEquals(CallerSideTarget.class.getName().replace('.', '/'), mi.owner);
            assertTrue(mi.desc.startsWith("(Ljava/lang/Object;"));
            foundStatic = true;
        }
        assertTrue(foundStatic, "expected INVOKESTATIC __unique$ in handler body after caller-side rewrite");

        for (AbstractInsnNode insn = handler.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if ("triple".equals(mi.name)) {
                throw new AssertionError("legacy INVOKEVIRTUAL triple still present — rewrite missed");
            }
        }
    }

    @Test
    void callerRewriteReturnsCorrectValue() throws Exception {
        Class<?> t = TransformerTestSupport.applyMixin(CallerSideTarget.class, CallerSideMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int out = (int) t.getMethod("compute", int.class).invoke(inst, 7);
        assertEquals(21, out);
    }

    private static byte[] transformMixinOnly(Class<?> mixinClass) throws Exception {
        MixinMapping mapping = new MixinMapping(mixinClass);
        MixinTransformer transformer = new MixinTransformer(List.of(mapping));
        String mixinInternal = mixinClass.getName().replace('.', '/');
        String resource = mixinInternal + ".class";
        byte[] original;
        try (InputStream is = mixinClass.getClassLoader().getResourceAsStream(resource)) {
            original = is.readAllBytes();
        }
        return transformer.transform(null, mixinClass.getClassLoader(), mixinInternal, null, null, original);
    }

    private static MethodNode findHandler(ClassNode node) {
        for (MethodNode m : node.methods) {
            if ("compute".equals(m.name) && m.desc.startsWith("(Ljava/lang/Object;I)")) return m;
        }
        throw new AssertionError("handler not found");
    }
}
