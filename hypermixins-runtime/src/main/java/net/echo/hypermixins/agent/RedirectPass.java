package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Call;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @Redirect:} swaps each matched INVOKE / field-access site with a static INVOKESTATIC call to
 * the handler. Validates that the captured call kind (Call enum) matches the actual opcode and
 * that the handler descriptor is consistent (receiver-as-first-arg for virtual / interface;
 * arg count and return type match).
 */
final class RedirectPass {

    private RedirectPass() {}

    static void apply(MethodNode method, Map<String, List<RedirectMapping>> redirectByDesc) {
        if (method.instructions == null) return;
        List<RedirectMapping> wildcards = redirectByDesc.entrySet().stream()
            .filter(e -> e.getKey().indexOf('*') >= 0 || e.getKey().startsWith("regex:"))
            .flatMap(e -> e.getValue().stream())
            .toList();
        Map<RedirectMapping, Integer> matchCount = new HashMap<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            String matchKey;
            if (insn instanceof MethodInsnNode mi) {
                matchKey = mi.owner + "." + mi.name + mi.desc;
            } else if (insn instanceof FieldInsnNode fi) {
                matchKey = fi.owner + "." + fi.name + ":" + fi.desc;
            } else continue;

            List<RedirectMapping> candidates = redirectByDesc.get(matchKey);
            if (candidates == null && wildcards.isEmpty()) continue;
            if (candidates == null) candidates = List.of();
            List<RedirectMapping> effective = candidates;
            if (!wildcards.isEmpty()) {
                List<RedirectMapping> all = new ArrayList<>(candidates);
                for (RedirectMapping w : wildcards) {
                    if (DescriptorMatcher.matches(w.invokeDesc(), matchKey)) all.add(w);
                }
                effective = all;
            }
            for (RedirectMapping redirect : effective) {
                if (!method.name.equals(redirect.targetMethod())) continue;
                int count = matchCount.getOrDefault(redirect, 0);
                matchCount.put(redirect, count + 1);
                if (count != redirect.index()) continue;
                if (insn instanceof MethodInsnNode mi) {
                    validateRedirect(redirect, mi);
                } else {
                    validateFieldRedirect(redirect, (FieldInsnNode) insn);
                }
                method.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(redirect.handler().getDeclaringClass()),
                    redirect.handler().getName(),
                    redirect.handlerDesc(), false));
                break;
            }
        }
    }

    private static void validateFieldRedirect(RedirectMapping redirect, FieldInsnNode fi) {
        Call expected = redirect.call();
        int op = fi.getOpcode();
        boolean ok = switch (expected) {
            case GETFIELD  -> op == Opcodes.GETFIELD;
            case PUTFIELD  -> op == Opcodes.PUTFIELD;
            case GETSTATIC -> op == Opcodes.GETSTATIC;
            case PUTSTATIC -> op == Opcodes.PUTSTATIC;
            default -> false;
        };
        if (!ok) mismatch(expected, op);
    }

    private static void validateRedirect(RedirectMapping redirect, MethodInsnNode mi) {
        Call expected = redirect.call();
        int opcode = mi.getOpcode();
        switch (expected) {
            case INVOKESTATIC -> { if (opcode != Opcodes.INVOKESTATIC) mismatch(expected, opcode); }
            case INVOKEVIRTUAL -> {
                if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) mismatch(expected, opcode);
            }
            case INVOKEINTERFACE -> { if (opcode != Opcodes.INVOKEINTERFACE) mismatch(expected, opcode); }
            case INVOKESPECIAL   -> { if (opcode != Opcodes.INVOKESPECIAL)   mismatch(expected, opcode); }
            case NEW ->
                throw new IllegalArgumentException("Call.NEW is an allocation opcode; not yet supported");
            case GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC ->
                throw new IllegalArgumentException(
                    "Call." + expected + " applied to a non-field instruction at " + mi.owner + "." + mi.name);
        }
        Type[] origArgs = Type.getArgumentTypes(mi.desc);
        Type[] handlerArgs = Type.getArgumentTypes(redirect.handlerDesc());
        int expectedCount = (expected == Call.INVOKESTATIC || expected == Call.INVOKESPECIAL)
            ? origArgs.length : origArgs.length + 1;
        if (handlerArgs.length != expectedCount)
            throw new IllegalArgumentException("Argument count mismatch in redirect: " + redirect.handler());
        if (!Type.getReturnType(mi.desc).equals(Type.getReturnType(redirect.handlerDesc())))
            throw new IllegalArgumentException("Return type mismatch in redirect: " + redirect.handler());
    }

    private static void mismatch(Call expected, int actual) {
        throw new IllegalArgumentException("Opcode mismatch: expected " + expected + " but found " + actual);
    }
}
