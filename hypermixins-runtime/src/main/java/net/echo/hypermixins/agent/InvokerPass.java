package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites every {@code native} {@code @Invoker} method on the mixin into a direct
 * {@code INVOKEVIRTUAL} on the target. Private-target invokers route through the synthetic
 * {@code __access$} accessor generated upstream by {@link MixinTransformer}.
 */
final class InvokerPass {

    private InvokerPass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        if (mapping.descriptor().invokers().isEmpty()) return;
        String mappedTarget = mapping.getTargetClass().replace('.', '/');
        Map<String, MixinDescriptor.InvokerEntry> invokersByKey = new HashMap<>();
        for (MixinDescriptor.InvokerEntry iv : mapping.descriptor().invokers()) {
            invokersByKey.put(iv.handlerName() + iv.handlerDesc(), iv);
        }
        for (MethodNode method : node.methods) {
            MixinDescriptor.InvokerEntry iv = invokersByKey.get(method.name + method.desc);
            if (iv == null) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            Type returnType = Type.getReturnType(method.desc);
            Type[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
            String targetDesc = Type.getMethodDescriptor(returnType, targetArgs);

            if ((method.access & Opcodes.ACC_NATIVE) != 0) method.access &= ~Opcodes.ACC_NATIVE;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;

            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedTarget));
            int slot = 2;
            for (Type a : targetArgs) {
                ins.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
                slot += a.getSize();
            }
            String invokedName = mapping.descriptor().isPrivateShadowTarget(iv.targetName(), targetDesc)
                ? MixinTransformer.privateShadowAccessorName(iv.targetName(), targetDesc)
                : iv.targetName();
            ins.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mappedTarget, invokedName, targetDesc, false));
            ins.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            method.instructions.add(ins);
        }
    }
}
