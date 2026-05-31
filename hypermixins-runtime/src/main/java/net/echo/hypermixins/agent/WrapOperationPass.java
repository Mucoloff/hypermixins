package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces each matched INVOKE site with: build an {@code Operation<R>} lambda whose body
 * dispatches the wrapped operation through a per-site static adapter, then INVOKESTATIC the
 * handler with (originalArgs..., Operation). The handler's return type matches the wrapped
 * site's return type so the rest of the body stays stack-consistent. FIELD sites are not
 * supported in this iteration (synthetic-adapter codegen for PUT/GETFIELD is materially
 * different from INVOKE — deferred).
 */
final class WrapOperationPass {

    private WrapOperationPass() {}

    static void apply(
        ClassNode owner, MethodNode method,
        Map<String, List<MixinDescriptor.WrapOperationEntry>> entriesByDesc,
        Class<?> mixinClass,
        Set<String> generatedAdapters,
        List<MethodNode> extraMethods
    ) {
        if (method.instructions == null || entriesByDesc.isEmpty()) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        List<MixinDescriptor.WrapOperationEntry> wildcards = entriesByDesc.entrySet().stream()
            .filter(e -> e.getKey().indexOf('*') >= 0 || e.getKey().startsWith("regex:"))
            .flatMap(e -> e.getValue().stream())
            .toList();
        Map<MixinDescriptor.WrapOperationEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            snapshot.add(insn);
        }
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String key = mi.owner + "." + mi.name + mi.desc;
            List<MixinDescriptor.WrapOperationEntry> direct = entriesByDesc.get(key);
            if (direct == null && wildcards.isEmpty()) continue;
            List<MixinDescriptor.WrapOperationEntry> effective = direct != null ? direct : List.of();
            if (!wildcards.isEmpty()) {
                List<MixinDescriptor.WrapOperationEntry> all = new ArrayList<>(effective);
                for (MixinDescriptor.WrapOperationEntry w : wildcards) {
                    if (DescriptorMatcher.matches(w.invokeDesc(), key)) all.add(w);
                }
                effective = all;
            }
            for (MixinDescriptor.WrapOperationEntry wo : effective) {
                if (!method.name.equals(wo.targetMethod())) continue;
                int count = matchCount.getOrDefault(wo, 0);
                matchCount.put(wo, count + 1);
                if (wo.index() > 0 && count + 1 != wo.index()) continue;
                String adapterName = ensureAdapter(owner, mi, generatedAdapters, extraMethods);
                wrap(method, mi, wo, mixinInternal, owner.name, adapterName);
                break;
            }
        }
    }

    private static String ensureAdapter(ClassNode owner, MethodInsnNode mi,
                                        Set<String> generated, List<MethodNode> extraMethods) {
        String key = mi.getOpcode() + "|" + mi.owner + "." + mi.name + mi.desc;
        String adapterName = "__wrapAdapter$" + NameHash.hashHex(key);
        if (!generated.add(adapterName)) return adapterName;

        MethodNode adapter = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            adapterName, LambdaAdapters.SAM_DESC, null, null);
        InsnList body = new InsnList();

        boolean hasReceiver = mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                           || mi.getOpcode() == Opcodes.INVOKEINTERFACE
                           || mi.getOpcode() == Opcodes.INVOKESPECIAL;
        Type[] callArgs = Type.getArgumentTypes(mi.desc);
        Type returnType = Type.getReturnType(mi.desc);

        int idx = 0;
        if (hasReceiver) {
            LambdaAdapters.loadAndUnbox(body, 0, idx++, Type.getObjectType(mi.owner));
        }
        for (Type at : callArgs) {
            LambdaAdapters.loadAndUnbox(body, 0, idx++, at);
        }
        body.add(new MethodInsnNode(mi.getOpcode(), mi.owner, mi.name, mi.desc, mi.itf));

        if (returnType.getSort() == Type.VOID) {
            body.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            Bytecode.emitBox(body, returnType);
        }
        body.add(new InsnNode(Opcodes.ARETURN));
        adapter.instructions = body;
        adapter.maxLocals = 1; // Object[] args at slot 0.
        adapter.maxStack = Math.max(2, callArgs.length + (hasReceiver ? 2 : 1));
        extraMethods.add(adapter);
        return adapterName;
    }

    private static void wrap(MethodNode method, MethodInsnNode site, MixinDescriptor.WrapOperationEntry wo,
                             String mixinInternal, String adapterOwner, String adapterName) {
        boolean hasReceiver = site.getOpcode() == Opcodes.INVOKEVIRTUAL
                           || site.getOpcode() == Opcodes.INVOKEINTERFACE
                           || site.getOpcode() == Opcodes.INVOKESPECIAL;
        Type[] callArgs = Type.getArgumentTypes(site.desc);
        Type returnType = Type.getReturnType(site.desc);

        Type[] effectiveArgs;
        if (hasReceiver) {
            effectiveArgs = new Type[callArgs.length + 1];
            effectiveArgs[0] = Type.getObjectType(site.owner);
            System.arraycopy(callArgs, 0, effectiveArgs, 1, callArgs.length);
        } else {
            effectiveArgs = callArgs;
        }

        int[] argLocals = new int[effectiveArgs.length];
        int slotCursor = method.maxLocals;
        for (int i = 0; i < effectiveArgs.length; i++) {
            argLocals[i] = slotCursor;
            slotCursor += effectiveArgs[i].getSize();
        }
        method.maxLocals = slotCursor;

        InsnList block = new InsnList();
        // 1. Stash original stack args.
        LambdaAdapters.stashArgs(block, effectiveArgs, argLocals);
        // 2. Reload original args for the handler call.
        LambdaAdapters.reloadArgs(block, effectiveArgs, argLocals);
        // 3. Build Operation<R> via INVOKEDYNAMIC.
        block.add(LambdaAdapters.buildOperationLambda(adapterOwner, adapterName));
        // 4. INVOKESTATIC handler — its return replaces the original INVOKE's value.
        block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
            wo.handlerName(), wo.handlerDesc(), false));
        // 5. Remove the original INVOKE.
        method.instructions.insertBefore(site, block);
        method.instructions.remove(site);
    }
}
