package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;
import net.echo.hypermixins.annotations.Cancellable;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Redirect;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile-time-baked view of a mixin class. Built once per {@code @Mixin} class either from
 * the KSP-generated {@code <MixinFQN>$$Descriptor} (the zero-reflection production path) or
 * from runtime annotation reflection (legacy / test fallback).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MixinDescriptor d = MixinDescriptor.load(WorldMixin.class);
 * for (MixinDescriptor.OverwriteEntry e : d.overwrites()) {
 *     System.out.println(e.targetName() + e.targetDesc() + " -> " + e.handlerName());
 * }
 * String[] synth = d.synthetics().get("getPlayers()Ljava/util/List;");
 * // synth[0] = mangled __original$ name, synth[1] = __dispatch$ name
 * }</pre>
 *
 * Results are cached per {@code Class<?>}; the first call resolves the
 * {@code $$Descriptor} and subsequent calls return the cached instance.
 * Use {@link #invalidateCache(Class)} after a class redefinition.
 * <p>
 * Once loaded, this object is immutable and shared across all registrations of the same mixin.
 *
 * <h2>Generated descriptor ABI</h2>
 * The KSP processor and this loader share a fixed column layout per entries() table. Changing
 * any row schema requires updating both sides in lock-step or runtime decoding will misalign.
 * <pre>
 *   overwriteEntries:  [targetName, targetDesc, handlerName, handlerDesc]
 *   originalEntries:   [handlerName, handlerDesc, targetName]
 *   redirectEntries:   [targetMethod, invokeDesc, index, call, handlerName, handlerDesc]
 *   injectEntries:     [targetMethod, point, atDesc, atIndex,
 *                       cancellable, returnable, handlerName, handlerDesc]
 *   syntheticNames:    [targetName, targetDesc, mangledOriginalName, dispatchName]
 * </pre>
 * Scalar columns are encoded as {@code Integer.toString} / {@code Boolean.toString}. Enum
 * columns ({@code call}, {@code point}) hold the unqualified enum constant name and are
 * decoded via {@code Enum.valueOf}.
 */
public final class MixinDescriptor {

    public record OverwriteEntry(String targetName, String targetDesc, String handlerName, String handlerDesc) {}
    public record OriginalEntry(String handlerName, String handlerDesc, String targetName) {}
    public record RedirectEntry(String targetMethod, String invokeDesc, int index, Call call,
                                String handlerName, String handlerDesc) {}
    public record InjectEntry(String targetMethod, At.Point point, String atDesc, int atIndex,
                              boolean cancellable, boolean returnable,
                              String handlerName, String handlerDesc) {}

    private static final ConcurrentHashMap<Class<?>, MixinDescriptor> CACHE = new ConcurrentHashMap<>();

    private final Class<?> mixinClass;
    private final String targetClass;
    private final List<OverwriteEntry> overwrites;
    private final List<OriginalEntry> originals;
    private final List<RedirectEntry> redirects;
    private final List<InjectEntry> injects;
    private final Map<String, String[]> synthetics;

    private MixinDescriptor(
        Class<?> mixinClass, String targetClass,
        List<OverwriteEntry> overwrites, List<OriginalEntry> originals,
        List<RedirectEntry> redirects, List<InjectEntry> injects,
        Map<String, String[]> synthetics
    ) {
        this.mixinClass  = mixinClass;
        this.targetClass = targetClass;
        this.overwrites  = List.copyOf(overwrites);
        this.originals   = List.copyOf(originals);
        this.redirects   = List.copyOf(redirects);
        this.injects     = List.copyOf(injects);
        this.synthetics  = Collections.unmodifiableMap(new HashMap<>(synthetics));
    }

    public Class<?> mixinClass() { return mixinClass; }
    /** Internal name form: {@code "owner/Internal"}. */
    public String targetClass() { return targetClass; }
    public List<OverwriteEntry> overwrites() { return overwrites; }
    public List<OriginalEntry>  originals()  { return originals; }
    public List<RedirectEntry>  redirects()  { return redirects; }
    public List<InjectEntry>    injects()    { return injects; }
    /** Map {@code targetName+targetDesc → [mangledOriginalName, dispatchName]}. */
    public Map<String, String[]> synthetics() { return synthetics; }

    /**
     * Loads the descriptor for {@code mixinClass} from its KSP-generated {@code $$Descriptor}.
     * Falls back to runtime annotation reflection ({@link #fromAnnotations}) if the descriptor
     * class is missing. The fallback supports source-only / hand-written mixins; production
     * builds should rely on the generated path for zero runtime reflection.
     */
    public static MixinDescriptor load(Class<?> mixinClass) {
        MixinDescriptor cached = CACHE.get(mixinClass);
        if (cached != null) return cached;
        MixinDescriptor fresh = loadUncached(mixinClass);
        MixinDescriptor previous = CACHE.putIfAbsent(mixinClass, fresh);
        return previous != null ? previous : fresh;
    }

    /** Bypass for tests / hot-reload that need a fresh descriptor instance. */
    public static void invalidateCache(Class<?> mixinClass) {
        CACHE.remove(mixinClass);
    }

    private static MixinDescriptor loadUncached(Class<?> mixinClass) {
        String descriptorFqn = mixinClass.getName() + "$$Descriptor";
        Class<?> desc;
        try {
            desc = Class.forName(descriptorFqn, true, mixinClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            return fromAnnotations(mixinClass);
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            String targetInternal = (String) lookup.findStatic(desc, "targetClass",
                MethodType.methodType(String.class)).invoke();

            List<String[]> overwriteRows = invokeStringList(lookup, desc, "overwriteEntries");
            List<String[]> originalRows  = invokeStringList(lookup, desc, "originalEntries");
            List<String[]> redirectRows  = invokeStringList(lookup, desc, "redirectEntries");
            List<String[]> injectRows    = invokeStringList(lookup, desc, "injectEntries");
            List<String[]> syntheticRows = invokeStringList(lookup, desc, "syntheticNames");

            List<OverwriteEntry> ows = new ArrayList<>(overwriteRows.size());
            for (String[] r : overwriteRows) ows.add(new OverwriteEntry(r[0], r[1], r[2], r[3]));

            List<OriginalEntry> orig = new ArrayList<>(originalRows.size());
            for (String[] r : originalRows) orig.add(new OriginalEntry(r[0], r[1], r[2]));

            List<RedirectEntry> reds = new ArrayList<>(redirectRows.size());
            for (String[] r : redirectRows) reds.add(new RedirectEntry(
                r[0], r[1], Integer.parseInt(r[2]), Call.valueOf(r[3]), r[4], r[5]));

            List<InjectEntry> injs = new ArrayList<>(injectRows.size());
            for (String[] r : injectRows) injs.add(new InjectEntry(
                r[0], At.Point.valueOf(r[1]), r[2], Integer.parseInt(r[3]),
                Boolean.parseBoolean(r[4]), Boolean.parseBoolean(r[5]), r[6], r[7]));

            Map<String, String[]> synths = new LinkedHashMap<>();
            for (String[] r : syntheticRows) synths.put(r[0] + r[1], new String[]{r[2], r[3]});

            return new MixinDescriptor(mixinClass, targetInternal, ows, orig, reds, injs, synths);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to read generated $$Descriptor for " + mixinClass.getName(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> invokeStringList(MethodHandles.Lookup lookup, Class<?> desc, String name) throws Throwable {
        MethodHandle mh = lookup.findStatic(desc, name, MethodType.methodType(List.class));
        return (List<String[]>) mh.invoke();
    }

    /**
     * Builds the descriptor by reflecting on the mixin class's annotations. Used as a fallback
     * when the KSP-generated {@code $$Descriptor} is unavailable (e.g., source-only fixtures
     * in test code). Validates the same rules the processor enforces at compile time.
     */
    public static MixinDescriptor fromAnnotations(Class<?> mixinClass) {
        Mixin mixin = mixinClass.getAnnotation(Mixin.class);
        if (mixin == null) throw new IllegalArgumentException("Missing @Mixin on " + mixinClass);
        if (mixin.value().isEmpty()) throw new IllegalArgumentException("@Mixin#value() is empty on " + mixinClass);
        String targetInternal = mixin.value().replace('.', '/');

        List<OverwriteEntry> overwrites = new ArrayList<>();
        List<OriginalEntry>  originals  = new ArrayList<>();
        List<RedirectEntry>  redirects  = new ArrayList<>();
        List<InjectEntry>    injects    = new ArrayList<>();
        Map<String, String[]> synths    = new LinkedHashMap<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            Overwrite ow  = method.getAnnotation(Overwrite.class);
            Original  or  = method.getAnnotation(Original.class);
            Redirect  re  = method.getAnnotation(Redirect.class);
            Inject    in  = method.getAnnotation(Inject.class);

            if (ow != null) {
                if (ow.value().isEmpty()) throw new IllegalArgumentException("@Overwrite#value() is empty on " + method);
                if (Modifier.isStatic(method.getModifiers()))
                    throw new IllegalArgumentException("@Overwrite must be non-static: " + method);
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != Object.class)
                    throw new IllegalArgumentException("@Overwrite first param must be Object self on " + method);
                for (Class<?> p : params) {
                    if (p.getName().equals(mixin.value()))
                        throw new IllegalArgumentException("@Overwrite param must not reference target directly: " + method);
                }
                String targetDesc = targetDescriptorOf(method);
                String handlerDesc = Type.getMethodDescriptor(method);
                overwrites.add(new OverwriteEntry(ow.value(), targetDesc, method.getName(), handlerDesc));
                String hash = sha1Hex16(targetDesc);
                synths.put(ow.value() + targetDesc, new String[]{
                    "__original$" + ow.value() + "$" + hash,
                    "__dispatch$" + ow.value() + "$" + hash
                });
            }
            if (or != null) {
                if (or.value().isEmpty()) throw new IllegalArgumentException("@Original#value() is empty on " + method);
                originals.add(new OriginalEntry(method.getName(), Type.getMethodDescriptor(method), or.value()));
            }
            if (re != null) {
                if (!Modifier.isStatic(method.getModifiers()))
                    throw new IllegalArgumentException("@Redirect must be static: " + method);
                if (re.method().isEmpty()) throw new IllegalArgumentException("@Redirect#method() empty on " + method);
                if (re.at().desc().isEmpty()) throw new IllegalArgumentException("@At#desc() empty on @Redirect " + method);
                int paren = re.at().desc().indexOf('(');
                if (paren < 0) throw new IllegalArgumentException("@At#desc() missing '(' on " + method);
                String invokeSig = re.at().desc().substring(paren);
                String handlerDesc = Type.getMethodDescriptor(method);
                if (!handlerDesc.equals(invokeSig))
                    throw new IllegalArgumentException("Redirect handler signature mismatch on " + method);
                redirects.add(new RedirectEntry(re.method(), re.at().desc(), re.at().index(),
                    re.at().call(), method.getName(), handlerDesc));
            }
            if (in != null) {
                if (in.method().isEmpty()) throw new IllegalArgumentException("@Inject#method() empty on " + method);
                if (Modifier.isStatic(method.getModifiers()))
                    throw new IllegalArgumentException("@Inject must be non-static: " + method);
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != Object.class)
                    throw new IllegalArgumentException("@Inject first param must be Object self on " + method);
                boolean cancellable = in.cancellable() || method.isAnnotationPresent(Cancellable.class);
                boolean returnable  = false;
                if (cancellable) {
                    Class<?> last = params[params.length - 1];
                    String simple = last.getSimpleName();
                    if (!simple.equals("CallbackInfo") && !simple.equals("CallbackInfoReturnable")) {
                        throw new IllegalArgumentException(
                            "@Inject cancellable=true requires CallbackInfo[Returnable] last param on " + method);
                    }
                    returnable = simple.equals("CallbackInfoReturnable");
                }
                At at = in.at();
                At.Point point = at.point();
                if ((point == At.Point.INVOKE || point == At.Point.FIELD || point == At.Point.CONSTANT)
                    && at.desc().isEmpty()) {
                    throw new IllegalArgumentException(
                        "@Inject point " + point + " requires @At#desc() on " + method);
                }
                injects.add(new InjectEntry(in.method(), point, at.desc(), at.index(),
                    cancellable, returnable, method.getName(), Type.getMethodDescriptor(method)));
            }
        }
        return new MixinDescriptor(mixinClass, targetInternal, overwrites, originals, redirects, injects, synths);
    }

    /** Inlined SHA-1/16-hex digest used only by the legacy {@link #fromAnnotations} path. */
    private static String sha1Hex16(String input) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String targetDescriptorOf(Method mixinMethod) {
        Type returnType = Type.getReturnType(mixinMethod);
        Type[] args = Type.getArgumentTypes(mixinMethod);
        Type[] targetArgs = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        return Type.getMethodDescriptor(returnType, targetArgs);
    }
}
