package net.echo.hypermixins.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Bytecode-emission helpers shared between {@link InjectPass}, {@link ModifyArgsPass}, and the
 * transformer's INVOKEDYNAMIC body. Constants are encoded with the shortest opcode form; box /
 * unbox / NEWARRAY operands follow JVMS §4.10.
 */
final class Bytecode {

    private Bytecode() {}

    static void unboxOrCast(InsnList out, Type target) {
        switch (target.getSort()) {
            case Type.BOOLEAN -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)); }
            case Type.BYTE    -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false)); }
            case Type.CHAR    -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false)); }
            case Type.SHORT   -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false)); }
            case Type.INT     -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)); }
            case Type.LONG    -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)); }
            case Type.FLOAT   -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false)); }
            case Type.DOUBLE  -> { out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)); }
            case Type.OBJECT, Type.ARRAY -> out.add(new TypeInsnNode(Opcodes.CHECKCAST, target.getInternalName()));
            default -> {}
        }
    }

    static int newarrayOperandForPrimitive(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> Opcodes.T_BOOLEAN;
            case Type.BYTE    -> Opcodes.T_BYTE;
            case Type.CHAR    -> Opcodes.T_CHAR;
            case Type.SHORT   -> Opcodes.T_SHORT;
            case Type.INT     -> Opcodes.T_INT;
            case Type.LONG    -> Opcodes.T_LONG;
            case Type.FLOAT   -> Opcodes.T_FLOAT;
            case Type.DOUBLE  -> Opcodes.T_DOUBLE;
            default -> throw new IllegalStateException("not a primitive type: " + t);
        };
    }

    static void emitBox(InsnList out, Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean",   "valueOf", "(Z)Ljava/lang/Boolean;",   false));
            case Type.BYTE    -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte",      "valueOf", "(B)Ljava/lang/Byte;",      false));
            case Type.CHAR    -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT   -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short",     "valueOf", "(S)Ljava/lang/Short;",     false));
            case Type.INT     -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer",   "valueOf", "(I)Ljava/lang/Integer;",   false));
            case Type.LONG    -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long",      "valueOf", "(J)Ljava/lang/Long;",      false));
            case Type.FLOAT   -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float",     "valueOf", "(F)Ljava/lang/Float;",     false));
            case Type.DOUBLE  -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double",    "valueOf", "(D)Ljava/lang/Double;",    false));
            default -> {}
        }
    }

    static AbstractInsnNode intConst(int n) {
        if (n >= -1 && n <= 5) return new InsnNode(Opcodes.ICONST_0 + n);
        if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, n);
        if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, n);
        return new LdcInsnNode(n);
    }
}
