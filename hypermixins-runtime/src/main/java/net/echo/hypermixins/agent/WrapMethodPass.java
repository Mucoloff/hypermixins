package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps an entire target method body. The original body is cloned into a sibling
 * {@code __wrappedOrig$<name>$<hash>} method on the target class, a static
 * {@code __wrapMethodAdapter$<hash>(Object[] args)Object} adapter is emitted that dispatches
 * the saved body via the Object[] args contract, and the original method's body is rewritten
 * to: build {@code Operation<R>} via INVOKEDYNAMIC + adapter, push (self, originalArgs...,
 * Operation), {@code INVOKEVIRTUAL} the instance handler on the mixin class.
 *
 * <p>Conflicts with {@code @Overwrite} on the same target are rejected at transform.
 */
final class WrapMethodPass {

    private WrapMethodPass() {}

    static void apply(
        ClassNode node, MixinMapping mapping, Set<String> overwrittenKeys, List<MethodNode> extraMethods
    ) {
        List<MixinDescriptor.WrapMethodEntry> entries = mapping.descriptor().wrapMethods();
        if (entries.isEmpty()) return;

        // Bucket entries by target method name (descriptor isn't part of @WrapMethod, so a single
        // entry binds to every overload — most common case is one match).
        Map<String, MixinDescriptor.WrapMethodEntry> byName = new HashMap<>();
        for (MixinDescriptor.WrapMethodEntry wm : entries) {
            byName.put(wm.targetMethod(), wm);
        }

        String mixinInternal = Type.getInternalName(mapping.getMixinClass());
        String mixinDesc = Type.getDescriptor(mapping.getMixinClass());
        String mixinField = "__mixin$" + mapping.getMixinClass().getName().replace('.', '$');

        for (MethodNode method : new ArrayList<>(node.methods)) {
            if (method.name.equals("<init>") || method.name.equals("<clinit>")) continue;
            MixinDescriptor.WrapMethodEntry wm = byName.get(method.name);
            if (wm == null) continue;

            String key = node.name + "#" + method.name + method.desc;
            if (overwrittenKeys.contains(key)) {
                throw new IllegalStateException(
                    "@WrapMethod conflicts with @Overwrite on " + key + " — only one may wrap a given target");
            }
            overwrittenKeys.add(key);

            wrap(node, method, wm, mixinInternal, mixinDesc, mixinField, extraMethods);
        }
    }

    private static void wrap(
        ClassNode node, MethodNode method, MixinDescriptor.WrapMethodEntry wm,
        String mixinInternal, String mixinDesc, String mixinField, List<MethodNode> extraMethods
    ) {
        boolean isStaticTarget = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] targetArgs = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);

        String hash = NameHash.hashHex(method.name + method.desc);
        String savedName = "__wrappedOrig$" + method.name + "$" + hash;
        String adapterName = "__wrapMethodAdapter$" + hash;

        // 1. Clone the original body under savedName, keep instance/static access.
        MethodNode savedCopy = new MethodNode(
            (method.access & ~Opcodes.ACC_NATIVE) | Opcodes.ACC_SYNTHETIC,
            savedName, method.desc, method.signature,
            method.exceptions == null ? null : method.exceptions.toArray(new String[0]));
        method.accept(savedCopy);
        savedCopy.name = savedName;
        savedCopy.access = (method.access & ~Opcodes.ACC_NATIVE) | Opcodes.ACC_SYNTHETIC;
        extraMethods.add(savedCopy);

        // 2. Emit the static adapter that dispatches the saved body via Object[] args.
        extraMethods.add(buildAdapter(node.name, adapterName, savedName, method.desc, isStaticTarget, targetArgs, returnType));

        // 3. Rewrite the original method body.
        method.access &= ~Opcodes.ACC_NATIVE;
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;

        InsnList body = new InsnList();
        // Build Operation lambda.
        body.add(LambdaAdapters.buildOperationLambda(node.name, adapterName));
        int opLocal = computeMaxLocals(isStaticTarget, targetArgs);
        body.add(new VarInsnNode(Opcodes.ASTORE, opLocal));

        // Push mixin instance.
        if (isStaticTarget) {
            String staticField = "__mixin$static$" + mixinInternal.replace('/', '$');
            body.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, staticField, mixinDesc));
        } else {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, mixinField, mixinDesc));
        }

        // Push self (Object).
        if (isStaticTarget) {
            body.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }

        // Push original args.
        int slot = isStaticTarget ? 0 : 1;
        for (Type t : targetArgs) {
            body.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            slot += t.getSize();
        }

        // Push Operation.
        body.add(new VarInsnNode(Opcodes.ALOAD, opLocal));

        // INVOKEVIRTUAL handler.
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinInternal,
            wm.handlerName(), wm.handlerDesc(), false));

        body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

        method.instructions.add(body);
        method.maxLocals = opLocal + 1;
        method.maxStack = Math.max(method.maxStack, targetArgs.length + 4);
    }

    private static int computeMaxLocals(boolean isStaticTarget, Type[] targetArgs) {
        int s = isStaticTarget ? 0 : 1;
        for (Type t : targetArgs) s += t.getSize();
        return s;
    }

    private static MethodNode buildAdapter(
        String targetInternal, String adapterName, String savedName, String savedDesc,
        boolean isStaticTarget, Type[] targetArgs, Type returnType
    ) {
        MethodNode adapter = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            adapterName, LambdaAdapters.SAM_DESC, null, null);
        InsnList body = new InsnList();
        int idx = 0;
        if (!isStaticTarget) {
            LambdaAdapters.loadAndUnbox(body, 0, idx++, Type.getObjectType(targetInternal));
        }
        for (Type at : targetArgs) {
            LambdaAdapters.loadAndUnbox(body, 0, idx++, at);
        }
        body.add(new MethodInsnNode(
            isStaticTarget ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
            targetInternal, savedName, savedDesc, false));
        if (returnType.getSort() == Type.VOID) {
            body.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            Bytecode.emitBox(body, returnType);
        }
        body.add(new InsnNode(Opcodes.ARETURN));
        adapter.instructions = body;
        adapter.maxLocals = 1;
        adapter.maxStack = Math.max(2, targetArgs.length + (isStaticTarget ? 1 : 2));
        return adapter;
    }

}
