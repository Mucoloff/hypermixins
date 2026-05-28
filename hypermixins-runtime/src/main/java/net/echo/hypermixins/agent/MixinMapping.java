package net.echo.hypermixins.agent;

import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter that exposes a {@link MixinDescriptor} through the legacy API expected by
 * {@link MixinTransformer}. The descriptor is loaded once (preferring the KSP-generated
 * {@code $$Descriptor} class, falling back to reflection on {@code @Mixin/@Overwrite/...}
 * annotations) and method handles are resolved by name + descriptor — no annotation walks.
 */
public class MixinMapping {

    private final MixinDescriptor descriptor;
    private final Map<String, Method> overwrites;
    private final Map<String, String> originals;
    private final List<RedirectMapping> redirects;
    private final Map<String, List<InjectMapping>> injects;

    public MixinMapping(Class<?> mixinClass) {
        this(MixinDescriptor.load(mixinClass));
    }

    public MixinMapping(MixinDescriptor descriptor) {
        this.descriptor = descriptor;
        Class<?> mixinClass = descriptor.mixinClass();

        Map<String, Method> ow = new HashMap<>();
        for (MixinDescriptor.OverwriteEntry e : descriptor.overwrites()) {
            Method handler = findMethodByDescriptor(mixinClass, e.handlerName(), e.handlerDesc());
            String key = e.targetName() + e.targetDesc();
            if (ow.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate @Overwrite for key '" + key + "' in " + mixinClass);
            }
            ow.put(key, handler);
        }
        this.overwrites = Collections.unmodifiableMap(ow);

        Map<String, String> orig = new HashMap<>();
        for (MixinDescriptor.OriginalEntry e : descriptor.originals()) {
            orig.put(e.handlerName() + e.handlerDesc(), e.targetName());
        }
        this.originals = Collections.unmodifiableMap(orig);

        List<RedirectMapping> red = new ArrayList<>();
        for (MixinDescriptor.RedirectEntry e : descriptor.redirects()) {
            Method handler = findMethodByDescriptor(mixinClass, e.handlerName(), e.handlerDesc());
            red.add(new RedirectMapping(e.targetMethod(), e.invokeDesc(), e.index(), e.call(), handler));
        }
        this.redirects = Collections.unmodifiableList(red);

        Map<String, List<InjectMapping>> inj = new HashMap<>();
        for (MixinDescriptor.InjectEntry e : descriptor.injects()) {
            Method handler = findMethodByDescriptor(mixinClass, e.handlerName(), e.handlerDesc());
            inj.computeIfAbsent(e.targetMethod(), k -> new ArrayList<>()).add(
                new InjectMapping(e.targetMethod(), e.point(), e.atIndex(), e.atDesc(),
                    e.cancellable(), e.returnable(), handler));
        }
        this.injects = Collections.unmodifiableMap(inj);
    }

    public Class<?> getMixinClass() { return descriptor.mixinClass(); }
    /** Fully qualified target class name (dotted form). */
    public String getTargetClass() { return descriptor.targetClass().replace('/', '.'); }
    public Map<String, Method> getOverwrites() { return overwrites; }
    public Map<String, String> getOriginals() { return originals; }
    public List<RedirectMapping> getRedirects() { return redirects; }
    public Map<String, List<InjectMapping>> getInjects() { return injects; }
    /** Underlying compile-time-baked view. */
    public MixinDescriptor descriptor() { return descriptor; }

    private static Method findMethodByDescriptor(Class<?> cls, String name, String desc) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return m;
        }
        throw new IllegalStateException("Mixin class " + cls.getName() +
            " has no method " + name + desc + " — class out of sync with descriptor");
    }
}
