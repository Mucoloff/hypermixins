package net.echo.hypermixins.agent;

import net.echo.hypermixins.registry.MixinRegistry;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Rewrites a target method body to {@code INVOKEDYNAMIC dispatch}, and produces:
 * <ul>
 *   <li>{@code __original$name$hash} — the unmodified original body (pre-mixin), preserved
 *       so {@code @Original} trampolines can call it.</li>
 *   <li>{@code __dispatch$name$hash} — the mixin-calling body that pulls the singleton out of
 *       the per-target field and invokes the handler.</li>
 * </ul>
 * Registers the call-site key with {@link MixinRegistry#registerPending} so the bootstrap
 * resolves it lazily on first invocation.
 */
final class OverwritePass {

    static final String REGISTRY_INTERNAL = "net/echo/hypermixins/registry/MixinRegistry";
    static final String BOOTSTRAP_DESCRIPTOR =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;";

    private OverwritePass() {}

    static MethodNode[] apply(
        ClassNode owner, MethodNode target, Method mixinMethod, String mixinFieldName,
        String mangledOriginalName, String dispName, boolean targetIsStatic, MixinMapping mapping,
        List<String> registeredKeysOut
    ) {
        MethodNode originalCopy = cloneAsOriginal(target, mangledOriginalName);

        int synthAcc = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (targetIsStatic ? Opcodes.ACC_STATIC : 0);
        MethodNode dispatchCopy = new MethodNode(
            synthAcc, dispName, target.desc, target.signature,
            target.exceptions == null ? null : target.exceptions.toArray(new String[0])
        );
        {
            InsnList di = new InsnList();
            String mixinDescStr = Type.getDescriptor(mixinMethod.getDeclaringClass());
            if (targetIsStatic) {
                di.add(new FieldInsnNode(Opcodes.GETSTATIC, owner.name,
                    StaticMixinField.name(mapping), mixinDescStr));
                di.add(new InsnNode(Opcodes.ACONST_NULL));
                Type[] targetArgs = Type.getArgumentTypes(target.desc);
                int slot = 0;
                for (Type t : targetArgs) { di.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot)); slot += t.getSize(); }
            } else {
                di.add(new VarInsnNode(Opcodes.ALOAD, 0));
                di.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, mixinFieldName, mixinDescStr));
                di.add(new VarInsnNode(Opcodes.ALOAD, 0));
                Type[] targetArgs = Type.getArgumentTypes(target.desc);
                int slot = 1;
                for (Type t : targetArgs) { di.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot)); slot += t.getSize(); }
            }
            di.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(mixinMethod.getDeclaringClass()),
                mixinMethod.getName(), Type.getMethodDescriptor(mixinMethod), false));
            Type ret = Type.getReturnType(target.desc);
            di.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            dispatchCopy.instructions.add(di);
        }

        String key = owner.name + "#" + target.name + target.desc;
        MixinRegistry.registerPending(key, originalCopy.name, dispName);
        registeredKeysOut.add(key);

        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;

        String callSiteDesc = targetIsStatic
            ? target.desc
            : "(L" + owner.name + ";" + target.desc.substring(1);

        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, REGISTRY_INTERNAL,
            "bootstrap", BOOTSTRAP_DESCRIPTOR, false);

        InsnList body = new InsnList();
        Type[] args = Type.getArgumentTypes(target.desc);
        int slot;
        if (targetIsStatic) {
            slot = 0;
        } else {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            slot = 1;
        }
        for (Type t : args) { body.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot)); slot += t.getSize(); }
        body.add(new InvokeDynamicInsnNode("dispatch", callSiteDesc, bsm, key));
        body.add(new InsnNode(Type.getReturnType(target.desc).getOpcode(Opcodes.IRETURN)));
        target.instructions.add(body);

        return new MethodNode[]{originalCopy, dispatchCopy};
    }

    private static MethodNode cloneAsOriginal(MethodNode original, String mangledName) {
        int acc = (original.access & Opcodes.ACC_STATIC) != 0
            ? (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)
            : (Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC);
        MethodNode copy = new MethodNode(acc, mangledName, original.desc,
            original.signature, original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        original.accept(copy);
        return copy;
    }
}
