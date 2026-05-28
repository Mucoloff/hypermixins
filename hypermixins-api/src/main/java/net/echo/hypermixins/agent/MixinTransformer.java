package net.echo.hypermixins.agent;

import net.echo.hypermixins.api.Call;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.*;

public class MixinTransformer implements ClassFileTransformer {

    private final Map<String, MixinMapping> targets = new HashMap<>();
    private final Map<String, MixinMapping> mixins = new HashMap<>();

    public MixinTransformer(List<MixinMapping> mappings) {
        for (MixinMapping m : mappings) {
            targets.put(m.getTargetClass().replace('.', '/'), m);
            mixins.put(Type.getInternalName(m.getMixinClass()), m);
        }
    }

    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        MixinMapping mapping;

        if ((mapping = targets.get(className)) != null) {
            return transformTarget(classfileBuffer, mapping, loader);
        }

        if ((mapping = mixins.get(className)) != null) {
            return transformMixin(classfileBuffer, mapping, loader);
        }

        return null;
    }

    private byte[] transformMixin(byte[] classfile, MixinMapping mapping, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfile);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MethodNode method : node.methods) {
            String key = method.name + method.desc;

            if (!mapping.getOriginals().containsKey(key)) continue;

            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length == 0) {
                throw new IllegalStateException("@Original method must declare Object self as first parameter: " + method.name + method.desc);
            }

            Type returnType = Type.getReturnType(method.desc);
            Type[] targetArgs = Arrays.copyOfRange(args, 1, args.length);
            String targetDesc = Type.getMethodDescriptor(returnType, targetArgs);

            if ((method.access & Opcodes.ACC_NATIVE) == Opcodes.ACC_NATIVE) {
                method.access &= ~Opcodes.ACC_NATIVE;
            }

            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;

            InsnList insns = new InsnList();
            String mappedTargetClass = mapping.getTargetClass().replace('.', '/');

            // slot 0 = mixin `this`, slot 1 = Object self (target instance), slots 2+ = method args
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedTargetClass));

            // load each remaining argument (slots 2, 3, ...) by type
            int slot = 2;
            for (Type arg : targetArgs) {
                insns.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
                slot += arg.getSize();
            }

            String targetName = mapping.getOriginals().get(key);
            String originalName = mangledName(targetName, targetDesc);

            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                mappedTargetClass,
                originalName,
                targetDesc,
                false
            ));

            insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            method.instructions.add(insns);
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
        node.accept(writer);
        return writer.toByteArray();
    }

    private byte[] transformTarget(byte[] classfile, MixinMapping mapping, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfile);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        String mixinField = "__mixin$" + mapping.getMixinClass().getName().replace('.', '$');
        String mixinDesc = Type.getDescriptor(mapping.getMixinClass());

        // add mixin field if not already present
        boolean hasMixinField = false;
        for (FieldNode f : node.fields) {
            if (f.name.equals(mixinField)) { hasMixinField = true; break; }
        }
        if (!hasMixinField) {
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, mixinField, mixinDesc, null, null));
        }

        // build redirect lookup keyed by invokeDesc for O(1) inner lookup
        Map<String, List<RedirectMapping>> redirectByDesc = new HashMap<>();
        for (RedirectMapping r : mapping.getRedirects()) {
            redirectByDesc.computeIfAbsent(r.invokeDesc(), k -> new ArrayList<>()).add(r);
        }

        List<MethodNode> originalCopies = new ArrayList<>();
        Set<String> addedOriginalKeys = new HashSet<>();

        // single pass over methods
        for (MethodNode method : node.methods) {
            // apply redirects
            applyRedirects(method, redirectByDesc);

            // patch constructor (root ctors only — those that call super(), not this())
            if (method.name.equals("<init>")) {
                patchConstructor(method, node, mapping, mixinField);
            }

            // apply overwrite
            String key = method.name + method.desc;
            Method overwrite = mapping.getOverwrites().get(key);
            if (overwrite != null) {
                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                    throw new IllegalStateException(
                        "Cannot @Overwrite static method: " + method.name + method.desc +
                        " in " + node.name + " — static targets not supported"
                    );
                }
                MethodNode orig = applyOverwrite(node, method, overwrite, mixinField);
                String origKey = orig.name + orig.desc;
                if (!addedOriginalKeys.contains(origKey)) {
                    originalCopies.add(orig);
                    addedOriginalKeys.add(origKey);
                }
            }
        }

        node.methods.addAll(originalCopies);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void patchConstructor(
        MethodNode ctor,
        ClassNode owner,
        MixinMapping mapping,
        String mixinFieldName
    ) {
        boolean callsThis = false;

        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKESPECIAL
                && mi.name.equals("<init>")
                && mi.owner.equals(owner.name)) {
                callsThis = true;
                break;
            }
        }

        // Secondary constructor (calls this(...)) — skip to avoid double-init
        if (callsThis) return;

        AbstractInsnNode superCall = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKESPECIAL
                && mi.name.equals("<init>")
                && mi.owner.equals(owner.superName)) {
                superCall = insn;
                break;
            }
        }

        if (superCall == null) {
            throw new IllegalStateException("No super() call found in constructor of " + owner.name);
        }

        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new TypeInsnNode(Opcodes.NEW, Type.getInternalName(mapping.getMixinClass())));
        inject.add(new InsnNode(Opcodes.DUP));
        inject.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            Type.getInternalName(mapping.getMixinClass()),
            "<init>",
            "()V",
            false
        ));
        inject.add(new FieldInsnNode(
            Opcodes.PUTFIELD,
            owner.name,
            mixinFieldName,
            Type.getDescriptor(mapping.getMixinClass())
        ));

        ctor.instructions.insert(superCall, inject);
    }

    /** Returns the saved-original copy; caller adds it to node.methods. */
    private static MethodNode applyOverwrite(
        ClassNode owner,
        MethodNode target,
        Method mixinMethod,
        String mixinFieldName
    ) {
        MethodNode originalCopy = cloneAsOriginal(target);

        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;

        InsnList insns = new InsnList();

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new FieldInsnNode(
            Opcodes.GETFIELD,
            owner.name,
            mixinFieldName,
            Type.getDescriptor(mixinMethod.getDeclaringClass())
        ));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0)); // 'self' arg to mixin handler

        Type[] targetArgs = Type.getArgumentTypes(target.desc);
        int slot = 1;
        for (Type t : targetArgs) {
            insns.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            slot += t.getSize();
        }

        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            Type.getInternalName(mixinMethod.getDeclaringClass()),
            mixinMethod.getName(),
            Type.getMethodDescriptor(mixinMethod),
            false
        ));

        Type returnType = Type.getReturnType(mixinMethod);
        insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

        target.instructions.add(insns);
        return originalCopy;
    }

    private static MethodNode cloneAsOriginal(MethodNode original) {
        String newName = mangledName(original.name, original.desc);
        int acc = (original.access & Opcodes.ACC_STATIC) != 0
            ? (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)
            : (Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC);

        MethodNode copy = new MethodNode(
            acc,
            newName,
            original.desc,
            original.signature,
            original.exceptions == null ? null : original.exceptions.toArray(new String[0])
        );
        original.accept(copy);
        return copy;
    }

    private static void applyRedirects(MethodNode method, Map<String, List<RedirectMapping>> redirectByDesc) {
        if (method.instructions == null) return;

        // track per-redirect occurrence index
        Map<RedirectMapping, Integer> invokeCount = new HashMap<>();

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;

            String invokeKey = mi.owner + "." + mi.name + mi.desc;
            List<RedirectMapping> candidates = redirectByDesc.get(invokeKey);
            if (candidates == null) continue;

            for (RedirectMapping redirect : candidates) {
                if (!method.name.equals(redirect.targetMethod())) continue;

                int count = invokeCount.getOrDefault(redirect, 0);
                invokeCount.put(redirect, count + 1);
                if (count != redirect.index()) continue;

                Call expected = getCall(redirect, mi);
                String handlerDesc = redirect.handlerDesc();

                Type[] originalArgs = Type.getArgumentTypes(mi.desc);
                Type originalReturn = Type.getReturnType(mi.desc);
                Type[] handlerArgs = Type.getArgumentTypes(handlerDesc);
                Type handlerReturn = Type.getReturnType(handlerDesc);

                if (!handlerReturn.equals(originalReturn)) {
                    throw new IllegalArgumentException("Return type mismatch in redirect handler: " + redirect.handler());
                }

                int expectedArgCount = (expected == Call.INVOKESTATIC || expected == Call.INVOKESPECIAL)
                    ? originalArgs.length
                    : originalArgs.length + 1;

                if (handlerArgs.length != expectedArgCount) {
                    throw new IllegalArgumentException("Argument count mismatch in redirect handler: " + redirect.handler());
                }

                if (expected == Call.INVOKEVIRTUAL || expected == Call.INVOKEINTERFACE) {
                    Type receiver = Type.getObjectType(mi.owner);
                    if (!handlerArgs[0].equals(receiver)) {
                        throw new IllegalArgumentException("First handler argument must be receiver type: " + mi.owner);
                    }
                }

                method.instructions.set(mi, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(redirect.handler().getDeclaringClass()),
                    redirect.handler().getName(),
                    handlerDesc,
                    false
                ));
                break; // applied — move to next insn
            }
        }
    }

    private static Call getCall(RedirectMapping redirect, MethodInsnNode mi) {
        Call expected = redirect.call();
        int opcode = mi.getOpcode();
        return switch (expected) {
            case INVOKESTATIC -> {
                if (opcode != Opcodes.INVOKESTATIC)
                    throw new IllegalArgumentException("Expected INVOKESTATIC but found opcode " + opcode);
                yield Call.INVOKESTATIC;
            }
            case INVOKEVIRTUAL -> {
                if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE)
                    throw new IllegalArgumentException("Expected INVOKEVIRTUAL/INTERFACE but found opcode " + opcode);
                yield opcode == Opcodes.INVOKEINTERFACE ? Call.INVOKEINTERFACE : Call.INVOKEVIRTUAL;
            }
            case INVOKEINTERFACE -> {
                if (opcode != Opcodes.INVOKEINTERFACE)
                    throw new IllegalArgumentException("Expected INVOKEINTERFACE but found opcode " + opcode);
                yield Call.INVOKEINTERFACE;
            }
            case INVOKESPECIAL -> {
                if (opcode != Opcodes.INVOKESPECIAL)
                    throw new IllegalArgumentException("Expected INVOKESPECIAL but found opcode " + opcode);
                yield Call.INVOKESPECIAL;
            }
        };
    }

    /** Collision-safe mangled name using first 16 hex chars of SHA-1(desc). */
    public static String mangledName(String methodName, String descriptor) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "__original$" + methodName + "$" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // SHA-1 always available
        }
    }

    /** ClassWriter that resolves common superclass via ClassLoader stream rather than Class.forName. */
    public static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SafeClassWriter(int flags, ClassLoader loader) {
            super(flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // Fast path: identical types or java/lang/Object
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) return "java/lang/Object";
            try {
                List<String> chain1 = superChain(type1);
                List<String> chain2 = superChain(type2);
                Set<String> set1 = new HashSet<>(chain1);
                for (String t : chain2) {
                    if (set1.contains(t)) return t;
                }
            } catch (IOException ignored) {
                // fallback
            }
            return "java/lang/Object";
        }

        private List<String> superChain(String internalName) throws IOException {
            List<String> chain = new ArrayList<>();
            String current = internalName;
            while (current != null && !current.equals("java/lang/Object")) {
                chain.add(current);
                current = superOf(current);
            }
            chain.add("java/lang/Object");
            return chain;
        }

        private String superOf(String internalName) throws IOException {
            ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
            String resource = internalName + ".class";
            try (InputStream is = cl.getResourceAsStream(resource)) {
                if (is == null) return null;
                ClassReader cr = new ClassReader(is);
                return cr.getSuperName();
            }
        }
    }
}
