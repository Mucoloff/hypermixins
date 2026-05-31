package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Unique;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Copies every {@code @Unique} method declared on the mixin class into the target class.
 *
 * <p><b>Static</b> {@code @Unique}: copied as-is under the mangled name
 * {@code __unique$<mixin-flat>$<name>$<hash>}.
 *
 * <p><b>Instance</b> {@code @Unique}: copied as a public static synthetic with
 * {@code Object self} prepended (mirroring the {@code @Overwrite} dispatch convention). All
 * non-zero local slot references are shifted by {@code +1} to account for the prepended self
 * slot; {@code ALOAD 0} stays as is and now loads the target instance. The body must not
 * reference the mixin class itself (no {@code GETFIELD}/{@code PUTFIELD} on the mixin owner,
 * no {@code INVOKE} on the mixin owner): instance helpers are expected to be self-contained
 * (parameters, locals, target {@code self}). References that would break on the target class
 * are rejected with a clear error at transform time. Direct invocation from other mixin bodies
 * is out of scope this iteration — callers must INVOKESTATIC the mangled synthetic with the
 * target instance as the first argument.
 */
final class UniquePass {

    private static final String UNIQUE_DESC = Type.getDescriptor(Unique.class);

    private UniquePass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        Class<?> mixinClass = mapping.getMixinClass();
        if (!hasAnyUnique(mixinClass)) return;
        ClassNode mixinNode = loadMixinNode(mixinClass);
        if (mixinNode == null) return;
        String mixinInternal = mixinNode.name;
        Set<String> existingKeys = new HashSet<>();
        for (MethodNode m : node.methods) existingKeys.add(m.name + m.desc);

        for (MethodNode m : mixinNode.methods) {
            if (!isAnnotatedUnique(m)) continue;
            if ((m.access & Opcodes.ACC_STATIC) != 0) {
                String mangled = mangledUniqueName(mixinClass, m.name, m.desc);
                String key = mangled + m.desc;
                if (!existingKeys.add(key)) continue;
                MethodNode copy = cloneAsPublicStaticSynthetic(m, mangled, m.desc);
                node.methods.add(copy);
                continue;
            }
            String newDesc = prependObjectSelf(m.desc);
            String mangled = mangledUniqueName(mixinClass, m.name, newDesc);
            String key = mangled + newDesc;
            if (!existingKeys.add(key)) continue;
            MethodNode copy = cloneAsPublicStaticSynthetic(m, mangled, newDesc);
            rewriteInstanceUniqueBody(copy, mixinInternal, mixinClass);
            node.methods.add(copy);
        }
    }

    /** Public so OverwritePass / other mixin-body rewriters can route static calls here later. */
    static String mangledUniqueName(Class<?> mixinClass, String methodName, String desc) {
        String flat = mixinClass.getName().replace('.', '$');
        return "__unique$" + flat + "$" + methodName + "$" + NameHash.hashHex(desc);
    }

    private static boolean hasAnyUnique(Class<?> mixinClass) {
        for (Method m : mixinClass.getDeclaredMethods()) {
            if (m.getAnnotation(Unique.class) != null) return true;
        }
        return false;
    }

    private static boolean isAnnotatedUnique(MethodNode m) {
        if (m.visibleAnnotations == null) return false;
        for (AnnotationNode ann : m.visibleAnnotations) {
            if (UNIQUE_DESC.equals(ann.desc)) return true;
        }
        return false;
    }

    private static ClassNode loadMixinNode(Class<?> mixinClass) {
        String resource = mixinClass.getName().replace('.', '/') + ".class";
        ClassLoader cl = mixinClass.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(is.readAllBytes()).accept(node, 0);
            return node;
        } catch (IOException e) {
            return null;
        }
    }

    private static MethodNode cloneAsPublicStaticSynthetic(MethodNode original, String newName, String newDesc) {
        int access = (original.access & ~Modifier.PRIVATE & ~Modifier.PROTECTED)
            | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        MethodNode copy = new MethodNode(access, newName, newDesc, original.signature,
            original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        original.accept(copy);
        // The cloned node carries the original method's name/desc in its fields after accept();
        // reset them so the call site can resolve the mangled signature.
        copy.name = newName;
        copy.desc = newDesc;
        copy.access = access;
        return copy;
    }

    private static String prependObjectSelf(String originalDesc) {
        return "(Ljava/lang/Object;" + originalDesc.substring(1);
    }

    /**
     * Walks the cloned body of an instance @Unique method and rejects every reference to the
     * mixin class itself (GETFIELD/PUTFIELD/INVOKE/CHECKCAST). The instance method's implicit
     * {@code this} already occupied slot 0; converting to a static method with {@code Object
     * self} prepended keeps slot 0 as the receiver and leaves every other slot in place — no
     * shift required. {@code maxLocals} is unchanged for the same reason.
     */
    private static void rewriteInstanceUniqueBody(MethodNode copy, String mixinInternal, Class<?> mixinClass) {
        for (AbstractInsnNode insn = copy.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && mixinInternal.equals(f.owner)) {
                throw new IllegalStateException(
                    "Instance @Unique helper " + mixinClass.getName() + "." + copy.name
                    + " accesses mixin field " + f.name + " — only self-contained instance helpers are"
                    + " supported. Move state to a @Shadow field on the target or pass it as a parameter.");
            }
            if (insn instanceof MethodInsnNode mi && mixinInternal.equals(mi.owner)) {
                throw new IllegalStateException(
                    "Instance @Unique helper " + mixinClass.getName() + "." + copy.name
                    + " calls mixin method " + mi.name + mi.desc
                    + " — only self-contained instance helpers are supported.");
            }
            if (insn instanceof TypeInsnNode ti && mixinInternal.equals(ti.desc)) {
                throw new IllegalStateException(
                    "Instance @Unique helper " + mixinClass.getName() + "." + copy.name
                    + " references mixin type via " + Integer.toHexString(ti.getOpcode())
                    + " — only self-contained instance helpers are supported.");
            }
        }
    }
}
