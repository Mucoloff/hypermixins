package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Inserts {@code this.__mixin$X = new MixinType()} right after the {@code super()} call so each
 * instance carries the mixin singleton. Skips {@code this(...)} delegating constructors — the
 * forwarded ctor handles the assignment.
 */
final class ConstructorPatch {

    private ConstructorPatch() {}

    static void apply(MethodNode ctor, ClassNode owner, MixinMapping mapping, String mixinFieldName) {
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKESPECIAL
                && mi.name.equals("<init>") && mi.owner.equals(owner.name)) return;
        }

        AbstractInsnNode superCall = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKESPECIAL
                && mi.name.equals("<init>") && mi.owner.equals(owner.superName)) {
                superCall = insn; break;
            }
        }
        if (superCall == null) throw new IllegalStateException("No super() in constructor of " + owner.name);

        String mixinInternal = Type.getInternalName(mapping.getMixinClass());
        String mixinDesc     = Type.getDescriptor(mapping.getMixinClass());
        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new TypeInsnNode(Opcodes.NEW, mixinInternal));
        inject.add(new InsnNode(Opcodes.DUP));
        inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixinInternal, "<init>", "()V", false));
        inject.add(new FieldInsnNode(Opcodes.PUTFIELD, owner.name, mixinFieldName, mixinDesc));
        ctor.instructions.insert(superCall, inject);
    }
}
