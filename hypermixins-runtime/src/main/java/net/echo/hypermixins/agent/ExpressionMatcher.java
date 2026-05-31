package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Definition;
import net.echo.hypermixins.annotations.Definitions;
import net.echo.hypermixins.annotations.Expression;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compiled form of a handler's {@code @Definition} + {@code @Expression} pair. Built once per
 * mapping inside {@link InjectPass} and reused for every candidate target instruction. Resolves
 * the {@code @Definition.id()} referenced by the AST root to its target method / field
 * signature and compares against the incoming {@link AbstractInsnNode}.
 */
final class ExpressionMatcher {

    private final ExpressionNode root;
    private final Map<String, Definition> defsById;

    private ExpressionMatcher(ExpressionNode root, Map<String, Definition> defsById) {
        this.root = root;
        this.defsById = defsById;
    }

    static ExpressionMatcher compile(Method handler) {
        Expression expr = handler.getAnnotation(Expression.class);
        if (expr == null) {
            throw new IllegalStateException(
                "@At(point = EXPRESSION) requires @Expression on " + handler);
        }
        Map<String, Definition> defs = new HashMap<>();
        for (Definition d : collectDefinitions(handler)) {
            if (d.id().isEmpty()) {
                throw new IllegalStateException("@Definition.id() must be non-empty on " + handler);
            }
            if (d.method().isEmpty() == d.field().isEmpty()) {
                throw new IllegalStateException(
                    "@Definition id='" + d.id() + "' on " + handler
                    + " must set exactly one of method() / field()");
            }
            if (defs.put(d.id(), d) != null) {
                throw new IllegalStateException(
                    "Duplicate @Definition id='" + d.id() + "' on " + handler);
            }
        }
        ExpressionNode root = ExpressionParser.parse(expr.value());
        validate(root, defs, handler);
        return new ExpressionMatcher(root, defs);
    }

    boolean matches(AbstractInsnNode insn) {
        return switch (root) {
            case ExpressionNode.Call c -> matchesCall(insn, c);
            case ExpressionNode.FieldRef f -> matchesFieldRef(insn, f);
        };
    }

    private boolean matchesCall(AbstractInsnNode insn, ExpressionNode.Call call) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        Definition d = Objects.requireNonNull(defsById.get(call.defId()));
        String actual = mi.owner + "." + mi.name + mi.desc;
        if (!DescriptorMatcher.matches(d.method(), actual)) return false;
        int paramCount = countParams(mi.desc);
        return paramCount == call.args().size();
    }

    private boolean matchesFieldRef(AbstractInsnNode insn, ExpressionNode.FieldRef ref) {
        if (!(insn instanceof FieldInsnNode fi)) return false;
        Definition d = Objects.requireNonNull(defsById.get(ref.defId()));
        return DescriptorMatcher.matches(d.field(), fi.owner + "." + fi.name + ":" + fi.desc);
    }

    private static void validate(ExpressionNode root, Map<String, Definition> defs, Method handler) {
        String id = switch (root) {
            case ExpressionNode.Call c -> c.defId();
            case ExpressionNode.FieldRef f -> f.defId();
        };
        Definition d = defs.get(id);
        if (d == null) {
            throw new IllegalStateException(
                "@Expression references undefined id '" + id + "' on " + handler);
        }
        if (root instanceof ExpressionNode.Call && d.method().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a call but its @Definition sets field(), not method() on " + handler);
        }
        if (root instanceof ExpressionNode.FieldRef && d.field().isEmpty()) {
            throw new IllegalStateException(
                "@Expression uses '" + id + "' as a field but its @Definition sets method(), not field() on " + handler);
        }
    }

    private static Definition[] collectDefinitions(Method handler) {
        Definition single = handler.getAnnotation(Definition.class);
        if (single != null) return new Definition[] { single };
        Definitions group = handler.getAnnotation(Definitions.class);
        if (group != null) return group.value();
        return new Definition[0];
    }

    private static int countParams(String methodDesc) {
        int open = methodDesc.indexOf('(');
        int close = methodDesc.indexOf(')');
        if (open < 0 || close < 0 || close <= open) return 0;
        String args = methodDesc.substring(open + 1, close);
        int count = 0;
        int i = 0;
        while (i < args.length()) {
            char c = args.charAt(i);
            if (c == 'L') {
                int end = args.indexOf(';', i);
                if (end < 0) return count;
                i = end + 1;
            } else if (c == '[') {
                i++;
                continue;
            } else {
                i++;
            }
            count++;
        }
        return count;
    }
}
