package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @ModifyArgs: captures every argument of the matched INVOKE into a fresh Object[] (boxing
 * primitives), hands the array to the handler (which may mutate elements), then reloads each
 * argument from the (possibly mutated) array before the INVOKE fires.
 */
final class ModifyArgsPass {

    private ModifyArgsPass() {}

    static void apply(
        MethodNode method, List<MixinDescriptor.ModifyArgsEntry> mxa, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyArgsEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String key = mi.owner + "." + mi.name + mi.desc;
            for (MixinDescriptor.ModifyArgsEntry ma : mxa) {
                if (!method.name.equals(ma.targetMethod())) continue;
                if (!DescriptorMatcher.matches(ma.invokeDesc(), key)) continue;
                int count = matchCount.getOrDefault(ma, 0);
                matchCount.put(ma, count + 1);
                if (count != 0) continue;
                Type[] argTypes = Type.getArgumentTypes(mi.desc);
                int n = argTypes.length;
                int[] argLocals = new int[n];
                int slotCursor = method.maxLocals;
                for (int i = 0; i < n; i++) {
                    argLocals[i] = slotCursor;
                    slotCursor += argTypes[i].getSize();
                }
                method.maxLocals = slotCursor;
                InsnList block = new InsnList();
                for (int i = n - 1; i >= 0; i--) {
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
                }
                block.add(Bytecode.intConst(n));
                block.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                int arrLocal = method.maxLocals;
                method.maxLocals += 1;
                block.add(new VarInsnNode(Opcodes.ASTORE, arrLocal));
                for (int i = 0; i < n; i++) {
                    block.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
                    block.add(Bytecode.intConst(i));
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                    Bytecode.emitBox(block, argTypes[i]);
                    block.add(new InsnNode(Opcodes.AASTORE));
                }
                block.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
                // Args-wrapper shape: handler accepts net.echo.hypermixins.annotations.Args,
                // not Object[]. Wrap before the call. Args.set() mutates the same backing
                // array so the reload loop below still sees the post-handler values.
                if (ma.handlerDesc().equals("(Lnet/echo/hypermixins/annotations/Args;)V")) {
                    block.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "net/echo/hypermixins/annotations/Args", "of",
                        "([Ljava/lang/Object;)Lnet/echo/hypermixins/annotations/Args;", false));
                }
                block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
                    ma.handlerName(), ma.handlerDesc(), false));
                for (int i = 0; i < n; i++) {
                    block.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
                    block.add(Bytecode.intConst(i));
                    block.add(new InsnNode(Opcodes.AALOAD));
                    Bytecode.unboxOrCast(block, argTypes[i]);
                }
                method.instructions.insertBefore(insn, block);
                break;
            }
        }
    }
}
