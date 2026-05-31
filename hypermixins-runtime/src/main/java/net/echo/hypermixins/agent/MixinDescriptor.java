package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    public record WrapConditionEntry(String targetMethod, String invokeDesc, int index,
                                     String handlerName, String handlerDesc) {}
    public record WrapOperationEntry(String targetMethod, String invokeDesc, int index,
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
    private final List<WrapConditionEntry> wrapConditions;
    private final List<WrapOperationEntry> wrapOperations;
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
        List<WrapConditionEntry> wrapConditions,
        List<WrapOperationEntry> wrapOperations,
        Map<String, String[]> synthetics
    ) {
        this(mixinClass, targetClass, overwrites, originals, redirects, injects, injectLocals,
            injectShifts, shadows, shadowFields, shadowStaticFields, modifyReturnValues,
            accessors, invokers, modifyConstants, modifyArgs, modifyExpressionValues,
            modifyArgsAll, modifyReceivers, wrapConditions, wrapOperations, synthetics, Set.of(), Set.of());
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
        List<WrapConditionEntry> wrapConditions,
        List<WrapOperationEntry> wrapOperations,
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
        this.wrapConditions = List.copyOf(wrapConditions);
        this.wrapOperations = List.copyOf(wrapOperations);
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
    public List<WrapConditionEntry> wrapConditions() { return wrapConditions; }
    public List<WrapOperationEntry> wrapOperations() { return wrapOperations; }
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
        MixinDescriptor fresh = DescriptorReader.read(mixinClass);
        MixinDescriptor previous = CACHE.putIfAbsent(mixinClass, fresh);
        return previous != null ? previous : fresh;
    }

    /** Bypass for tests / hot-reload that need a fresh descriptor instance. */
    public static void invalidateCache(Class<?> mixinClass) {
        CACHE.remove(mixinClass);
    }


    /**
     * Builds the descriptor by reflecting on the mixin class's annotations. Used as a fallback
     * when the KSP-generated {@code $$Descriptor} is unavailable (e.g., source-only fixtures
     * in test code). Validates the same rules the processor enforces at compile time.
     */
    public static MixinDescriptor fromAnnotations(Class<?> mixinClass) {
        return AnnotationDescriptorReader.read(mixinClass);
    }

    /** Package-private factory used by [AnnotationDescriptorReader] — preserves probed maps. */
    static MixinDescriptor buildWithMaps(
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
        List<WrapConditionEntry> wrapConditions,
        List<WrapOperationEntry> wrapOperations,
        Map<String, String[]> synthetics,
        Set<String> staticTargetMethods,
        Set<String> privateShadowTargets
    ) {
        return new MixinDescriptor(mixinClass, targetClass, overwrites, originals, redirects,
            injects, injectLocals, injectShifts, shadows, shadowFields, shadowStaticFields,
            modifyReturnValues, accessors, invokers, modifyConstants, modifyArgs,
            modifyExpressionValues, modifyArgsAll, modifyReceivers, wrapConditions, wrapOperations, synthetics,
            staticTargetMethods, privateShadowTargets);
    }

    /** Package-private factory used by [DescriptorReader] (no static-map population). */
    static MixinDescriptor build(
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
        List<WrapConditionEntry> wrapConditions,
        List<WrapOperationEntry> wrapOperations,
        Map<String, String[]> synthetics
    ) {
        return new MixinDescriptor(mixinClass, targetClass, overwrites, originals, redirects,
            injects, injectLocals, injectShifts, shadows, shadowFields, shadowStaticFields,
            modifyReturnValues, accessors, invokers, modifyConstants, modifyArgs,
            modifyExpressionValues, modifyArgsAll, modifyReceivers, wrapConditions, wrapOperations, synthetics);
    }

    static MixinDescriptor withTargetMaps(
        MixinDescriptor base, List<String[]> staticRows, List<String[]> privateRows
    ) {
        if (staticRows.isEmpty() && privateRows.isEmpty()) return base;
        Set<String> stat = new HashSet<>();
        for (String[] r : staticRows) stat.add(r[0] + r[1]);
        Set<String> priv = new HashSet<>();
        for (String[] r : privateRows) priv.add(r[0] + r[1]);
        return new MixinDescriptor(base.mixinClass, base.targetClass,
            base.overwrites, base.originals, base.redirects, base.injects, base.injectLocals,
            base.injectShifts, base.shadows, base.shadowFields, base.shadowStaticFields, base.modifyReturnValues, base.accessors, base.invokers, base.modifyConstants, base.modifyArgs, base.modifyExpressionValues, base.modifyArgsAll, base.modifyReceivers, base.wrapConditions, base.wrapOperations, base.synthetics, stat, priv);
    }

}
