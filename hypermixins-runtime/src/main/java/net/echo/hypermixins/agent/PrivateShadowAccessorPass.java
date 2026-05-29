package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Synthesises {@code public synthetic returnType __access$name$hash(args)} trampolines that
 * INVOKESPECIAL the private target. Lets @Shadow / @Invoker / private-target injects reach the
 * private member without bypassing JVM access checks.
 */
final class PrivateShadowAccessorPass {

    private PrivateShadowAccessorPass() {}

    static String accessorName(String targetName, String targetDesc) {
        return "__access$" + targetName + "$" + NameHash.hashHex(targetDesc);
    }

    static String dropFirstArgFromDescriptor(String desc) {
        Type[] all = Type.getArgumentTypes(desc);
        if (all.length == 0) return desc;
        Type ret = Type.getReturnType(desc);
        Type[] rest = Arrays.copyOfRange(all, 1, all.length);
        return Type.getMethodDescriptor(ret, rest);
    }

    static void add(
        ClassNode node, String targetName, String targetDesc,
        List<MethodNode> extraMethods, Set<String> addedKeys
    ) {
        String accessor = accessorName(targetName, targetDesc);
        String key = accessor + targetDesc;
        if (!addedKeys.add(key)) return;
        for (MethodNode existing : node.methods) {
            if (existing.name.equals(accessor) && existing.desc.equals(targetDesc)) return;
        }
        MethodNode acc = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            accessor, targetDesc, null, null);
        InsnList ins = new InsnList();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        Type[] argTypes = Type.getArgumentTypes(targetDesc);
        int slot = 1;
        for (Type a : argTypes) {
            ins.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
            slot += a.getSize();
        }
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, targetName, targetDesc, false));
        Type ret = Type.getReturnType(targetDesc);
        ins.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        acc.instructions.add(ins);
        extraMethods.add(acc);
    }
}
