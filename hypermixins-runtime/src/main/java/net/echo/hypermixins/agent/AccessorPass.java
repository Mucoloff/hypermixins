package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites every {@code native} {@code @Accessor}-annotated method on the mixin into a
 * direct {@code GETFIELD} (kind = GET) or {@code PUTFIELD} (kind = SET) on the target field
 * via the {@code Object self} parameter.
 */
final class AccessorPass {

    private AccessorPass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        if (mapping.descriptor().accessors().isEmpty()) return;
        String mappedTarget = mapping.getTargetClass().replace('.', '/');
        Map<String, MixinDescriptor.AccessorEntry> accessorsByKey = new HashMap<>();
        for (MixinDescriptor.AccessorEntry a : mapping.descriptor().accessors()) {
            accessorsByKey.put(a.handlerName() + a.handlerDesc(), a);
        }
        for (MethodNode method : node.methods) {
            MixinDescriptor.AccessorEntry acc = accessorsByKey.get(method.name + method.desc);
            if (acc == null) continue;
            if ((method.access & Opcodes.ACC_NATIVE) != 0) method.access &= ~Opcodes.ACC_NATIVE;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            Type returnType = Type.getReturnType(method.desc);
            Type[] args = Type.getArgumentTypes(method.desc);
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedTarget));
            if (acc.kind().equals("GET")) {
                ins.add(new FieldInsnNode(Opcodes.GETFIELD, mappedTarget, acc.targetField(), returnType.getDescriptor()));
                ins.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            } else {
                Type valueType = args[1];
                ins.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), 2));
                ins.add(new FieldInsnNode(Opcodes.PUTFIELD, mappedTarget, acc.targetField(), valueType.getDescriptor()));
                ins.add(new InsnNode(Opcodes.RETURN));
            }
            method.instructions.add(ins);
        }
    }
}
