package net.echo.hypermixins.agent;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Helpers for building {@code Operation<T>} lambdas via INVOKEDYNAMIC + LambdaMetafactory and
 * for emitting the synthetic adapter methods both {@link WrapOperationPass} and
 * {@link WrapMethodPass} plant on the target class. The adapter signature is uniform:
 * {@code public static synthetic Object adapt(Object[] args)} — unboxes/casts the args to
 * the original op shape, runs the op, boxes the result, returns it. The lambda binds
 * Operation.call({@code Object[]}) directly to that adapter via H_INVOKESTATIC.
 */
final class LambdaAdapters {

    static final String OPERATION_INTERNAL = "net/echo/hypermixins/annotations/Operation";
    static final String OPERATION_DESC = "L" + OPERATION_INTERNAL + ";";
    static final String SAM_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    private static final Handle BSM = new Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
            + "Ljava/lang/invoke/CallSite;",
        false
    );

    private LambdaAdapters() {}

    /**
     * Emits {@code INVOKEDYNAMIC call ()LOperation;} where the lambda body delegates to
     * {@code adapterOwner.adapterName(Object[])Object}. No captures.
     */
    static InvokeDynamicInsnNode buildOperationLambda(String adapterOwner, String adapterName) {
        Type samType = Type.getMethodType(SAM_DESC);
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, adapterOwner, adapterName, SAM_DESC, false);
        return new InvokeDynamicInsnNode(
            "call",
            "()" + OPERATION_DESC,
            BSM,
            samType, impl, samType
        );
    }

    /**
     * Loads {@code args[index]} onto the stack and unboxes / CHECKCASTs to {@code target}.
     * Used by emitted adapter bodies to project Object[] params back to their original
     * concrete types before dispatching the wrapped operation.
     */
    static void loadAndUnbox(InsnList out, int arrayLocal, int index, Type target) {
        out.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        out.add(intConst(index));
        out.add(new InsnNode(Opcodes.AALOAD));
        Bytecode.unboxOrCast(out, target);
    }

    /**
     * Stashes the top {@code argTypes.length} stack values into the supplied temp locals in
     * reverse order so the original ordering is preserved on subsequent reloads.
     */
    static void stashArgs(InsnList out, Type[] argTypes, int[] argLocals) {
        for (int i = argTypes.length - 1; i >= 0; i--) {
            out.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }
    }

    /** Reloads stashed args onto the stack in original declaration order. */
    static void reloadArgs(InsnList out, Type[] argTypes, int[] argLocals) {
        for (int i = 0; i < argTypes.length; i++) {
            out.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
        }
    }

    private static AbstractInsnNode intConst(int n) {
        if (n >= -1 && n <= 5) return new InsnNode(Opcodes.ICONST_0 + n);
        if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, n);
        if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, n);
        return Bytecode.intConst(n);
    }

    /** Marks an explicit ATHROW on the unreachable branch — handy when adapters fall through. */
    static AbstractInsnNode unreachableAThrow() {
        return new TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError");
    }
}
