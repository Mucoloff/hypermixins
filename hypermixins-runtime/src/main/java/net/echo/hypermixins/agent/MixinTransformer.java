package net.echo.hypermixins.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MixinTransformer implements ClassFileTransformer {

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
            ImplementsPass.apply(node, mapping);
            UniquePass.apply(node, mapping);

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

            Map<String, List<MixinDescriptor.WrapConditionEntry>> wcsByDesc = new HashMap<>();
            for (MixinDescriptor.WrapConditionEntry wc : mapping.descriptor().wrapConditions()) {
                wcsByDesc.computeIfAbsent(wc.invokeDesc(), k -> new ArrayList<>()).add(wc);
            }

            Map<String, List<MixinDescriptor.WrapOperationEntry>> wopsByDesc = new HashMap<>();
            for (MixinDescriptor.WrapOperationEntry wo : mapping.descriptor().wrapOperations()) {
                wopsByDesc.computeIfAbsent(wo.invokeDesc(), k -> new ArrayList<>()).add(wo);
            }
            Set<String> wrapAdaptersGenerated = new HashSet<>();

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
                if (!wcsByDesc.isEmpty())
                    WrapWithConditionPass.apply(method, wcsByDesc, mixinClassForMrv);
                if (!wopsByDesc.isEmpty())
                    WrapOperationPass.apply(node, method, wopsByDesc, mixinClassForMrv,
                        wrapAdaptersGenerated, extraMethods);

                if (method.name.equals("<init>")) {
                    ConstructorPatch.apply(method, node, mapping, mixinField);
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
                    MethodNode[] copies = OverwritePass.apply(node, method, overwrite, mixinField, names[0], names[1], targetIsStatic, mapping, registeredKeys);
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

    // ---- Mixin transformation (@Original trampolines) ----

    private byte[] transformMixin(byte[] classfile, MixinMapping mapping, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfile);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        ShadowFieldPass.apply(node, mapping);
        OriginalPass.apply(node, mapping);
        AccessorPass.apply(node, mapping);
        InvokerPass.apply(node, mapping);
        ShadowMethodPass.apply(node, mapping);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
        node.accept(writer);
        return writer.toByteArray();
    }

}
