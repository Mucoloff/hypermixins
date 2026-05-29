package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Synthesises {@code private static final <MixinType> __mixin$static$<FQN>} on the target
 * class plus the {@code <clinit>} construction needed for dispatching to static targets.
 * Re-entrant: if a previous mixin's pass already added both the field and its initialising
 * PUTSTATIC, this is a no-op.
 */
final class StaticMixinField {

    private StaticMixinField() {}

    static String name(MixinMapping mapping) {
        return "__mixin$static$" + mapping.getMixinClass().getName().replace('.', '$');
    }

    static void ensure(ClassNode node, MixinMapping mapping) {
        String fieldName = name(mapping);
        String mixinDesc = Type.getDescriptor(mapping.getMixinClass());
        String mixinInternal = Type.getInternalName(mapping.getMixinClass());
        boolean hasField = node.fields.stream().anyMatch(f -> f.name.equals(fieldName));
        if (!hasField) {
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                fieldName, mixinDesc, null, null));
        }
        MethodNode clinit = null;
        for (MethodNode m : node.methods) {
            if (m.name.equals("<clinit>")) { clinit = m; break; }
        }
        boolean createdClinit = false;
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(clinit);
            createdClinit = true;
        }
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fi
                && fi.getOpcode() == Opcodes.PUTSTATIC
                && fi.owner.equals(node.name) && fi.name.equals(fieldName)) {
                return;
            }
        }
        InsnList init = new InsnList();
        init.add(new TypeInsnNode(Opcodes.NEW, mixinInternal));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixinInternal, "<init>", "()V", false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, fieldName, mixinDesc));
        if (createdClinit) {
            clinit.instructions.insertBefore(clinit.instructions.getFirst(), init);
        } else {
            AbstractInsnNode first = clinit.instructions.getFirst();
            if (first == null) clinit.instructions.add(init);
            else clinit.instructions.insertBefore(first, init);
        }
    }
}
