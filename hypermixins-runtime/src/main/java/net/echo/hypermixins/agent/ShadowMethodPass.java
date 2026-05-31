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
import java.util.Set;

/**
 * Rewrites @Shadow-annotated mixin methods into trampolines that INVOKEVIRTUAL the target
 * method directly. For private targets, routes the call through the public accessor synthesised
 * by {@link PrivateShadowAccessorPass}.
 */
final class ShadowMethodPass {

    private ShadowMethodPass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        Map<String, String> shadowsByHandlerKey = new HashMap<>();
        for (MixinDescriptor.ShadowEntry s : mapping.descriptor().shadows()) {
            shadowsByHandlerKey.put(s.handlerName() + s.handlerDesc(), s.targetName());
        }
        if (shadowsByHandlerKey.isEmpty()) return;
        String mappedTarget = mapping.getTargetClass().replace('.', '/');
        Set<String> softHandlerKeys = SoftBinding.collectSoftShadowMethodKeys(mapping.getMixinClass());
        Class<?> targetCls = softHandlerKeys.isEmpty() ? null : SoftBinding.tryLoadTarget(mapping);
        for (MethodNode method : node.methods) {
            String key = method.name + method.desc;
            String targetName = shadowsByHandlerKey.get(key);
            if (targetName == null) continue;

            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length == 0) {
                throw new IllegalStateException(
                    "@Shadow method must declare Object self as first parameter: " + method.name + method.desc);
            }
            Type returnType = Type.getReturnType(method.desc);
            Type[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
            String targetDesc = Type.getMethodDescriptor(returnType, targetArgs);

            if ((method.access & Opcodes.ACC_NATIVE) != 0) method.access &= ~Opcodes.ACC_NATIVE;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;

            boolean soft = softHandlerKeys.contains(key);
            if (soft && !SoftBinding.targetMethodExists(targetCls, targetName, targetDesc)) {
                method.instructions.add(SoftBinding.uoeBody(
                    "soft @Shadow target absent: " + mappedTarget + "." + targetName + targetDesc,
                    returnType));
                continue;
            }

            InsnList insns = new InsnList();
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedTarget));
            int slot = 2;
            for (Type arg : targetArgs) {
                insns.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
                slot += arg.getSize();
            }
            String invokedName = mapping.descriptor().isPrivateShadowTarget(targetName, targetDesc)
                ? PrivateShadowAccessorPass.accessorName(targetName, targetDesc)
                : targetName;
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mappedTarget, invokedName, targetDesc, false));
            insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            method.instructions.add(insns);
        }
    }
}
