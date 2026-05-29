package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;
import net.echo.hypermixins.registry.MixinRegistry;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MixinTransformer implements ClassFileTransformer {

    private static final String REGISTRY_INTERNAL =
        "net/echo/hypermixins/registry/MixinRegistry";
    private static final String BOOTSTRAP_DESCRIPTOR =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
        "Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;";

    private final Map<String, List<MixinMapping>> targets = new HashMap<>();
    private final Map<String, MixinMapping> mixins  = new HashMap<>();
    private final Set<Class<?>> transformedTargets   = ConcurrentHashMap.newKeySet();
    private final List<String>  registeredKeys       = new ArrayList<>();

    public MixinTransformer(List<MixinMapping> mappings) {
        for (MixinMapping m : mappings) {
            targets.computeIfAbsent(m.getTargetClass().replace('.', '/'), k -> new ArrayList<>()).add(m);
            mixins.put(Type.getInternalName(m.getMixinClass()), m);
        }
    }

    public List<String> registeredKeys() { return List.copyOf(registeredKeys); }
    public Set<Class<?>> transformedTargets() { return Collections.unmodifiableSet(transformedTargets); }

    /**
     * Drops strong references to every target class this transformer touched. Called by
     * {@link net.echo.hypermixins.api.MixinHandle#unregister} so the host application doesn't
     * keep pinning target classes (and their loaders) alive after a mixin is fully removed.
     */
    public void clearTransformedTargets() { transformedTargets.clear(); }

    @Override
    public byte[] transform(
        Module module, ClassLoader loader, String className,
        Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        List<MixinMapping> targetMappings = targets.get(className);
        if (targetMappings != null) {
            byte[] result = transformTarget(classfileBuffer, targetMappings, loader);
            if (classBeingRedefined != null) transformedTargets.add(classBeingRedefined);
            return result;
        }
        MixinMapping mixinMapping = mixins.get(className);
        if (mixinMapping != null) {
            return transformMixin(classfileBuffer, mixinMapping, loader);
        }
        return null;
    }

    // ---- Target transformation ----

    private byte[] transformTarget(byte[] classfile, List<MixinMapping> mappings, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfile);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        List<MethodNode> extraMethods = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();
        Set<String> overwrittenKeys = new HashSet<>();

        // Generate public synthetic accessors on the target for every private @Shadow / @Invoker
        // target. Done up-front so subsequent passes can iterate node.methods safely.
        for (MixinMapping mapping : mappings) {
            for (MixinDescriptor.ShadowEntry sh : mapping.descriptor().shadows()) {
                String targetDesc = PrivateShadowAccessorPass.dropFirstArgFromDescriptor(sh.handlerDesc());
                if (!mapping.descriptor().isPrivateShadowTarget(sh.targetName(), targetDesc)) continue;
                PrivateShadowAccessorPass.add(node,sh.targetName(), targetDesc, extraMethods, addedKeys);
            }
            for (MixinDescriptor.InvokerEntry iv : mapping.descriptor().invokers()) {
                String targetDesc = PrivateShadowAccessorPass.dropFirstArgFromDescriptor(iv.handlerDesc());
                if (!mapping.descriptor().isPrivateShadowTarget(iv.targetName(), targetDesc)) continue;
                PrivateShadowAccessorPass.add(node,iv.targetName(), targetDesc, extraMethods, addedKeys);
            }
        }

        for (MixinMapping mapping : mappings) {
            String mixinField = "__mixin$" + mapping.getMixinClass().getName().replace('.', '$');
            String mixinDesc  = Type.getDescriptor(mapping.getMixinClass());

            boolean hasMixinField = node.fields.stream().anyMatch(f -> f.name.equals(mixinField));
            if (!hasMixinField) {
                node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, mixinField, mixinDesc, null, null));
            }

            Map<String, List<RedirectMapping>> redirectByDesc = new HashMap<>();
            for (RedirectMapping r : mapping.getRedirects()) {
                redirectByDesc.computeIfAbsent(r.invokeDesc(), k -> new ArrayList<>()).add(r);
            }

            Map<String, String[]> synthetics = mapping.descriptor().synthetics();

            // Up-front static-overwrite detection so we can ensure the static mixin field +
            // <clinit> patch happen before we iterate methods (the patch may add a new method).
            for (MethodNode m : new ArrayList<>(node.methods)) {
                if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
                if (mapping.getOverwrites().get(m.name + m.desc) == null) continue;
                StaticMixinField.ensure(node, mapping);
                break;
            }

            // Group @ModifyReturnValue handlers by [targetMethod -> by invokeDesc].
            Map<String, List<MixinDescriptor.ModifyReturnValueEntry>> mrvByDesc = new HashMap<>();
            for (MixinDescriptor.ModifyReturnValueEntry mrv : mapping.descriptor().modifyReturnValues()) {
                mrvByDesc.computeIfAbsent(mrv.invokeDesc(), k -> new ArrayList<>()).add(mrv);
            }
            Class<?> mixinClassForMrv = mapping.getMixinClass();

            List<MixinDescriptor.ModifyConstantEntry> mcs = mapping.descriptor().modifyConstants();

            Map<String, List<MixinDescriptor.ModifyArgEntry>> masByDesc = new HashMap<>();
            for (MixinDescriptor.ModifyArgEntry ma : mapping.descriptor().modifyArgs()) {
                masByDesc.computeIfAbsent(ma.invokeDesc(), k -> new ArrayList<>()).add(ma);
            }

            for (MethodNode method : new ArrayList<>(node.methods)) {
                RedirectPass.apply(method, redirectByDesc);
                if (!mrvByDesc.isEmpty()) ModifyReturnValuePass.apply(method, mrvByDesc, mixinClassForMrv);
                if (!mcs.isEmpty()) ModifyConstantPass.apply(method, mcs, mixinClassForMrv);
                if (!masByDesc.isEmpty()) ModifyArgPass.apply(method, masByDesc, mixinClassForMrv);
                if (!mapping.descriptor().modifyExpressionValues().isEmpty())
                    ModifyExpressionValuePass.apply(method, mapping.descriptor().modifyExpressionValues(), mixinClassForMrv);
                if (!mapping.descriptor().modifyArgsAll().isEmpty())
                    ModifyArgsPass.apply(method, mapping.descriptor().modifyArgsAll(), mixinClassForMrv);
                if (!mapping.descriptor().modifyReceivers().isEmpty())
                    ModifyReceiverPass.apply(method, mapping.descriptor().modifyReceivers(), mixinClassForMrv);

                if (method.name.equals("<init>")) {
                    patchConstructor(method, node, mapping, mixinField);
                }

                List<InjectMapping> injectsForMethod = mapping.getInjects().get(method.name);
                if (injectsForMethod != null && !injectsForMethod.isEmpty()
                    && !method.name.equals("<init>") && !method.name.equals("<clinit>")) {
                    InjectPass.apply(node, method, injectsForMethod, mixinField, mapping.descriptor());
                }

                String key = method.name + method.desc;
                Method overwrite = mapping.getOverwrites().get(key);
                if (overwrite != null) {
                    if (!overwrittenKeys.add(node.name + "#" + key)) {
                        throw new IllegalStateException(
                            "Multiple mixins @Overwrite the same target method " + node.name + "#" + key +
                            "; only one mixin may overwrite a given method per target");
                    }
                    boolean targetIsStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                    String[] names = synthetics.get(key);
                    if (names == null) {
                        throw new IllegalStateException(
                            "Missing precomputed synthetic names for " + node.name + "#" + key);
                    }
                    MethodNode[] copies = applyOverwrite(node, method, overwrite, mixinField, names[0], names[1], targetIsStatic, mapping);
                    for (MethodNode copy : copies) {
                        String copyKey = copy.name + copy.desc;
                        if (addedKeys.add(copyKey)) extraMethods.add(copy);
                    }
                }
            }
        }

        node.methods.addAll(extraMethods);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites {@code target} body as {@code INVOKEDYNAMIC}, creates two synthetic helpers:
     * <ul>
     *   <li>{@code __original$name$hash} — the unmodified original body (pre-mixin).</li>
     *   <li>{@code __dispatch$name$hash} — the mixin-calling body (GETFIELD + INVOKEVIRTUAL).</li>
     * </ul>
     * Returns both helpers; caller must add them to the class.
     */
    private MethodNode[] applyOverwrite(
        ClassNode owner, MethodNode target, Method mixinMethod, String mixinFieldName,
        String mangledOriginalName, String dispName, boolean targetIsStatic, MixinMapping mapping
    ) {
        // 1. Clone original body — keep ACC_STATIC matching the target.
        MethodNode originalCopy = cloneAsOriginal(target, mangledOriginalName);

        // 2. Create __dispatch$xxx with mixin-calling body
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
                di.add(new InsnNode(Opcodes.ACONST_NULL)); // self for static target → null
                Type[] targetArgs = Type.getArgumentTypes(target.desc);
                int slot = 0;
                for (Type t : targetArgs) { di.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot)); slot += t.getSize(); }
            } else {
                di.add(new VarInsnNode(Opcodes.ALOAD, 0));
                di.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, mixinFieldName, mixinDescStr));
                di.add(new VarInsnNode(Opcodes.ALOAD, 0)); // self
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

        // 3. Register pending lazy-install for the bootstrap
        String key = owner.name + "#" + target.name + target.desc;
        MixinRegistry.registerPending(key, originalCopy.name, dispName);
        registeredKeys.add(key);

        // 4. Replace target body with INVOKEDYNAMIC
        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;

        // Call-site descriptor: static targets keep target.desc; instance targets prepend owner.
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

    // ---- Mixin transformation (@Original trampolines) ----

    private byte[] transformMixin(byte[] classfile, MixinMapping mapping, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfile);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        ShadowFieldPass.apply(node, mapping);

        for (MethodNode method : node.methods) {
            String key = method.name + method.desc;
            if (!mapping.getOriginals().containsKey(key)) continue;

            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length == 0) {
                throw new IllegalStateException(
                    "@Original method must declare Object self as first parameter: " + method.name + method.desc);
            }

            Type returnType  = Type.getReturnType(method.desc);
            Type[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
            String targetDesc = Type.getMethodDescriptor(returnType, targetArgs);

            if ((method.access & Opcodes.ACC_NATIVE) != 0) method.access &= ~Opcodes.ACC_NATIVE;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;

            String mappedTarget  = mapping.getTargetClass().replace('.', '/');
            String targetName    = mapping.getOriginals().get(key);
            String[] synths      = mapping.descriptor().synthetics().get(targetName + targetDesc);
            if (synths == null) {
                throw new IllegalStateException(
                    "Missing precomputed synthetic names for @Original target " + targetName + targetDesc);
            }
            String originalName  = synths[0];
            boolean targetIsStatic = mapping.descriptor().isStaticTargetMethod(targetName, targetDesc);

            InsnList insns = new InsnList();
            int slotOrig;
            if (targetIsStatic) {
                slotOrig = 2;
            } else {
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedTarget));
                slotOrig = 2;
            }
            for (Type arg : targetArgs) {
                insns.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slotOrig));
                slotOrig += arg.getSize();
            }
            insns.add(new MethodInsnNode(
                targetIsStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
                mappedTarget, originalName, targetDesc, false));
            insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            method.instructions.add(insns);
        }

        AccessorPass.apply(node, mapping);

        InvokerPass.apply(node, mapping);

        // ---- @Shadow trampolines ----
        Map<String, String> shadowsByHandlerKey = new HashMap<>();
        for (MixinDescriptor.ShadowEntry s : mapping.descriptor().shadows()) {
            shadowsByHandlerKey.put(s.handlerName() + s.handlerDesc(), s.targetName());
        }
        if (!shadowsByHandlerKey.isEmpty()) {
            String mappedTarget = mapping.getTargetClass().replace('.', '/');
            for (MethodNode method : node.methods) {
                String key = method.name + method.desc;
                String targetName = shadowsByHandlerKey.get(key);
                if (targetName == null) continue;

                Type[] args = Type.getArgumentTypes(method.desc);
                if (args.length == 0) {
                    throw new IllegalStateException(
                        "@Shadow method must declare Object self as first parameter: " + method.name + method.desc);
                }
                Type returnType = Type.getReturnType(method.desc);
                Type[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
                String targetDesc = Type.getMethodDescriptor(returnType, targetArgs);

                if ((method.access & Opcodes.ACC_NATIVE) != 0) method.access &= ~Opcodes.ACC_NATIVE;
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.localVariables = null;

                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedTarget));
                int slotShad = 2;
                for (Type arg : targetArgs) {
                    insns.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slotShad));
                    slotShad += arg.getSize();
                }
                String invokedName = mapping.descriptor().isPrivateShadowTarget(targetName, targetDesc)
                    ? PrivateShadowAccessorPass.accessorName(targetName, targetDesc)
                    : targetName;
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mappedTarget, invokedName, targetDesc, false));
                insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
                method.instructions.add(insns);
            }
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites every {@code ALOAD 0; (GETFIELD|PUTFIELD) mixin.shadowField} pair in {@code method}
     * to {@code ALOAD 1; CHECKCAST targetInternal; (GETFIELD|PUTFIELD) targetInternal.targetName}.
     * Matches the canonical {@code this.foo} pattern emitted by javac.
     */
    // ---- Constructor patching ----

    private static void patchConstructor(
        MethodNode ctor, ClassNode owner, MixinMapping mapping, String mixinFieldName
    ) {
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKESPECIAL
                && mi.name.equals("<init>") && mi.owner.equals(owner.name)) return; // this(...)
        }

        AbstractInsnNode superCall = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKESPECIAL
                && mi.name.equals("<init>") && mi.owner.equals(owner.superName)) {
                superCall = insn; break;
            }
        }
        if (superCall == null) throw new IllegalStateException("No super() in constructor of " + owner.name);

        String mixinInternal = Type.getInternalName(mapping.getMixinClass());
        String mixinDesc     = Type.getDescriptor(mapping.getMixinClass());
        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new TypeInsnNode(Opcodes.NEW, mixinInternal));
        inject.add(new InsnNode(Opcodes.DUP));
        inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixinInternal, "<init>", "()V", false));
        inject.add(new FieldInsnNode(Opcodes.PUTFIELD, owner.name, mixinFieldName, mixinDesc));
        ctor.instructions.insert(superCall, inject);
    }


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
            default -> {} // VOID — nothing
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
            default -> {} // reference type — already an Object
        }
    }

    static AbstractInsnNode intConst(int n) {
        if (n >= -1 && n <= 5) return new InsnNode(Opcodes.ICONST_0 + n);
        if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, n);
        if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, n);
        return new LdcInsnNode(n);
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

    /** ClassWriter that resolves superclass via ClassLoader stream, avoiding Class.forName deadlocks. */
    public static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;
        SafeClassWriter(int flags, ClassLoader loader) { super(flags); this.loader = loader; }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) return "java/lang/Object";
            try {
                List<String> chain1 = superChain(type1);
                Set<String> set1 = new HashSet<>(chain1);
                for (String t : superChain(type2)) { if (set1.contains(t)) return t; }
            } catch (IOException ignored) {}
            return "java/lang/Object";
        }

        private List<String> superChain(String name) throws IOException {
            List<String> chain = new ArrayList<>();
            String cur = name;
            while (cur != null && !cur.equals("java/lang/Object")) {
                chain.add(cur); cur = superOf(cur);
            }
            chain.add("java/lang/Object");
            return chain;
        }

        private String superOf(String name) throws IOException {
            ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
            try (InputStream is = cl.getResourceAsStream(name + ".class")) {
                if (is == null) return null;
                return new ClassReader(is).getSuperName();
            }
        }
    }
}
