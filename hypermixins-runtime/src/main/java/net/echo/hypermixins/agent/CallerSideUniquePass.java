package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Unique;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Rewrites {@code INVOKEVIRTUAL <mixin>.<helper>} call sites in mixin handler bodies to
 * {@code INVOKESTATIC <target>.__unique$<mangled>} when {@code helper} is an instance
 * {@code @Unique} method merged onto the target by {@link UniquePass}.
 *
 * <p>Scope: only when the caller method has {@code Object self} addressable (instance handler
 * with first declared param {@code Object}, or static handler with same first param) AND the
 * call's receiver was produced by a clean {@code ALOAD 0}. Anything more exotic
 * (field-stored receivers, expression receivers) is left untouched.
 */
final class CallerSideUniquePass {

    private CallerSideUniquePass() {}

    static void apply(ClassNode mixinNode, MixinMapping mapping) {
        Class<?> mixinClass = mapping.getMixinClass();
        Set<String> instanceUniqueKeys = collectInstanceUniqueKeys(mixinClass);
        if (instanceUniqueKeys.isEmpty()) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        String targetInternal = mapping.getTargetClass().replace('.', '/');

        for (MethodNode m : mixinNode.methods) {
            if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
            if (isAnnotatedUnique(m)) continue;
            int selfSlot = resolveSelfSlot(m);
            if (selfSlot < 0) continue;
            rewriteBody(m, mixinInternal, targetInternal, mixinClass, instanceUniqueKeys, selfSlot);
        }
    }

    private static void rewriteBody(
        MethodNode m, String mixinInternal, String targetInternal, Class<?> mixinClass,
        Set<String> instanceUniqueKeys, int selfSlot
    ) {
        if (m.instructions == null) return;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!mixinInternal.equals(mi.owner)) continue;
            String key = mi.name + mi.desc;
            if (!instanceUniqueKeys.contains(key)) continue;

            int argCount = Type.getArgumentTypes(mi.desc).length;
            AbstractInsnNode producer = ExpressionStackWalker.findProducerAt(mi, argCount);
            if (!(producer instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != 0) {
                continue;
            }
            String newDesc = "(Ljava/lang/Object;" + mi.desc.substring(1);
            String mangled = UniquePass.mangledUniqueName(mixinClass, mi.name, newDesc);
            v.var = selfSlot;
            mi.setOpcode(Opcodes.INVOKESTATIC);
            mi.owner = targetInternal;
            mi.name = mangled;
            mi.desc = newDesc;
            mi.itf = false;
        }
    }

    /**
     * Returns {@code 1} for an instance method whose first declared param is {@code Object},
     * {@code 0} for a static method whose first declared param is {@code Object}, otherwise
     * {@code -1} (unrewriteable: no addressable target {@code self}).
     */
    private static int resolveSelfSlot(MethodNode m) {
        Type[] args = Type.getArgumentTypes(m.desc);
        if (args.length == 0) return -1;
        if (!"java/lang/Object".equals(args[0].getInternalName())) return -1;
        return (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
    }

    private static Set<String> collectInstanceUniqueKeys(Class<?> mixinClass) {
        Set<String> out = new HashSet<>();
        for (Method jm : mixinClass.getDeclaredMethods()) {
            if (jm.getAnnotation(Unique.class) == null) continue;
            if (java.lang.reflect.Modifier.isStatic(jm.getModifiers())) continue;
            out.add(jm.getName() + Type.getMethodDescriptor(jm));
        }
        return out;
    }

    private static boolean isAnnotatedUnique(MethodNode m) {
        if (m.visibleAnnotations == null) return false;
        String desc = "L" + Unique.class.getName().replace('.', '/') + ";";
        for (org.objectweb.asm.tree.AnnotationNode ann : m.visibleAnnotations) {
            if (desc.equals(ann.desc)) return true;
        }
        return false;
    }
}
