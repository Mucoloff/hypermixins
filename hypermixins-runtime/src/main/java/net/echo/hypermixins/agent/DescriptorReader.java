package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a KSP-generated {@code <MixinFQN>$$Descriptor} class into a {@link MixinDescriptor}.
 * One row table per annotation kind, each accessed via {@link MethodHandles} on the generated
 * {@code public static List<String[]> *Entries()} accessors. Unknown / older descriptor classes
 * that lack a given table degrade gracefully via {@link #invokeStringListOrEmpty}.
 */
final class DescriptorReader {

    private DescriptorReader() {}

    /**
     * Tries to load the generated descriptor for {@code mixinClass} and decode every row table
     * into {@link MixinDescriptor} entries. Falls back to
     * {@link MixinDescriptor#fromAnnotations} when the {@code $$Descriptor} class is missing.
     */
    static MixinDescriptor read(Class<?> mixinClass) {
        String descriptorFqn = mixinClass.getName() + "$$Descriptor";
        Class<?> desc;
        try {
            desc = Class.forName(descriptorFqn, true, mixinClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            return MixinDescriptor.fromAnnotations(mixinClass);
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
            List<String[]> wrapCondRows = invokeStringListOrEmpty(lookup, desc, "wrapConditionEntries");
            List<String[]> staticTargetRows = invokeStringListOrEmpty(lookup, desc, "staticTargetMethods");
            List<String[]> privateShadowRows = invokeStringListOrEmpty(lookup, desc, "privateShadowTargetMethods");
            List<String[]> syntheticRows = invokeStringList(lookup, desc, "syntheticNames");

            List<MixinDescriptor.OverwriteEntry> ows = new ArrayList<>(overwriteRows.size());
            for (String[] r : overwriteRows) ows.add(new MixinDescriptor.OverwriteEntry(r[0], r[1], r[2], r[3]));

            List<MixinDescriptor.OriginalEntry> orig = new ArrayList<>(originalRows.size());
            for (String[] r : originalRows) orig.add(new MixinDescriptor.OriginalEntry(r[0], r[1], r[2]));

            List<MixinDescriptor.RedirectEntry> reds = new ArrayList<>(redirectRows.size());
            for (String[] r : redirectRows) reds.add(new MixinDescriptor.RedirectEntry(
                r[0], r[1], Integer.parseInt(r[2]), Call.valueOf(r[3]), r[4], r[5]));

            List<MixinDescriptor.InjectEntry> injs = new ArrayList<>(injectRows.size());
            for (String[] r : injectRows) injs.add(new MixinDescriptor.InjectEntry(
                r[0], At.Point.valueOf(r[1]), r[2], Integer.parseInt(r[3]),
                Boolean.parseBoolean(r[4]), Boolean.parseBoolean(r[5]), r[6], r[7]));

            List<MixinDescriptor.InjectLocalEntry> injLocals = new ArrayList<>(injectLocalRows.size());
            for (String[] r : injectLocalRows) {
                int ord = r.length >= 5 ? Integer.parseInt(r[4]) : -1;
                boolean argsOnly = r.length >= 6 && Boolean.parseBoolean(r[5]);
                injLocals.add(new MixinDescriptor.InjectLocalEntry(r[0], r[1], Integer.parseInt(r[2]), Integer.parseInt(r[3]), ord, argsOnly));
            }

            Map<String, At.Shift> injShifts = new HashMap<>();
            for (String[] r : injectShiftRows) injShifts.put(r[0] + r[1], At.Shift.valueOf(r[2]));

            List<MixinDescriptor.ShadowEntry> shads = new ArrayList<>(shadowRows.size());
            for (String[] r : shadowRows) shads.add(new MixinDescriptor.ShadowEntry(r[0], r[1], r[2]));

            List<MixinDescriptor.ShadowFieldEntry> shadFields = new ArrayList<>(shadowFieldRows.size());
            for (String[] r : shadowFieldRows) shadFields.add(new MixinDescriptor.ShadowFieldEntry(r[0], r[1], r[2]));

            List<MixinDescriptor.ShadowFieldEntry> shadStaticFields = new ArrayList<>(shadowStaticFieldRows.size());
            for (String[] r : shadowStaticFieldRows) shadStaticFields.add(new MixinDescriptor.ShadowFieldEntry(r[0], r[1], r[2]));

            List<MixinDescriptor.ModifyReturnValueEntry> mrvs = new ArrayList<>(modifyRvRows.size());
            for (String[] r : modifyRvRows) mrvs.add(new MixinDescriptor.ModifyReturnValueEntry(
                r[0], r[1], Integer.parseInt(r[2]), r[3], r[4]));

            List<MixinDescriptor.AccessorEntry> accs = new ArrayList<>(accessorRows.size());
            for (String[] r : accessorRows) accs.add(new MixinDescriptor.AccessorEntry(r[0], r[1], r[2], r[3]));

            List<MixinDescriptor.InvokerEntry> invs = new ArrayList<>(invokerRows.size());
            for (String[] r : invokerRows) invs.add(new MixinDescriptor.InvokerEntry(r[0], r[1], r[2]));

            List<MixinDescriptor.ModifyConstantEntry> mcs = new ArrayList<>(modifyConstRows.size());
            for (String[] r : modifyConstRows) mcs.add(new MixinDescriptor.ModifyConstantEntry(
                r[0], r[1], r[2], Integer.parseInt(r[3]), r[4], r[5]));

            List<MixinDescriptor.ModifyArgEntry> mas = new ArrayList<>(modifyArgRows.size());
            for (String[] r : modifyArgRows) mas.add(new MixinDescriptor.ModifyArgEntry(
                r[0], r[1], Integer.parseInt(r[2]), r[3], r[4]));

            List<MixinDescriptor.ModifyExpressionValueEntry> mxs = new ArrayList<>(modifyExprRows.size());
            for (String[] r : modifyExprRows) mxs.add(new MixinDescriptor.ModifyExpressionValueEntry(
                r[0], At.Point.valueOf(r[1]), r[2], Integer.parseInt(r[3]), r[4], r[5]));

            List<MixinDescriptor.ModifyArgsEntry> mxa = new ArrayList<>(modifyArgsRows.size());
            for (String[] r : modifyArgsRows) mxa.add(new MixinDescriptor.ModifyArgsEntry(r[0], r[1], r[2], r[3]));

            List<MixinDescriptor.ModifyReceiverEntry> mxr = new ArrayList<>(modifyRecvRows.size());
            for (String[] r : modifyRecvRows) mxr.add(new MixinDescriptor.ModifyReceiverEntry(r[0], r[1], r[2], r[3]));

            List<MixinDescriptor.WrapConditionEntry> wcs = new ArrayList<>(wrapCondRows.size());
            for (String[] r : wrapCondRows) wcs.add(new MixinDescriptor.WrapConditionEntry(
                r[0], r[1], Integer.parseInt(r[2]), r[3], r[4]));

            Map<String, String[]> synths = new LinkedHashMap<>();
            for (String[] r : syntheticRows) synths.put(r[0] + r[1], new String[]{r[2], r[3]});

            MixinDescriptor base = MixinDescriptor.build(
                mixinClass, targetInternal, ows, orig, reds, injs, injLocals, injShifts, shads, shadFields, shadStaticFields, mrvs, accs, invs, mcs, mas, mxs, mxa, mxr, wcs, synths);
            return MixinDescriptor.withTargetMaps(base, staticTargetRows, privateShadowRows);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to read generated $$Descriptor for " + mixinClass.getName(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> invokeStringList(MethodHandles.Lookup lookup, Class<?> desc, String name) throws Throwable {
        MethodHandle mh = lookup.findStatic(desc, name, MethodType.methodType(List.class));
        return (List<String[]>) mh.invoke();
    }

    private static List<String[]> invokeStringListOrEmpty(MethodHandles.Lookup lookup, Class<?> desc, String name) {
        try { return invokeStringList(lookup, desc, name); }
        catch (Throwable t) { return List.of(); }
    }
}
