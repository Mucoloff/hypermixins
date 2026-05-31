package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Implements;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reads {@code @Implements({ I.class, J.class })} from the mixin class and appends each
 * interface's internal name to the target class's {@code interfaces} list. Idempotent: an
 * interface already in {@code node.interfaces} is skipped. No descriptor table is needed
 * because the annotation is read from the mixin {@code Class<?>} reflectively at transform
 * time.
 */
final class ImplementsPass {

    private ImplementsPass() {}

    static void apply(ClassNode node, MixinMapping mapping) {
        Implements ann = mapping.getMixinClass().getAnnotation(Implements.class);
        if (ann == null) return;
        for (Class<?> iface : ann.value()) {
            if (!iface.isInterface()) {
                throw new IllegalStateException(
                    "@Implements on " + mapping.getMixinClass().getName()
                    + " lists a non-interface type: " + iface.getName());
            }
            String internal = Type.getInternalName(iface);
            if (node.interfaces == null) continue;
            if (node.interfaces.contains(internal)) continue;
            node.interfaces.add(internal);
        }
    }
}
