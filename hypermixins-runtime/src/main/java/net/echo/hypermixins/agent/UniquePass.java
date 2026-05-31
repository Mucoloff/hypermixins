package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Unique;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Copies every static {@code @Unique} method declared on the mixin class into the target
 * class under a mangled name (
 * {@code __unique$<mixin-class-flattened>$<methodName>$<descriptor-hash>}). Lets a mixin's
 * {@code @Overwrite} body call helper methods declared on the same mixin without leaking the
 * mixin class itself onto every transformed target — the helpers travel with the target.
 *
 * <p>Restricted to static methods so the {@code this} slot does not need rewriting.
 */
final class UniquePass {

    private static final String UNIQUE_DESC = Type.getDescriptor(Unique.class);

    private UniquePass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        Class<?> mixinClass = mapping.getMixinClass();
        if (!hasAnyUnique(mixinClass)) return;
        ClassNode mixinNode = loadMixinNode(mixinClass);
        if (mixinNode == null) return;
        Set<String> existingKeys = new HashSet<>();
        for (MethodNode m : node.methods) existingKeys.add(m.name + m.desc);

        for (MethodNode m : mixinNode.methods) {
            if (!isAnnotatedUnique(m)) continue;
            if ((m.access & Opcodes.ACC_STATIC) == 0) {
                throw new IllegalStateException(
                    "@Unique requires the method to be static for now: "
                    + mixinClass.getName() + "." + m.name + m.desc);
            }
            String mangled = mangledUniqueName(mixinClass, m.name, m.desc);
            String key = mangled + m.desc;
            if (!existingKeys.add(key)) continue;
            MethodNode copy = cloneAsPublicStaticSynthetic(m, mangled);
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

    private static MethodNode cloneAsPublicStaticSynthetic(MethodNode original, String newName) {
        int access = (original.access & ~Modifier.PRIVATE & ~Modifier.PROTECTED)
            | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        MethodNode copy = new MethodNode(access, newName, original.desc, original.signature,
            original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        original.accept(copy);
        // The cloned node carries the original method's name in its `name` field after accept();
        // reset to the mangled name so the call site can resolve it.
        copy.name = newName;
        copy.access = access;
        return copy;
    }
}
