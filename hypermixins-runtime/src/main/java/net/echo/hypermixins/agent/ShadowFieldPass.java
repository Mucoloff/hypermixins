package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Final;
import net.echo.hypermixins.annotations.Mutable;
import net.echo.hypermixins.annotations.Shadow;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites {@code @Shadow} field accesses inside {@code @Overwrite} / {@code @Inject} handlers
 * so they read or write the target's field instead of the mixin's. Covers both instance
 * ({@code ALOAD 0; (GET|PUT)FIELD}) and static ({@code (GET|PUT)STATIC}) patterns.
 */
final class ShadowFieldPass {

    private ShadowFieldPass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        Map<String, MixinDescriptor.ShadowFieldEntry> shadowFieldsByKey = new HashMap<>();
        for (MixinDescriptor.ShadowFieldEntry sf : mapping.descriptor().shadowFields()) {
            shadowFieldsByKey.put(sf.mixinFieldName() + sf.fieldDesc(), sf);
        }
        Map<String, MixinDescriptor.ShadowFieldEntry> shadowStaticFieldsByKey = new HashMap<>();
        for (MixinDescriptor.ShadowFieldEntry sf : mapping.descriptor().shadowStaticFields()) {
            shadowStaticFieldsByKey.put(sf.mixinFieldName() + sf.fieldDesc(), sf);
        }
        if (shadowFieldsByKey.isEmpty() && shadowStaticFieldsByKey.isEmpty()) return;

        // Probe mixin class for @Final + @Mutable markers on shadow fields. @Final without @Mutable
        // bans PUTFIELD/PUTSTATIC rewrites — the target field is contractually frozen.
        Set<String> frozenKeys = collectFrozenShadowKeys(mapping.getMixinClass());

        String mixinInternal = node.name;
        String targetInternal = mapping.descriptor().targetClass();
        Set<String> handlerKeys = new HashSet<>();
        for (MixinDescriptor.OverwriteEntry oe : mapping.descriptor().overwrites())
            handlerKeys.add(oe.handlerName() + oe.handlerDesc());
        for (MixinDescriptor.InjectEntry ie : mapping.descriptor().injects())
            handlerKeys.add(ie.handlerName() + ie.handlerDesc());

        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") || method.name.equals("<clinit>")) continue;
            if (!handlerKeys.contains(method.name + method.desc)) continue;
            if (!shadowFieldsByKey.isEmpty()) {
                rewriteInstance(method, mixinInternal, targetInternal, shadowFieldsByKey, frozenKeys);
            }
            if (!shadowStaticFieldsByKey.isEmpty()) {
                rewriteStatic(method, mixinInternal, targetInternal, shadowStaticFieldsByKey, frozenKeys);
            }
        }
    }

    /** Returns mixinFieldName+fieldDesc keys for every @Shadow @Final field without @Mutable. */
    private static Set<String> collectFrozenShadowKeys(Class<?> mixinClass) {
        Set<String> frozen = new HashSet<>();
        for (Field f : mixinClass.getDeclaredFields()) {
            if (f.getAnnotation(Shadow.class) == null) continue;
            if (f.getAnnotation(Final.class) == null) continue;
            if (f.getAnnotation(Mutable.class) != null) continue;
            frozen.add(f.getName() + Type.getDescriptor(f.getType()));
        }
        return frozen;
    }

    /**
     * Rewrites {@code ALOAD 0; (GET|PUT)FIELD mixin.foo} → {@code ALOAD 1; CHECKCAST target;
     * (GET|PUT)FIELD target.foo}.
     */
    private static void rewriteInstance(
        MethodNode method, String mixinInternal, String targetInternal,
        Map<String, MixinDescriptor.ShadowFieldEntry> shadowFieldsByKey,
        Set<String> frozenKeys
    ) {
        if (method.instructions == null) return;
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            snapshot.add(insn);
        }
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof FieldInsnNode fi)) continue;
            if (!fi.owner.equals(mixinInternal)) continue;
            int op = fi.getOpcode();
            if (op != Opcodes.GETFIELD && op != Opcodes.PUTFIELD) continue;
            MixinDescriptor.ShadowFieldEntry shadow = shadowFieldsByKey.get(fi.name + fi.desc);
            if (shadow == null) continue;
            if (op == Opcodes.PUTFIELD && frozenKeys.contains(fi.name + fi.desc)) {
                throw new IllegalStateException(
                    "Cannot write to @Final @Shadow field " + mixinInternal + "." + fi.name
                    + " — add @Mutable to acknowledge mutability");
            }

            AbstractInsnNode ownerLoad = (op == Opcodes.GETFIELD)
                ? skipMeta(fi.getPrevious())
                : findEnclosingThisLoad(fi);
            if (ownerLoad == null) continue;
            if (!(ownerLoad.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) ownerLoad).var == 0)) continue;

            method.instructions.insertBefore(ownerLoad, new VarInsnNode(Opcodes.ALOAD, 1));
            method.instructions.insertBefore(ownerLoad, new TypeInsnNode(Opcodes.CHECKCAST, targetInternal));
            method.instructions.remove(ownerLoad);
            method.instructions.set(fi, new FieldInsnNode(op, targetInternal, shadow.targetFieldName(), shadow.fieldDesc()));
        }
    }

    /** Rewrites GETSTATIC/PUTSTATIC mixin.foo → GETSTATIC/PUTSTATIC target.foo. */
    private static void rewriteStatic(
        MethodNode method, String mixinInternal, String targetInternal,
        Map<String, MixinDescriptor.ShadowFieldEntry> shadowFieldsByKey,
        Set<String> frozenKeys
    ) {
        if (method.instructions == null) return;
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            snapshot.add(insn);
        }
        for (AbstractInsnNode insn : snapshot) {
            if (!(insn instanceof FieldInsnNode fi)) continue;
            if (!fi.owner.equals(mixinInternal)) continue;
            int op = fi.getOpcode();
            if (op != Opcodes.GETSTATIC && op != Opcodes.PUTSTATIC) continue;
            MixinDescriptor.ShadowFieldEntry shadow = shadowFieldsByKey.get(fi.name + fi.desc);
            if (shadow == null) continue;
            if (op == Opcodes.PUTSTATIC && frozenKeys.contains(fi.name + fi.desc)) {
                throw new IllegalStateException(
                    "Cannot write to @Final @Shadow static field " + mixinInternal + "." + fi.name
                    + " — add @Mutable to acknowledge mutability");
            }
            method.instructions.set(fi, new FieldInsnNode(op, targetInternal, shadow.targetFieldName(), shadow.fieldDesc()));
        }
    }

    private static AbstractInsnNode skipMeta(AbstractInsnNode n) {
        while (n != null && n.getOpcode() == -1) n = n.getPrevious();
        return n;
    }

    /**
     * Conservative scan back from a PUTFIELD to its receiver-loading {@code ALOAD 0}. Stops at
     * the nearest preceding {@code ALOAD 0}, trusting javac's straight-line {@code this.foo = expr}
     * emission.
     */
    private static AbstractInsnNode findEnclosingThisLoad(AbstractInsnNode putfield) {
        AbstractInsnNode n = putfield.getPrevious();
        while (n != null && !(n.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) n).var == 0)) {
            n = n.getPrevious();
        }
        return n;
    }
}
