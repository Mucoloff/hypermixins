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
                String targetDesc = dropFirstArgFromDescriptor(sh.handlerDesc());
                if (!mapping.descriptor().isPrivateShadowTarget(sh.targetName(), targetDesc)) continue;
                addPrivateShadowAccessor(node, sh.targetName(), targetDesc, extraMethods, addedKeys);
            }
            for (MixinDescriptor.InvokerEntry iv : mapping.descriptor().invokers()) {
                String targetDesc = dropFirstArgFromDescriptor(iv.handlerDesc());
                if (!mapping.descriptor().isPrivateShadowTarget(iv.targetName(), targetDesc)) continue;
                addPrivateShadowAccessor(node, iv.targetName(), targetDesc, extraMethods, addedKeys);
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
                ensureStaticMixinField(node, mapping);
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
                applyRedirects(method, redirectByDesc);
                if (!mrvByDesc.isEmpty()) ModifyReturnValuePass.apply(method, mrvByDesc, mixinClassForMrv);
                if (!mcs.isEmpty()) applyModifyConstants(method, mcs, mixinClassForMrv);
                if (!masByDesc.isEmpty()) applyModifyArgs(method, masByDesc, mixinClassForMrv);
                if (!mapping.descriptor().modifyExpressionValues().isEmpty())
                    applyModifyExpressionValues(method, mapping.descriptor().modifyExpressionValues(), mixinClassForMrv);
                if (!mapping.descriptor().modifyArgsAll().isEmpty())
                    applyModifyArgsAll(method, mapping.descriptor().modifyArgsAll(), mixinClassForMrv);
                if (!mapping.descriptor().modifyReceivers().isEmpty())
                    applyModifyReceivers(method, mapping.descriptor().modifyReceivers(), mixinClassForMrv);

                if (method.name.equals("<init>")) {
                    patchConstructor(method, node, mapping, mixinField);
                }

                List<InjectMapping> injectsForMethod = mapping.getInjects().get(method.name);
                if (injectsForMethod != null && !injectsForMethod.isEmpty()
                    && !method.name.equals("<init>") && !method.name.equals("<clinit>")) {
                    applyInjects(node, method, injectsForMethod, mixinField, mapping.descriptor());
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
                    staticMixinFieldName(mapping), mixinDescStr));
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

    private static String dropFirstArgFromDescriptor(String desc) {
        Type[] all = Type.getArgumentTypes(desc);
        if (all.length == 0) return desc;
        Type ret = Type.getReturnType(desc);
        Type[] rest = Arrays.copyOfRange(all, 1, all.length);
        return Type.getMethodDescriptor(ret, rest);
    }

    static String privateShadowAccessorName(String targetName, String targetDesc) {
        return "__access$" + targetName + "$" + NameHash.hashHex(targetDesc);
    }

    /**
     * Adds {@code public synthetic returnType __access$name$hash(args...) { ALOAD 0; args;
     * INVOKESPECIAL Target.<name>; ?RETURN; }} to {@code node}. Lets the mixin trampoline reach
     * a private target method via an INVOKEVIRTUAL on this accessor.
     */
    private static void addPrivateShadowAccessor(
        ClassNode node, String targetName, String targetDesc,
        List<MethodNode> extraMethods, Set<String> addedKeys
    ) {
        String accessor = privateShadowAccessorName(targetName, targetDesc);
        String key = accessor + targetDesc;
        if (!addedKeys.add(key)) return;
        // Don't duplicate if already added by a previous mixin.
        for (MethodNode existing : node.methods) {
            if (existing.name.equals(accessor) && existing.desc.equals(targetDesc)) return;
        }
        MethodNode acc = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            accessor, targetDesc, null, null);
        InsnList ins = new InsnList();
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        Type[] argTypes = Type.getArgumentTypes(targetDesc);
        int slot = 1;
        for (Type a : argTypes) {
            ins.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
            slot += a.getSize();
        }
        ins.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, targetName, targetDesc, false));
        Type ret = Type.getReturnType(targetDesc);
        ins.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        acc.instructions.add(ins);
        extraMethods.add(acc);
    }

    private static String staticMixinFieldName(MixinMapping mapping) {
        return "__mixin$static$" + mapping.getMixinClass().getName().replace('.', '$');
    }

    /**
     * Ensures the target class carries a static field holding the singleton mixin instance,
     * initialized in &lt;clinit&gt;. Re-entrant: skips the work if both the field and the
     * matching PUTSTATIC are already present from a previous mixin's pass.
     */
    private static void ensureStaticMixinField(ClassNode node, MixinMapping mapping) {
        String fieldName = staticMixinFieldName(mapping);
        String mixinDesc = Type.getDescriptor(mapping.getMixinClass());
        String mixinInternal = Type.getInternalName(mapping.getMixinClass());
        boolean hasField = node.fields.stream().anyMatch(f -> f.name.equals(fieldName));
        if (!hasField) {
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                fieldName, mixinDesc, null, null));
        }
        MethodNode clinit = null;
        for (MethodNode m : node.methods) {
            if (m.name.equals("<clinit>")) { clinit = m; break; }
        }
        boolean createdClinit = false;
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(clinit);
            createdClinit = true;
        }
        // Skip if the PUTSTATIC for this static field is already present.
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fi
                && fi.getOpcode() == Opcodes.PUTSTATIC
                && fi.owner.equals(node.name) && fi.name.equals(fieldName)) {
                return;
            }
        }
        InsnList init = new InsnList();
        init.add(new TypeInsnNode(Opcodes.NEW, mixinInternal));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixinInternal, "<init>", "()V", false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, fieldName, mixinDesc));
        if (createdClinit) {
            // New clinit body is just RETURN; insert before it.
            clinit.instructions.insertBefore(clinit.instructions.getFirst(), init);
        } else {
            // Insert at start of existing clinit.
            AbstractInsnNode first = clinit.instructions.getFirst();
            if (first == null) clinit.instructions.add(init);
            else clinit.instructions.insertBefore(first, init);
        }
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
                    ? privateShadowAccessorName(targetName, targetDesc)
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

    // ---- Inject application (HEAD / RETURN / TAIL) ----

    private static final String CB_INFO_INTERNAL = "net/echo/hypermixins/annotations/CallbackInfo";
    private static final String CB_INFO_DESC     = "Lnet/echo/hypermixins/annotations/CallbackInfo;";
    private static final String CB_RET_INTERNAL  = "net/echo/hypermixins/annotations/CallbackInfoReturnable";
    private static final String CB_RET_DESC      = "Lnet/echo/hypermixins/annotations/CallbackInfoReturnable;";

    private static void applyInjects(
        ClassNode owner, MethodNode target, List<InjectMapping> injects, String mixinField,
        MixinDescriptor descriptor
    ) {
        if ((target.access & Opcodes.ACC_ABSTRACT) != 0) return;
        Type targetReturn = Type.getReturnType(target.desc);

        // Build per-handler slot maps once. The map stores the InjectLocalEntry so emitInjectCall
        // can read both the literal slot and the ordinal fallback.
        Map<String, Map<Integer, MixinDescriptor.InjectLocalEntry>> localEntryByHandler = new HashMap<>();
        for (MixinDescriptor.InjectLocalEntry le : descriptor.injectLocals()) {
            localEntryByHandler
                .computeIfAbsent(le.handlerName() + le.handlerDesc(), k -> new HashMap<>())
                .put(le.paramIndex(), le);
        }

        for (InjectMapping inject : injects) {
            String handlerKey = inject.handler().getName() + Type.getMethodDescriptor(inject.handler());
            Map<Integer, MixinDescriptor.InjectLocalEntry> entryMap =
                localEntryByHandler.getOrDefault(handlerKey, Map.of());
            Set<Integer> argsOnlyParams = new HashSet<>();
            for (Map.Entry<Integer, MixinDescriptor.InjectLocalEntry> e : entryMap.entrySet()) {
                if (e.getValue().argsOnly()) argsOnlyParams.add(e.getKey());
            }
            Map<Integer, Integer> slotMap = new HashMap<>();
            for (Map.Entry<Integer, MixinDescriptor.InjectLocalEntry> e : entryMap.entrySet()) {
                MixinDescriptor.InjectLocalEntry le = e.getValue();
                if (le.slot() >= 0) {
                    slotMap.put(e.getKey(), le.slot());
                } else {
                    // ordinal >= 0 → K-th matching target param.
                    // ordinal < 0 → unique matching target param (error if ambiguous).
                    Type[] handlerArgs = Type.getArgumentTypes(Type.getMethodDescriptor(inject.handler()));
                    Type wantedRaw = handlerArgs[e.getKey()];
                    // For argsOnly, handler declares T[]; the local we resolve has element type T.
                    Type wanted = le.argsOnly() && wantedRaw.getSort() == Type.ARRAY
                        ? wantedRaw.getElementType()
                        : wantedRaw;
                    Type[] targetArgs = Type.getArgumentTypes(target.desc);
                    int slotCursor = 1;
                    int seen = 0;
                    int resolved = -1;
                    int resolvedCount = 0;
                    for (Type t : targetArgs) {
                        if (t.equals(wanted)) {
                            if (le.ordinal() >= 0) {
                                if (seen == le.ordinal()) { resolved = slotCursor; break; }
                                seen++;
                            } else {
                                if (resolvedCount == 0) resolved = slotCursor;
                                resolvedCount++;
                            }
                        }
                        slotCursor += t.getSize();
                    }
                    if (resolved < 0) {
                        throw new IllegalStateException(
                            "@Local of type " + wanted + " not found in target "
                                + target.name + target.desc);
                    }
                    if (le.ordinal() < 0 && resolvedCount > 1) {
                        throw new IllegalStateException(
                            "@Local of type " + wanted + " is ambiguous (matches " + resolvedCount
                                + " target params) — set index or ordinal on "
                                + inject.handler().getName());
                    }
                    slotMap.put(e.getKey(), resolved);
                }
            }
            At.Shift shift = descriptor.injectShifts().getOrDefault(handlerKey, At.Shift.BEFORE);
            switch (inject.point()) {
                case HEAD -> {
                    AbstractInsnNode first = target.instructions.getFirst();
                    InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
                    if (first == null) target.instructions.add(block);
                    else target.instructions.insertBefore(first, block);
                }
                case TAIL, RETURN -> injectBeforeReturns(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
                case INVOKE -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesInvoke(insn, inject));
                case FIELD -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesField(insn, inject));
                case CONSTANT -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesConstant(insn, inject));
                case JUMP -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    MixinTransformer::isConditionalJump);
                case NEW -> injectAtMatchingSites(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams, shift,
                    insn -> matchesNew(insn, inject));
                default -> throw new IllegalStateException("Unsupported @Inject point: " + inject.point());
            }
        }
    }

    /**
     * Injects the handler call before each instruction matched by {@code predicate}, honoring
     * {@code inject.index()} as a zero-based occurrence selector (negative or zero matches all,
     * positive matches only the Nth occurrence — mirrors the existing {@code @Redirect#index}).
     */
    private static void injectAtMatchingSites(
        ClassNode owner, MethodNode target, InjectMapping inject,
        String mixinField, Type targetReturn, Map<Integer, Integer> slotMap,
        Set<Integer> argsOnlyParams, At.Shift shift,
        java.util.function.Predicate<AbstractInsnNode> predicate
    ) {
        List<AbstractInsnNode> sites = new ArrayList<>();
        int matchCount = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!predicate.test(insn)) continue;
            if (inject.index() <= 0 || matchCount == inject.index()) sites.add(insn);
            matchCount++;
            if (inject.index() > 0 && matchCount > inject.index()) break;
        }
        if (sites.isEmpty()) {
            throw new IllegalStateException(
                "@Inject " + inject.point() + " found no matching site for "
                + inject.handler() + " (atDesc=" + inject.atDesc() + ", index=" + inject.index() + ")");
        }
        for (AbstractInsnNode site : sites) {
            InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
            if (shift == At.Shift.AFTER) {
                target.instructions.insert(site, block);
            } else {
                target.instructions.insertBefore(site, block);
            }
        }
    }

    private static boolean matchesInvoke(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof MethodInsnNode mi)) return false;
        return DescriptorMatcher.matches(inject.atDesc(), mi.owner + "." + mi.name + mi.desc);
    }

    private static boolean matchesField(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof FieldInsnNode fi)) return false;
        return DescriptorMatcher.matches(inject.atDesc(), fi.owner + "." + fi.name + ":" + fi.desc);
    }

    /**
     * Constant match form: {@code "<type>:<value>"}. Supported types:
     * <pre>
     *   I:42                     int constant
     *   J:1234                   long
     *   F:3.14                   float
     *   D:2.718                  double
     *   Ljava/lang/String;:foo   string constant (raw value, no quoting)
     * </pre>
     */
    private static boolean matchesConstant(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof LdcInsnNode ldc)) return false;
        String desc = inject.atDesc();
        int sep = desc.indexOf(':');
        if (sep < 0) return false;
        String type = desc.substring(0, sep);
        String value = desc.substring(sep + 1);
        Object cst = ldc.cst;
        return switch (type) {
            case "I" -> cst instanceof Integer i && i == Integer.parseInt(value);
            case "J" -> cst instanceof Long l && l == Long.parseLong(value);
            case "F" -> cst instanceof Float f && f == Float.parseFloat(value);
            case "D" -> cst instanceof Double d && d == Double.parseDouble(value);
            case "Ljava/lang/String;" -> cst instanceof String s && s.equals(value);
            default -> false;
        };
    }

    private static boolean matchesNew(AbstractInsnNode insn, InjectMapping inject) {
        if (!(insn instanceof TypeInsnNode tn)) return false;
        if (tn.getOpcode() != Opcodes.NEW) return false;
        return tn.desc.equals(inject.atDesc());
    }

    private static boolean isConditionalJump(AbstractInsnNode insn) {
        if (!(insn instanceof JumpInsnNode jump)) return false;
        int op = jump.getOpcode();
        return op != Opcodes.GOTO && op != Opcodes.JSR;
    }

    private static void injectBeforeReturns(
        ClassNode owner, MethodNode target, InjectMapping inject, String mixinField, Type targetReturn,
        Map<Integer, Integer> slotMap, Set<Integer> argsOnlyParams
    ) {
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) returns.add(insn);
        }
        for (AbstractInsnNode ret : returns) {
            InsnList block = emitInjectCall(owner, target, inject, mixinField, targetReturn, slotMap, argsOnlyParams);
            target.instructions.insertBefore(ret, block);
        }
    }

    /**
     * Emits bytecode that:
     * 1. allocates a CallbackInfo / CallbackInfoReturnable (when cancellable) and stores it in a fresh local,
     * 2. invokes the mixin handler (this.mixinField, self=this, [...captures...], [ci]),
     * 3. if cancellable: checks isCancelled() → if true, returns (with override value for returnable).
     */
    private static InsnList emitInjectCall(
        ClassNode owner, MethodNode target, InjectMapping inject,
        String mixinField, Type targetReturn, Map<Integer, Integer> slotMap,
        Set<Integer> argsOnlyParams
    ) {
        InsnList out = new InsnList();
        Class<?> mixinClass = inject.handler().getDeclaringClass();
        String mixinInternal = Type.getInternalName(mixinClass);
        String mixinDesc     = Type.getDescriptor(mixinClass);
        String handlerDesc   = Type.getMethodDescriptor(inject.handler());

        int ciLocal = -1;
        if (inject.cancellable()) {
            // Allocate CallbackInfo[Returnable] via static `of(String)` factory; store in fresh local.
            ciLocal = target.maxLocals;
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
        }

        // this.mixinField — the mixin instance
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, mixinField, mixinDesc));
        // self
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // Local capture: between `self` and the optional trailing CallbackInfo, handler
        // params are read either from the target's incoming-parameter slots (positional default)
        // or from explicit slot numbers given by @Local(index=N) on individual handler params.
        Type[] handlerArgs = Type.getArgumentTypes(handlerDesc);
        int captureCount = handlerArgs.length - 1 - (inject.cancellable() ? 1 : 0);
        // paramIndex (in handlerDesc, 0 = self) → fresh local holding the argsOnly array.
        Map<Integer, Integer> argsOnlyArrayLocals = new HashMap<>();
        Map<Integer, Integer> argsOnlySourceSlots = new HashMap<>();
        Map<Integer, Type> argsOnlyElementTypes = new HashMap<>();
        if (captureCount > 0) {
            Type[] targetArgs = Type.getArgumentTypes(target.desc);
            int positionalSlot = 1;
            int positionalIdx = 0;
            for (int i = 0; i < captureCount; i++) {
                Type expected = handlerArgs[1 + i];
                boolean argsOnly = argsOnlyParams.contains(1 + i);
                Integer forcedSlot = slotMap.get(1 + i);
                if (argsOnly) {
                    if (forcedSlot == null) {
                        throw new IllegalStateException(
                            "@Local(argsOnly = true) requires a resolvable source slot for handler "
                                + inject.handler() + " param " + i);
                    }
                    if (expected.getSort() != Type.ARRAY) {
                        throw new IllegalStateException(
                            "@Local(argsOnly = true) handler param must be a single-element array — got "
                                + expected + " on " + inject.handler());
                    }
                    Type element = expected.getElementType();
                    // Allocate fresh array, init array[0], stash in local, push for handler.
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    if (element.getSort() == Type.OBJECT || element.getSort() == Type.ARRAY) {
                        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, element.getInternalName()));
                    } else {
                        out.add(new IntInsnNode(Opcodes.NEWARRAY, newarrayOperandForPrimitive(element)));
                    }
                    int arrLocal = target.maxLocals;
                    target.maxLocals += 1;
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new VarInsnNode(element.getOpcode(Opcodes.ILOAD), forcedSlot));
                    out.add(new InsnNode(element.getOpcode(Opcodes.IASTORE)));
                    out.add(new InsnNode(Opcodes.DUP));
                    out.add(new VarInsnNode(Opcodes.ASTORE, arrLocal));
                    argsOnlyArrayLocals.put(1 + i, arrLocal);
                    argsOnlySourceSlots.put(1 + i, forcedSlot);
                    argsOnlyElementTypes.put(1 + i, element);
                    continue;
                }
                if (forcedSlot != null) {
                    out.add(new VarInsnNode(expected.getOpcode(Opcodes.ILOAD), forcedSlot));
                    continue;
                }
                if (positionalIdx >= targetArgs.length) {
                    throw new IllegalStateException(
                        "@Inject handler " + inject.handler() +
                        " declares positional capture beyond target arity for " +
                        target.name + target.desc);
                }
                Type actual = targetArgs[positionalIdx];
                if (!expected.equals(actual)) {
                    throw new IllegalStateException(
                        "@Inject handler " + inject.handler() + " param " + i +
                        " type " + expected + " does not match target " + target.name + target.desc +
                        " param " + positionalIdx + " type " + actual);
                }
                out.add(new VarInsnNode(actual.getOpcode(Opcodes.ILOAD), positionalSlot));
                positionalSlot += actual.getSize();
                positionalIdx++;
            }
        }
        if (inject.cancellable()) {
            out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
        }
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mixinInternal,
            inject.handler().getName(), handlerDesc, false));

        // @Local(argsOnly = true) writeback: load array[0] from each saved local back into source slot.
        for (Map.Entry<Integer, Integer> e : argsOnlyArrayLocals.entrySet()) {
            int paramIdx = e.getKey();
            int arrLocal = e.getValue();
            int sourceSlot = argsOnlySourceSlots.get(paramIdx);
            Type element = argsOnlyElementTypes.get(paramIdx);
            out.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
            out.add(new InsnNode(Opcodes.ICONST_0));
            out.add(new InsnNode(element.getOpcode(Opcodes.IALOAD)));
            out.add(new VarInsnNode(element.getOpcode(Opcodes.ISTORE), sourceSlot));
        }

        if (inject.cancellable()) {
            LabelNode notCancelled = new LabelNode();
            out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
            String cancelOwner = inject.returnable() ? CB_RET_INTERNAL : CB_INFO_INTERNAL;
            out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cancelOwner, "isCancelled", "()Z", false));
            out.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
            // Cancelled: emit return matching target return type.
            if (inject.returnable()) {
                // Load override value, unbox/cast.
                out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CB_RET_INTERNAL, "getReturnValue",
                    "()Ljava/lang/Object;", false));
                unboxOrCast(out, targetReturn);
                out.add(new InsnNode(targetReturn.getOpcode(Opcodes.IRETURN)));
            } else {
                // Void cancel — must be void target; emit RETURN.
                if (targetReturn.getSort() != Type.VOID) {
                    throw new IllegalStateException(
                        "@Inject(cancellable=true) with CallbackInfo on non-void target " +
                        target.name + target.desc + " — use CallbackInfoReturnable");
                }
                out.add(new InsnNode(Opcodes.RETURN));
            }
            out.add(notCancelled);
        }
        return out;
    }

    private static void unboxOrCast(InsnList out, Type target) {
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

    // ---- Redirect application (direct injection, no INVOKEDYNAMIC for now) ----

    private static void applyRedirects(MethodNode method, Map<String, List<RedirectMapping>> redirectByDesc) {
        if (method.instructions == null) return;
        // Pre-compute wildcard candidates once so the hot loop iterates a flat list.
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
            // Append wildcards whose pattern matches the candidate key.
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

    /**
     * Walks the target method for constant loads matching any {@code @ModifyConstant} entry's
     * (type, value, target, index) tuple. Inserts an INVOKESTATIC handler call immediately after
     * the matched load so the handler input is the pushed value and its result replaces the
     * value on stack.
     */
    private static void applyModifyConstants(
        MethodNode method, List<MixinDescriptor.ModifyConstantEntry> mcs, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyConstantEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);

        for (AbstractInsnNode insn : snapshot) {
            for (MixinDescriptor.ModifyConstantEntry mc : mcs) {
                if (!method.name.equals(mc.targetMethod())) continue;
                if (!matchesConstantLoad(insn, mc.type(), mc.value())) continue;
                int count = matchCount.getOrDefault(mc, 0);
                matchCount.put(mc, count + 1);
                if (count != mc.index()) continue;
                method.instructions.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, mixinInternal, mc.handlerName(), mc.handlerDesc(), false));
                break;
            }
        }
    }

    private static boolean matchesConstantLoad(AbstractInsnNode insn, String type, String value) {
        int op = insn.getOpcode();
        switch (type) {
            case "I" -> {
                int wanted = Integer.parseInt(value);
                if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return wanted == (op - Opcodes.ICONST_0);
                if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)
                    return wanted == ((IntInsnNode) insn).operand;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i)
                    return i == wanted;
                return false;
            }
            case "J" -> {
                long wanted = Long.parseLong(value);
                if (op == Opcodes.LCONST_0) return wanted == 0L;
                if (op == Opcodes.LCONST_1) return wanted == 1L;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l)
                    return l == wanted;
                return false;
            }
            case "F" -> {
                float wanted = Float.parseFloat(value);
                if (op == Opcodes.FCONST_0) return wanted == 0f;
                if (op == Opcodes.FCONST_1) return wanted == 1f;
                if (op == Opcodes.FCONST_2) return wanted == 2f;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Float f)
                    return f == wanted;
                return false;
            }
            case "D" -> {
                double wanted = Double.parseDouble(value);
                if (op == Opcodes.DCONST_0) return wanted == 0d;
                if (op == Opcodes.DCONST_1) return wanted == 1d;
                if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double d)
                    return d == wanted;
                return false;
            }
            case "Ljava/lang/String;" -> {
                return op == Opcodes.LDC && insn instanceof LdcInsnNode ldc
                    && ldc.cst instanceof String s && s.equals(value);
            }
            default -> { return false; }
        }
    }

    /**
     * Generalisation of {@code @ModifyReturnValue} that supports {@code At.Point} = {@code INVOKE}
     * (matches a MethodInsnNode by owner.name+desc), {@code FIELD} (matches a GETFIELD/GETSTATIC
     * by owner.name:desc), or {@code CONSTANT} (matches a LDC by the {@code "type:value"} form).
     * In every case the handler INVOKESTATIC fires immediately after the producing instruction so
     * the handler input is the pushed value.
     */
    private static void applyModifyExpressionValues(
        MethodNode method, List<MixinDescriptor.ModifyExpressionValueEntry> mxs, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyExpressionValueEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            for (MixinDescriptor.ModifyExpressionValueEntry mx : mxs) {
                if (!method.name.equals(mx.targetMethod())) continue;
                if (!matchesExpressionSite(insn, mx)) continue;
                int count = matchCount.getOrDefault(mx, 0);
                matchCount.put(mx, count + 1);
                if (count != mx.index()) continue;
                method.instructions.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, mixinInternal, mx.handlerName(), mx.handlerDesc(), false));
                break;
            }
        }
    }

    private static boolean matchesExpressionSite(AbstractInsnNode insn, MixinDescriptor.ModifyExpressionValueEntry mx) {
        switch (mx.point()) {
            case INVOKE:
                if (!(insn instanceof MethodInsnNode mi)) return false;
                return DescriptorMatcher.matches(mx.atDesc(), mi.owner + "." + mi.name + mi.desc);
            case FIELD:
                if (!(insn instanceof FieldInsnNode fi)) return false;
                if (fi.getOpcode() != Opcodes.GETFIELD && fi.getOpcode() != Opcodes.GETSTATIC) return false;
                return DescriptorMatcher.matches(mx.atDesc(), fi.owner + "." + fi.name + ":" + fi.desc);
            case CONSTANT: {
                String desc = mx.atDesc();
                int sep = desc.indexOf(':');
                if (sep < 0) return false;
                return matchesConstantLoad(insn, desc.substring(0, sep), desc.substring(sep + 1));
            }
            default:
                throw new IllegalStateException("@ModifyExpressionValue point " + mx.point() + " not supported");
        }
    }

    /**
     * @ModifyArgs: captures every reference-typed argument of the matched INVOKE into a fresh
     * Object[], hands the array to the handler (which may mutate elements), then reloads each
     * argument from the array before the INVOKE fires. Reference args only — primitive args fail
     * at transform time with a clear error.
     */
    private static void applyModifyArgsAll(
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
                // Allocate per-arg temp locals respecting two-slot primitives.
                int[] argLocals = new int[n];
                int slotCursor = method.maxLocals;
                for (int i = 0; i < n; i++) {
                    argLocals[i] = slotCursor;
                    slotCursor += argTypes[i].getSize();
                }
                method.maxLocals = slotCursor;
                InsnList block = new InsnList();
                // Stash args into temp locals in reverse order (stack top = last arg).
                for (int i = n - 1; i >= 0; i--) {
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
                }
                // Build Object[N].
                block.add(intConst(n));
                block.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                int arrLocal = method.maxLocals;
                method.maxLocals += 1;
                block.add(new VarInsnNode(Opcodes.ASTORE, arrLocal));
                for (int i = 0; i < n; i++) {
                    block.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
                    block.add(intConst(i));
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                    emitBox(block, argTypes[i]);
                    block.add(new InsnNode(Opcodes.AASTORE));
                }
                // Invoke handler.
                block.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
                block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
                    ma.handlerName(), ma.handlerDesc(), false));
                // Reload args from the (possibly mutated) array; unbox primitives.
                for (int i = 0; i < n; i++) {
                    block.add(new VarInsnNode(Opcodes.ALOAD, arrLocal));
                    block.add(intConst(i));
                    block.add(new InsnNode(Opcodes.AALOAD));
                    unboxOrCast(block, argTypes[i]);
                }
                method.instructions.insertBefore(insn, block);
                break;
            }
        }
    }

    /**
     * @ModifyReceiver: captures the args of a matched INVOKEVIRTUAL / INVOKEINTERFACE into
     * temp locals (in reverse order so the original push sequence is preserved), invokes the
     * static (T)T handler on the bare receiver, then pushes the args back. INVOKESTATIC and
     * INVOKESPECIAL call sites have no receiver and are rejected at transform time.
     */
    private static void applyModifyReceivers(
        MethodNode method, List<MixinDescriptor.ModifyReceiverEntry> mxr, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        Map<MixinDescriptor.ModifyReceiverEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String key = mi.owner + "." + mi.name + mi.desc;
            for (MixinDescriptor.ModifyReceiverEntry mr : mxr) {
                if (!method.name.equals(mr.targetMethod())) continue;
                if (!DescriptorMatcher.matches(mr.invokeDesc(), key)) continue;
                int op = mi.getOpcode();
                if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKEINTERFACE) {
                    throw new IllegalStateException(
                        "@ModifyReceiver only applies to virtual / interface call sites — got opcode " + op + " on " + key);
                }
                int count = matchCount.getOrDefault(mr, 0);
                matchCount.put(mr, count + 1);
                if (count != 0) continue;
                Type[] argTypes = Type.getArgumentTypes(mi.desc);
                int[] argLocals = new int[argTypes.length];
                int cursor = method.maxLocals;
                for (int i = 0; i < argTypes.length; i++) {
                    argLocals[i] = cursor;
                    cursor += argTypes[i].getSize();
                }
                method.maxLocals = cursor;
                InsnList block = new InsnList();
                // Stash args in reverse so the bare receiver ends up on top.
                for (int i = argTypes.length - 1; i >= 0; i--) {
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
                }
                // Invoke handler on the receiver.
                block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
                    mr.handlerName(), mr.handlerDesc(), false));
                // Push args back in original order.
                for (int i = 0; i < argTypes.length; i++) {
                    block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                }
                method.instructions.insertBefore(insn, block);
                break;
            }
        }
    }

    private static int newarrayOperandForPrimitive(Type t) {
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

    private static void emitBox(InsnList out, Type t) {
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

    private static AbstractInsnNode intConst(int n) {
        if (n >= -1 && n <= 5) return new InsnNode(Opcodes.ICONST_0 + n);
        if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, n);
        if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, n);
        return new LdcInsnNode(n);
    }

    /**
     * Supports any argument position. For index == last arg the handler INVOKESTATIC is inserted
     * straight before the call (the value is already on top). For lower positions, the args above
     * the target are stashed into per-arg temp locals, the handler runs on the now-exposed value,
     * and the stashed args are restored before the original INVOKE fires.
     */
    private static void applyModifyArgs(
        MethodNode method, Map<String, List<MixinDescriptor.ModifyArgEntry>> masByDesc, Class<?> mixinClass
    ) {
        if (method.instructions == null) return;
        String mixinInternal = Type.getInternalName(mixinClass);
        List<MixinDescriptor.ModifyArgEntry> wildcards = masByDesc.entrySet().stream()
            .filter(e -> e.getKey().indexOf('*') >= 0 || e.getKey().startsWith("regex:"))
            .flatMap(e -> e.getValue().stream())
            .toList();
        Map<MixinDescriptor.ModifyArgEntry, Integer> matchCount = new HashMap<>();
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext())
            snapshot.add(insn);
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            String key = mi.owner + "." + mi.name + mi.desc;
            List<MixinDescriptor.ModifyArgEntry> candidates = masByDesc.get(key);
            if (candidates == null && wildcards.isEmpty()) continue;
            List<MixinDescriptor.ModifyArgEntry> effective = candidates != null ? candidates : List.of();
            if (!wildcards.isEmpty()) {
                List<MixinDescriptor.ModifyArgEntry> all = new ArrayList<>(effective);
                for (MixinDescriptor.ModifyArgEntry w : wildcards) {
                    if (DescriptorMatcher.matches(w.invokeDesc(), key)) all.add(w);
                }
                effective = all;
            }
            for (MixinDescriptor.ModifyArgEntry ma : effective) {
                if (!method.name.equals(ma.targetMethod())) continue;
                int count = matchCount.getOrDefault(ma, 0);
                matchCount.put(ma, count + 1);
                if (count != 0) continue;
                Type[] argTypes = Type.getArgumentTypes(mi.desc);
                int argIdx = ma.argIndex();
                if (argIdx < 0 || argIdx >= argTypes.length) {
                    throw new IllegalStateException(
                        "@ModifyArg index=" + argIdx + " out of range for site " + key);
                }
                if (argIdx == argTypes.length - 1) {
                    // Last arg: handler INVOKESTATIC straight in front of the call.
                    method.instructions.insertBefore(mi, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, mixinInternal, ma.handlerName(), ma.handlerDesc(), false));
                } else {
                    // Stash args[argIdx+1..end] into temp locals, run handler on the now-top
                    // value, restore the stashed args.
                    int[] argLocals = new int[argTypes.length];
                    int cursor = method.maxLocals;
                    for (int i = 0; i < argTypes.length; i++) {
                        argLocals[i] = cursor;
                        cursor += argTypes[i].getSize();
                    }
                    method.maxLocals = cursor;
                    InsnList block = new InsnList();
                    for (int i = argTypes.length - 1; i > argIdx; i--) {
                        block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
                    }
                    block.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mixinInternal,
                        ma.handlerName(), ma.handlerDesc(), false));
                    for (int i = argIdx + 1; i < argTypes.length; i++) {
                        block.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
                    }
                    method.instructions.insertBefore(mi, block);
                }
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
                // Reachable only if a FieldInsnNode was misinterpreted as a method site —
                // applyRedirects routes field opcodes through validateFieldRedirect.
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

    // ---- Helpers ----

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
