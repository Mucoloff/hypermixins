package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;
import net.echo.hypermixins.annotations.Cancellable;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Redirect;
import net.echo.hypermixins.annotations.Shadow;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /**
     * One row per {@code @Local} handler parameter.
     * <ul>
     *   <li>{@code slot >= 0}: ALOAD/ILOAD straight from the literal slot index.</li>
     *   <li>{@code slot &lt; 0} and {@code ordinal &gt;= 0}: resolve the {@code ordinal}-th live
     *       local of matching type at the injection point.</li>
     *   <li>{@code slot &lt; 0} and {@code ordinal &lt; 0}: bare {@code @Local} — pick the
     *       single live local of matching type, fail at transform time if ambiguous.</li>
     * </ul>
     */
    public record InjectLocalEntry(String handlerName, String handlerDesc, int paramIndex,
                                   int slot, int ordinal, boolean argsOnly) {}
    public record ShadowEntry(String handlerName, String handlerDesc, String targetName) {}
    public record ShadowFieldEntry(String mixinFieldName, String fieldDesc, String targetFieldName) {}
    public record ModifyReturnValueEntry(String targetMethod, String invokeDesc, int index,
                                         String handlerName, String handlerDesc) {}
    public record AccessorEntry(String handlerName, String handlerDesc, String kind, String targetField) {}
    public record InvokerEntry(String handlerName, String handlerDesc, String targetName) {}
    public record ModifyConstantEntry(String targetMethod, String type, String value, int index,
                                      String handlerName, String handlerDesc) {}
    public record ModifyArgEntry(String targetMethod, String invokeDesc, int argIndex,
                                 String handlerName, String handlerDesc) {}
    public record ModifyExpressionValueEntry(String targetMethod, At.Point point, String atDesc,
                                             int index, String handlerName, String handlerDesc) {}
    public record ModifyArgsEntry(String targetMethod, String invokeDesc,
                                  String handlerName, String handlerDesc) {}
    public record ModifyReceiverEntry(String targetMethod, String invokeDesc,
                                      String handlerName, String handlerDesc) {}

    private static final ConcurrentHashMap<Class<?>, MixinDescriptor> CACHE = new ConcurrentHashMap<>();

    private final Class<?> mixinClass;
    private final String targetClass;
    private final List<OverwriteEntry> overwrites;
    private final List<OriginalEntry> originals;
    private final List<RedirectEntry> redirects;
    private final List<InjectEntry> injects;
    private final List<InjectLocalEntry> injectLocals;
    private final Map<String, At.Shift> injectShifts;
    private final List<ShadowEntry> shadows;
    private final List<ShadowFieldEntry> shadowFields;
    private final List<ShadowFieldEntry> shadowStaticFields;
    private final List<ModifyReturnValueEntry> modifyReturnValues;
    private final List<AccessorEntry> accessors;
    private final List<InvokerEntry> invokers;
    private final List<ModifyConstantEntry> modifyConstants;
    private final List<ModifyArgEntry> modifyArgs;
    private final List<ModifyExpressionValueEntry> modifyExpressionValues;
    private final List<ModifyArgsEntry> modifyArgsAll;
    private final List<ModifyReceiverEntry> modifyReceivers;
    private final Map<String, String[]> synthetics;
    /** Target-method-key → true when the method is static. Populated best-effort at build time. */
    private final Set<String> staticTargetMethods;
    /** Target-method-key (name+desc) for every target method seen as private. */
    private final Set<String> privateShadowTargets;

    private MixinDescriptor(
        Class<?> mixinClass, String targetClass,
        List<OverwriteEntry> overwrites, List<OriginalEntry> originals,
        List<RedirectEntry> redirects, List<InjectEntry> injects,
        List<InjectLocalEntry> injectLocals,
        Map<String, At.Shift> injectShifts,
        List<ShadowEntry> shadows,
        List<ShadowFieldEntry> shadowFields,
        List<ShadowFieldEntry> shadowStaticFields,
        List<ModifyReturnValueEntry> modifyReturnValues,
        List<AccessorEntry> accessors,
        List<InvokerEntry> invokers,
        List<ModifyConstantEntry> modifyConstants,
        List<ModifyArgEntry> modifyArgs,
        List<ModifyExpressionValueEntry> modifyExpressionValues,
        List<ModifyArgsEntry> modifyArgsAll,
        List<ModifyReceiverEntry> modifyReceivers,
        Map<String, String[]> synthetics
    ) {
        this.mixinClass  = mixinClass;
        this.targetClass = targetClass;
        this.overwrites  = List.copyOf(overwrites);
        this.originals   = List.copyOf(originals);
        this.redirects   = List.copyOf(redirects);
        this.injects     = List.copyOf(injects);
        this.injectLocals = List.copyOf(injectLocals);
        this.injectShifts = Collections.unmodifiableMap(new HashMap<>(injectShifts));
        this.shadows     = List.copyOf(shadows);
        this.shadowFields = List.copyOf(shadowFields);
        this.shadowStaticFields = List.copyOf(shadowStaticFields);
        this.modifyReturnValues = List.copyOf(modifyReturnValues);
        this.accessors = List.copyOf(accessors);
        this.invokers = List.copyOf(invokers);
        this.modifyConstants = List.copyOf(modifyConstants);
        this.modifyArgs = List.copyOf(modifyArgs);
        this.modifyExpressionValues = List.copyOf(modifyExpressionValues);
        this.modifyArgsAll = List.copyOf(modifyArgsAll);
        this.modifyReceivers = List.copyOf(modifyReceivers);
        this.synthetics  = Collections.unmodifiableMap(new HashMap<>(synthetics));
        this.staticTargetMethods = Set.of();
        this.privateShadowTargets = Set.of();
    }

    private MixinDescriptor(
        Class<?> mixinClass, String targetClass,
        List<OverwriteEntry> overwrites, List<OriginalEntry> originals,
        List<RedirectEntry> redirects, List<InjectEntry> injects,
        List<InjectLocalEntry> injectLocals,
        Map<String, At.Shift> injectShifts,
        List<ShadowEntry> shadows,
        List<ShadowFieldEntry> shadowFields,
        List<ShadowFieldEntry> shadowStaticFields,
        List<ModifyReturnValueEntry> modifyReturnValues,
        List<AccessorEntry> accessors,
        List<InvokerEntry> invokers,
        List<ModifyConstantEntry> modifyConstants,
        List<ModifyArgEntry> modifyArgs,
        List<ModifyExpressionValueEntry> modifyExpressionValues,
        List<ModifyArgsEntry> modifyArgsAll,
        List<ModifyReceiverEntry> modifyReceivers,
        Map<String, String[]> synthetics,
        Set<String> staticTargetMethods,
        Set<String> privateShadowTargets
    ) {
        this.mixinClass  = mixinClass;
        this.targetClass = targetClass;
        this.overwrites  = List.copyOf(overwrites);
        this.originals   = List.copyOf(originals);
        this.redirects   = List.copyOf(redirects);
        this.injects     = List.copyOf(injects);
        this.injectLocals = List.copyOf(injectLocals);
        this.injectShifts = Collections.unmodifiableMap(new HashMap<>(injectShifts));
        this.shadows     = List.copyOf(shadows);
        this.shadowFields = List.copyOf(shadowFields);
        this.shadowStaticFields = List.copyOf(shadowStaticFields);
        this.modifyReturnValues = List.copyOf(modifyReturnValues);
        this.accessors = List.copyOf(accessors);
        this.invokers = List.copyOf(invokers);
        this.modifyConstants = List.copyOf(modifyConstants);
        this.modifyArgs = List.copyOf(modifyArgs);
        this.modifyExpressionValues = List.copyOf(modifyExpressionValues);
        this.modifyArgsAll = List.copyOf(modifyArgsAll);
        this.modifyReceivers = List.copyOf(modifyReceivers);
        this.synthetics  = Collections.unmodifiableMap(new HashMap<>(synthetics));
        this.staticTargetMethods = Set.copyOf(staticTargetMethods);
        this.privateShadowTargets = Set.copyOf(privateShadowTargets);
    }

    public Class<?> mixinClass() { return mixinClass; }
    /** Internal name form: {@code "owner/Internal"}. */
    public String targetClass() { return targetClass; }
    public List<OverwriteEntry> overwrites() { return overwrites; }
    public List<OriginalEntry>  originals()  { return originals; }
    public List<RedirectEntry>  redirects()  { return redirects; }
    public List<InjectEntry>    injects()    { return injects; }
    public List<InjectLocalEntry> injectLocals() { return injectLocals; }
    public Map<String, At.Shift> injectShifts() { return injectShifts; }
    public List<ShadowEntry>    shadows()    { return shadows; }
    public List<ShadowFieldEntry> shadowFields() { return shadowFields; }
    public List<ShadowFieldEntry> shadowStaticFields() { return shadowStaticFields; }
    public List<ModifyReturnValueEntry> modifyReturnValues() { return modifyReturnValues; }
    public List<AccessorEntry> accessors() { return accessors; }
    public List<InvokerEntry> invokers() { return invokers; }
    public List<ModifyConstantEntry> modifyConstants() { return modifyConstants; }
    public List<ModifyArgEntry> modifyArgs() { return modifyArgs; }
    public List<ModifyExpressionValueEntry> modifyExpressionValues() { return modifyExpressionValues; }
    public List<ModifyArgsEntry> modifyArgsAll() { return modifyArgsAll; }
    public List<ModifyReceiverEntry> modifyReceivers() { return modifyReceivers; }
    /** Map {@code targetName+targetDesc → [mangledOriginalName, dispatchName]}. */
    public Map<String, String[]> synthetics() { return synthetics; }

    /**
     * Whether the target method {@code name + desc} is static. Best-effort: returns
     * {@code false} if the target class cannot be resolved at build time.
     */
    public boolean isStaticTargetMethod(String name, String desc) {
        return staticTargetMethods.contains(name + desc);
    }

    /** Whether the target method {@code name + desc} is private — drives private-target shadow trampoline gen. */
    public boolean isPrivateShadowTarget(String name, String desc) {
        return privateShadowTargets.contains(name + desc);
    }

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
            List<String[]> injectLocalRows = invokeStringListOrEmpty(lookup, desc, "injectCaptureLocals");
            List<String[]> injectShiftRows = invokeStringListOrEmpty(lookup, desc, "injectShifts");
            List<String[]> shadowRows    = invokeStringListOrEmpty(lookup, desc, "shadowEntries");
            List<String[]> shadowFieldRows = invokeStringListOrEmpty(lookup, desc, "shadowFieldEntries");
            List<String[]> shadowStaticFieldRows = invokeStringListOrEmpty(lookup, desc, "shadowStaticFieldEntries");
            List<String[]> modifyRvRows = invokeStringListOrEmpty(lookup, desc, "modifyReturnValueEntries");
            List<String[]> accessorRows = invokeStringListOrEmpty(lookup, desc, "accessorEntries");
            List<String[]> invokerRows = invokeStringListOrEmpty(lookup, desc, "invokerEntries");
            List<String[]> modifyConstRows = invokeStringListOrEmpty(lookup, desc, "modifyConstantEntries");
            List<String[]> modifyArgRows = invokeStringListOrEmpty(lookup, desc, "modifyArgEntries");
            List<String[]> modifyExprRows = invokeStringListOrEmpty(lookup, desc, "modifyExpressionValueEntries");
            List<String[]> modifyArgsRows = invokeStringListOrEmpty(lookup, desc, "modifyArgsEntries");
            List<String[]> modifyRecvRows = invokeStringListOrEmpty(lookup, desc, "modifyReceiverEntries");
            List<String[]> staticTargetRows = invokeStringListOrEmpty(lookup, desc, "staticTargetMethods");
            List<String[]> privateShadowRows = invokeStringListOrEmpty(lookup, desc, "privateShadowTargetMethods");
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

            List<InjectLocalEntry> injLocals = new ArrayList<>(injectLocalRows.size());
            for (String[] r : injectLocalRows) {
                int ord = r.length >= 5 ? Integer.parseInt(r[4]) : -1;
                boolean argsOnly = r.length >= 6 && Boolean.parseBoolean(r[5]);
                injLocals.add(new InjectLocalEntry(r[0], r[1], Integer.parseInt(r[2]), Integer.parseInt(r[3]), ord, argsOnly));
            }

            Map<String, At.Shift> injShifts = new HashMap<>();
            for (String[] r : injectShiftRows) injShifts.put(r[0] + r[1], At.Shift.valueOf(r[2]));

            List<ShadowEntry> shads = new ArrayList<>(shadowRows.size());
            for (String[] r : shadowRows) shads.add(new ShadowEntry(r[0], r[1], r[2]));

            List<ShadowFieldEntry> shadFields = new ArrayList<>(shadowFieldRows.size());
            for (String[] r : shadowFieldRows) shadFields.add(new ShadowFieldEntry(r[0], r[1], r[2]));

            List<ShadowFieldEntry> shadStaticFields = new ArrayList<>(shadowStaticFieldRows.size());
            for (String[] r : shadowStaticFieldRows) shadStaticFields.add(new ShadowFieldEntry(r[0], r[1], r[2]));

            List<ModifyReturnValueEntry> mrvs = new ArrayList<>(modifyRvRows.size());
            for (String[] r : modifyRvRows) mrvs.add(new ModifyReturnValueEntry(
                r[0], r[1], Integer.parseInt(r[2]), r[3], r[4]));

            List<AccessorEntry> accs = new ArrayList<>(accessorRows.size());
            for (String[] r : accessorRows) accs.add(new AccessorEntry(r[0], r[1], r[2], r[3]));

            List<InvokerEntry> invs = new ArrayList<>(invokerRows.size());
            for (String[] r : invokerRows) invs.add(new InvokerEntry(r[0], r[1], r[2]));

            List<ModifyConstantEntry> mcs = new ArrayList<>(modifyConstRows.size());
            for (String[] r : modifyConstRows) mcs.add(new ModifyConstantEntry(
                r[0], r[1], r[2], Integer.parseInt(r[3]), r[4], r[5]));

            List<ModifyArgEntry> mas = new ArrayList<>(modifyArgRows.size());
            for (String[] r : modifyArgRows) mas.add(new ModifyArgEntry(
                r[0], r[1], Integer.parseInt(r[2]), r[3], r[4]));

            List<ModifyExpressionValueEntry> mxs = new ArrayList<>(modifyExprRows.size());
            for (String[] r : modifyExprRows) mxs.add(new ModifyExpressionValueEntry(
                r[0], At.Point.valueOf(r[1]), r[2], Integer.parseInt(r[3]), r[4], r[5]));

            List<ModifyArgsEntry> mxa = new ArrayList<>(modifyArgsRows.size());
            for (String[] r : modifyArgsRows) mxa.add(new ModifyArgsEntry(r[0], r[1], r[2], r[3]));

            List<ModifyReceiverEntry> mxr = new ArrayList<>(modifyRecvRows.size());
            for (String[] r : modifyRecvRows) mxr.add(new ModifyReceiverEntry(r[0], r[1], r[2], r[3]));

            Map<String, String[]> synths = new LinkedHashMap<>();
            for (String[] r : syntheticRows) synths.put(r[0] + r[1], new String[]{r[2], r[3]});

            MixinDescriptor base = new MixinDescriptor(
                mixinClass, targetInternal, ows, orig, reds, injs, injLocals, injShifts, shads, shadFields, shadStaticFields, mrvs, accs, invs, mcs, mas, mxs, mxa, mxr, synths);
            return withTargetMaps(base, staticTargetRows, privateShadowRows);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to read generated $$Descriptor for " + mixinClass.getName(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> invokeStringList(MethodHandles.Lookup lookup, Class<?> desc, String name) throws Throwable {
        MethodHandle mh = lookup.findStatic(desc, name, MethodType.methodType(List.class));
        return (List<String[]>) mh.invoke();
    }

    /** Older $$Descriptor classes may not declare the requested table — return empty in that case. */
    private static List<String[]> invokeStringListOrEmpty(MethodHandles.Lookup lookup, Class<?> desc, String name) {
        try { return invokeStringList(lookup, desc, name); }
        catch (Throwable t) { return List.of(); }
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
        List<InjectLocalEntry> injectLocals = new ArrayList<>();
        List<ShadowEntry>    shadows    = new ArrayList<>();
        List<ShadowFieldEntry> shadowFields = new ArrayList<>();
        List<ShadowFieldEntry> shadowStaticFields = new ArrayList<>();
        Map<String, String[]> synths    = new LinkedHashMap<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            Overwrite ow  = method.getAnnotation(Overwrite.class);
            Original  or  = method.getAnnotation(Original.class);
            Redirect  re  = method.getAnnotation(Redirect.class);
            Inject    in  = method.getAnnotation(Inject.class);
            Shadow    sh  = method.getAnnotation(Shadow.class);

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
                String handlerDesc = Type.getMethodDescriptor(method);
                Call call = re.at().call();
                if (call == Call.GETFIELD || call == Call.PUTFIELD
                    || call == Call.GETSTATIC || call == Call.PUTSTATIC) {
                    int colon = re.at().desc().indexOf(":");
                    if (colon < 0) {
                        throw new IllegalArgumentException(
                            "@At#desc() for field redirect must be \"owner/Class.field:Ldesc;\" on " + method);
                    }
                    String fieldDesc = re.at().desc().substring(colon + 1);
                    String expected = expectedFieldHandlerDesc(call, fieldDesc);
                    if (!handlerDesc.equals(expected)) {
                        throw new IllegalArgumentException(
                            "Field-redirect handler signature mismatch on " + method
                                + ": expected " + expected + " got " + handlerDesc);
                    }
                } else {
                    int paren = re.at().desc().indexOf('(');
                    if (paren < 0) throw new IllegalArgumentException("@At#desc() missing '(' on " + method);
                    String invokeSig = re.at().desc().substring(paren);
                    if (!handlerDesc.equals(invokeSig))
                        throw new IllegalArgumentException("Redirect handler signature mismatch on " + method);
                }
                redirects.add(new RedirectEntry(re.method(), re.at().desc(), re.at().index(),
                    call, method.getName(), handlerDesc));
            }
            if (sh != null) {
                if (method.getParameterCount() == 0 || method.getParameterTypes()[0] != Object.class)
                    throw new IllegalArgumentException("@Shadow first param must be Object self on " + method);
                String targetName = resolveShadowName(method.getName(), sh.value(), sh.prefix());
                shadows.add(new ShadowEntry(method.getName(), Type.getMethodDescriptor(method), targetName));
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
                if ((point == At.Point.INVOKE || point == At.Point.FIELD
                    || point == At.Point.CONSTANT || point == At.Point.NEW)
                    && at.desc().isEmpty()) {
                    throw new IllegalArgumentException(
                        "@Inject point " + point + " requires @At#desc() on " + method);
                }
                String handlerDesc = Type.getMethodDescriptor(method);
                injects.add(new InjectEntry(in.method(), point, at.desc(), at.index(),
                    cancellable, returnable, method.getName(), handlerDesc));
                java.lang.annotation.Annotation[][] paramAnns = method.getParameterAnnotations();
                for (int pi = 0; pi < paramAnns.length; pi++) {
                    for (java.lang.annotation.Annotation a : paramAnns[pi]) {
                        if (a instanceof net.echo.hypermixins.annotations.Local lo) {
                            injectLocals.add(new InjectLocalEntry(
                                method.getName(), handlerDesc, pi, lo.index(), lo.ordinal(), lo.argsOnly()));
                        }
                    }
                }
            }
        }
        for (java.lang.reflect.Field f : mixinClass.getDeclaredFields()) {
            Shadow shFld = f.getAnnotation(Shadow.class);
            if (shFld == null) continue;
            String tname = resolveShadowName(f.getName(), shFld.value(), shFld.prefix());
            ShadowFieldEntry entry = new ShadowFieldEntry(f.getName(), Type.getDescriptor(f.getType()), tname);
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                shadowStaticFields.add(entry);
            } else {
                shadowFields.add(entry);
            }
        }

        Set<String> staticMap = ReflectionProbes.staticTargetMethods(
            mixinClass, targetInternal, originals, overwrites);
        Map<String, At.Shift> injectShifts = ReflectionCollectors.injectShifts(mixinClass);
        List<ModifyReturnValueEntry> mrvs = ReflectionCollectors.modifyReturnValues(mixinClass);
        List<AccessorEntry> accs = ReflectionCollectors.accessors(mixinClass);
        List<InvokerEntry> invs = ReflectionCollectors.invokers(mixinClass);
        List<ModifyConstantEntry> mcs = ReflectionCollectors.modifyConstants(mixinClass);
        List<ModifyArgEntry> mas = ReflectionCollectors.modifyArgs(mixinClass);
        List<ModifyExpressionValueEntry> mxs = ReflectionCollectors.modifyExpressionValues(mixinClass);
        List<ModifyArgsEntry> mxa = ReflectionCollectors.modifyArgsAll(mixinClass);
        List<ModifyReceiverEntry> mxr = ReflectionCollectors.modifyReceivers(mixinClass);
        Set<String> privateShadowMap = ReflectionProbes.privateShadowTargets(mixinClass, targetInternal, shadows, invs);
        return new MixinDescriptor(mixinClass, targetInternal,
            overwrites, originals, redirects, injects, injectLocals, injectShifts,
            shadows, shadowFields, shadowStaticFields, mrvs, accs, invs, mcs, mas, mxs, mxa, mxr, synths, staticMap, privateShadowMap);
    }

    private static MixinDescriptor withTargetMaps(
        MixinDescriptor base, List<String[]> staticRows, List<String[]> privateRows
    ) {
        if (staticRows.isEmpty() && privateRows.isEmpty()) return base;
        Set<String> stat = new HashSet<>();
        for (String[] r : staticRows) stat.add(r[0] + r[1]);
        Set<String> priv = new HashSet<>();
        for (String[] r : privateRows) priv.add(r[0] + r[1]);
        return new MixinDescriptor(base.mixinClass, base.targetClass,
            base.overwrites, base.originals, base.redirects, base.injects, base.injectLocals,
            base.injectShifts, base.shadows, base.shadowFields, base.shadowStaticFields, base.modifyReturnValues, base.accessors, base.invokers, base.modifyConstants, base.modifyArgs, base.modifyExpressionValues, base.modifyArgsAll, base.modifyReceivers, base.synthetics, stat, priv);
    }

    private static String resolveShadowName(String simpleName, String value, String prefix) {
        if (!value.isBlank()) return value;
        if (!prefix.isEmpty() && simpleName.startsWith(prefix)) return simpleName.substring(prefix.length());
        return simpleName;
    }

    /**
     * Best-effort detection of static target methods. Tries to load the target class and
     * resolve each target method that an {@code @Original} or {@code @Overwrite} refers to.
     * Failures are tolerated — the call site falls back to the instance dispatch path.
     */
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

    /**
     * Handler descriptor expected for a field-redirect based on opcode + the field's own desc.
     * GETFIELD:  {@code (Lowner;)Ldesc;}
     * PUTFIELD:  {@code (Lowner;Ldesc;)V}  — but we model the owner as Object to mirror @Redirect's self
     * GETSTATIC: {@code ()Ldesc;}
     * PUTSTATIC: {@code (Ldesc;)V}
     */
    private static String expectedFieldHandlerDesc(Call call, String fieldDesc) {
        return switch (call) {
            case GETFIELD -> "(Ljava/lang/Object;)" + fieldDesc;
            case PUTFIELD -> "(Ljava/lang/Object;" + fieldDesc + ")V";
            case GETSTATIC -> "()" + fieldDesc;
            case PUTSTATIC -> "(" + fieldDesc + ")V";
            default -> throw new IllegalStateException("unreachable: " + call);
        };
    }

    private static String targetDescriptorOf(Method mixinMethod) {
        Type returnType = Type.getReturnType(mixinMethod);
        Type[] args = Type.getArgumentTypes(mixinMethod);
        Type[] targetArgs = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        return Type.getMethodDescriptor(returnType, targetArgs);
    }
}
