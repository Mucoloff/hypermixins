package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Generates the CallbackInfo / CallbackInfoReturnable allocation and the post-handler
 * isCancelled() short-circuit return for cancellable {@code @Inject}s. {@link #allocate}
 * returns the slot the caller must reload before invoking the handler;
 * {@link #emitCancelCheck} emits the comparison and the cancellation return.
 */
final class CallbackInfoEmitter {

    static final String CB_INFO_INTERNAL = "net/echo/hypermixins/annotations/CallbackInfo";
    static final String CB_RET_INTERNAL  = "net/echo/hypermixins/annotations/CallbackInfoReturnable";

    private CallbackInfoEmitter() {}

    /** Allocates the CB[Returnable], stores it in a fresh local, and returns that slot. */
    static int allocate(InsnList out, MethodNode target, InjectMapping inject) {
        int ciLocal = target.maxLocals;
        target.maxLocals += 1;
        out.add(new LdcInsnNode(inject.targetMethod()));
        if (inject.returnable()) {
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CB_RET_INTERNAL, "of",
                "(Ljava/lang/String;)L" + CB_RET_INTERNAL + ";", false));
        } else {
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CB_INFO_INTERNAL, "of",
                "(Ljava/lang/String;)L" + CB_INFO_INTERNAL + ";", false));
        }
        out.add(new VarInsnNode(Opcodes.ASTORE, ciLocal));
        return ciLocal;
    }

    /**
     * Emits {@code if (ci.isCancelled()) return ...;}. Returnable mode unboxes the override
     * value; non-returnable mode demands a void target and emits bare RETURN.
     */
    static void emitCancelCheck(
        InsnList out, MethodNode target, InjectMapping inject, Type targetReturn, int ciLocal
    ) {
        LabelNode notCancelled = new LabelNode();
        out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
        String cancelOwner = inject.returnable() ? CB_RET_INTERNAL : CB_INFO_INTERNAL;
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cancelOwner, "isCancelled", "()Z", false));
        out.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
        if (inject.returnable()) {
            out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
            out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CB_RET_INTERNAL, "getReturnValue",
                "()Ljava/lang/Object;", false));
            Bytecode.unboxOrCast(out, targetReturn);
            out.add(new InsnNode(targetReturn.getOpcode(Opcodes.IRETURN)));
        } else {
            if (targetReturn.getSort() != Type.VOID) {
                throw new IllegalStateException(
                    "@Inject(cancellable=true) with CallbackInfo on non-void target "
                    + target.name + target.desc + " — use CallbackInfoReturnable");
            }
            out.add(new InsnNode(Opcodes.RETURN));
        }
        out.add(notCancelled);
    }
}
