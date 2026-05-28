package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.*;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public class MixinMapping {

    private final Class<?> mixinClass;
    private final String targetClass;
    private final Map<String, Method> overwrites;
    private final Map<String, String> originals;
    private final List<RedirectMapping> redirects;
    private final Map<String, List<InjectMapping>> injects;

    public MixinMapping(Class<?> mixinClass) {
        this.mixinClass = mixinClass;

        Mixin mixin = mixinClass.getAnnotation(Mixin.class);
        if (mixin == null) throw new IllegalArgumentException("Missing @Mixin on " + mixinClass);
        if (mixin.value().isEmpty()) throw new IllegalArgumentException("@Mixin#value() is empty on " + mixinClass);

        this.targetClass = mixin.value();
        this.overwrites = new HashMap<>();
        this.originals = new HashMap<>();
        this.redirects = new ArrayList<>();
        this.injects = new HashMap<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Original.class)) {
                handleOriginal(method);
            } else if (method.isAnnotationPresent(Overwrite.class)) {
                handleOverwrite(method, mixin);
            } else if (method.isAnnotationPresent(Redirect.class)) {
                handleRedirect(method);
            } else if (method.isAnnotationPresent(Inject.class)) {
                handleInject(method);
            }
        }
    }

    public Class<?> getMixinClass() { return mixinClass; }
    public String getTargetClass() { return targetClass; }
    public Map<String, Method> getOverwrites() { return Collections.unmodifiableMap(overwrites); }
    public Map<String, String> getOriginals() { return Collections.unmodifiableMap(originals); }
    public List<RedirectMapping> getRedirects() { return Collections.unmodifiableList(redirects); }
    public Map<String, List<InjectMapping>> getInjects() { return Collections.unmodifiableMap(injects); }

    private void handleOriginal(Method method) {
        Original original = method.getAnnotation(Original.class);
        if (original.value().isEmpty()) {
            throw new IllegalArgumentException("@Original#value() is empty on " + method);
        }
        String mixinKey = method.getName() + Type.getMethodDescriptor(method);
        originals.put(mixinKey, original.value());
    }

    private void handleOverwrite(Method method, Mixin mixin) {
        Overwrite overwrite = method.getAnnotation(Overwrite.class);
        if (overwrite.value().isEmpty()) {
            throw new IllegalArgumentException("@Overwrite#value() is empty on " + method);
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("@Overwrite on static method not supported: " + method);
        }

        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            throw new IllegalArgumentException("@Overwrite method missing 'Object self' first param: " + method);
        }
        if (params[0] != Object.class) {
            throw new IllegalArgumentException(
                "@Overwrite first parameter must be Object (use cast inside body), found: " + params[0] + " on " + method
            );
        }
        for (Class<?> p : params) {
            if (p.getName().equals(mixin.value())) {
                throw new IllegalArgumentException(
                    "@Overwrite parameters cannot reference the target class directly; use Object self. Method: " + method
                );
            }
        }

        String key = overwrite.value() + targetDescriptorOf(method);
        if (overwrites.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate @Overwrite for key '" + key + "' in " + mixinClass);
        }
        overwrites.put(key, method);
    }

    private void handleRedirect(Method method) {
        Redirect redirect = method.getAnnotation(Redirect.class);
        At at = redirect.at();

        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("@Redirect method must be static: " + method);
        }
        if (at.desc().isEmpty()) {
            throw new IllegalArgumentException("@At#desc() is empty on @Redirect " + method);
        }
        if (redirect.method().isEmpty()) {
            throw new IllegalArgumentException("@Redirect#method() is empty on " + method);
        }
        if (at.index() < 0) {
            throw new IllegalArgumentException("@At#index() is negative on @Redirect " + method);
        }

        int paren = at.desc().indexOf('(');
        if (paren == -1) {
            throw new IllegalArgumentException("@At#desc() missing '(' (must include descriptor): " + at.desc());
        }

        String invokeSignature = at.desc().substring(paren);
        String handlerDesc = Type.getMethodDescriptor(method);

        if (!handlerDesc.equals(invokeSignature)) {
            throw new IllegalArgumentException(
                "Redirect handler signature mismatch on " + method +
                "\n  expected: " + invokeSignature +
                "\n  found:    " + handlerDesc
            );
        }

        redirects.add(new RedirectMapping(redirect.method(), at.desc(), at.index(), at.call(), method));
    }

    private void handleInject(Method method) {
        Inject inject = method.getAnnotation(Inject.class);
        if (inject.method().isEmpty()) {
            throw new IllegalArgumentException("@Inject#method() is empty on " + method);
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("@Inject method must be non-static: " + method);
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 || params[0] != Object.class) {
            throw new IllegalArgumentException("@Inject first parameter must be Object self on " + method);
        }
        boolean cancellable = inject.cancellable() || method.isAnnotationPresent(Cancellable.class);
        boolean returnable = false;
        if (cancellable) {
            Class<?> last = params[params.length - 1];
            if (last != CallbackInfo.class && last != CallbackInfoReturnable.class) {
                throw new IllegalArgumentException(
                    "@Inject with cancellable=true requires CallbackInfo or CallbackInfoReturnable as last param on " + method);
            }
            returnable = (last == CallbackInfoReturnable.class);
        }
        At at = inject.at();
        At.Point point = at.point();
        if (point != At.Point.HEAD && point != At.Point.RETURN && point != At.Point.TAIL) {
            throw new IllegalArgumentException("@Inject point " + point + " not supported yet (use HEAD/RETURN/TAIL): " + method);
        }
        InjectMapping mapping = new InjectMapping(
            inject.method(), point, at.index(), at.desc(), cancellable, returnable, method);
        injects.computeIfAbsent(inject.method(), k -> new ArrayList<>()).add(mapping);
    }

    /** Descriptor for the target method derived from a mixin @Overwrite handler (drops first Object self param). */
    static String targetDescriptorOf(Method mixinMethod) {
        Type returnType = Type.getReturnType(mixinMethod);
        Type[] args = Type.getArgumentTypes(mixinMethod);
        Type[] targetArgs = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        return Type.getMethodDescriptor(returnType, targetArgs);
    }
}
