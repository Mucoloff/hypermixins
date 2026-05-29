package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Call;
import net.echo.hypermixins.annotations.Cancellable;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Local;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Original;
import net.echo.hypermixins.annotations.Overwrite;
import net.echo.hypermixins.annotations.Redirect;
import net.echo.hypermixins.annotations.Shadow;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection-based descriptor builder used when no KSP {@code $$Descriptor} is on the classpath
 * (source-only fixtures, hand-written test mixins). Validates the same rules the processor
 * enforces at compile time and delegates per-annotation reflection collection to
 * {@link ReflectionCollectors}.
 */
final class AnnotationDescriptorReader {

    private AnnotationDescriptorReader() {}

    static MixinDescriptor read(Class<?> mixinClass) {
        Mixin mixin = mixinClass.getAnnotation(Mixin.class);
        if (mixin == null) throw new IllegalArgumentException("Missing @Mixin on " + mixinClass);
        if (mixin.value().isEmpty()) throw new IllegalArgumentException("@Mixin#value() is empty on " + mixinClass);
        String targetInternal = mixin.value().replace('.', '/');

        List<MixinDescriptor.OverwriteEntry> overwrites = new ArrayList<>();
        List<MixinDescriptor.OriginalEntry>  originals  = new ArrayList<>();
        List<MixinDescriptor.RedirectEntry>  redirects  = new ArrayList<>();
        List<MixinDescriptor.InjectEntry>    injects    = new ArrayList<>();
        List<MixinDescriptor.InjectLocalEntry> injectLocals = new ArrayList<>();
        List<MixinDescriptor.ShadowEntry>    shadows    = new ArrayList<>();
        List<MixinDescriptor.ShadowFieldEntry> shadowFields = new ArrayList<>();
        List<MixinDescriptor.ShadowFieldEntry> shadowStaticFields = new ArrayList<>();
        Map<String, String[]> synths    = new LinkedHashMap<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            Overwrite ow = method.getAnnotation(Overwrite.class);
            Original  or = method.getAnnotation(Original.class);
            Redirect  re = method.getAnnotation(Redirect.class);
            Inject    in = method.getAnnotation(Inject.class);
            Shadow    sh = method.getAnnotation(Shadow.class);

            if (ow != null) collectOverwrite(mixin, method, ow, overwrites, synths);
            if (or != null) collectOriginal(method, or, originals);
            if (re != null) collectRedirect(method, re, redirects);
            if (sh != null) collectShadowMethod(method, sh, shadows);
            if (in != null) collectInject(method, in, injects, injectLocals);
        }
        for (Field f : mixinClass.getDeclaredFields()) {
            Shadow shFld = f.getAnnotation(Shadow.class);
            if (shFld == null) continue;
            String tname = resolveShadowName(f.getName(), shFld.value(), shFld.prefix());
            MixinDescriptor.ShadowFieldEntry entry = new MixinDescriptor.ShadowFieldEntry(
                f.getName(), Type.getDescriptor(f.getType()), tname);
            if (Modifier.isStatic(f.getModifiers())) shadowStaticFields.add(entry);
            else shadowFields.add(entry);
        }

        Set<String> staticMap = ReflectionProbes.staticTargetMethods(
            mixinClass, targetInternal, originals, overwrites);
        Map<String, At.Shift> injectShifts = ReflectionCollectors.injectShifts(mixinClass);
        List<MixinDescriptor.ModifyReturnValueEntry> mrvs = ReflectionCollectors.modifyReturnValues(mixinClass);
        List<MixinDescriptor.AccessorEntry> accs = ReflectionCollectors.accessors(mixinClass);
        List<MixinDescriptor.InvokerEntry> invs = ReflectionCollectors.invokers(mixinClass);
        List<MixinDescriptor.ModifyConstantEntry> mcs = ReflectionCollectors.modifyConstants(mixinClass);
        List<MixinDescriptor.ModifyArgEntry> mas = ReflectionCollectors.modifyArgs(mixinClass);
        List<MixinDescriptor.ModifyExpressionValueEntry> mxs = ReflectionCollectors.modifyExpressionValues(mixinClass);
        List<MixinDescriptor.ModifyArgsEntry> mxa = ReflectionCollectors.modifyArgsAll(mixinClass);
        List<MixinDescriptor.ModifyReceiverEntry> mxr = ReflectionCollectors.modifyReceivers(mixinClass);
        Set<String> privateShadowMap = ReflectionProbes.privateShadowTargets(mixinClass, targetInternal, shadows, invs);
        return MixinDescriptor.buildWithMaps(mixinClass, targetInternal,
            overwrites, originals, redirects, injects, injectLocals, injectShifts,
            shadows, shadowFields, shadowStaticFields, mrvs, accs, invs, mcs, mas, mxs, mxa, mxr,
            synths, staticMap, privateShadowMap);
    }

    private static void collectOverwrite(
        Mixin mixin, Method method, Overwrite ow,
        List<MixinDescriptor.OverwriteEntry> out, Map<String, String[]> synths
    ) {
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
        out.add(new MixinDescriptor.OverwriteEntry(ow.value(), targetDesc, method.getName(), handlerDesc));
        String hash = sha1Hex16(targetDesc);
        synths.put(ow.value() + targetDesc, new String[]{
            "__original$" + ow.value() + "$" + hash,
            "__dispatch$" + ow.value() + "$" + hash
        });
    }

    private static void collectOriginal(Method method, Original or, List<MixinDescriptor.OriginalEntry> out) {
        if (or.value().isEmpty()) throw new IllegalArgumentException("@Original#value() is empty on " + method);
        out.add(new MixinDescriptor.OriginalEntry(method.getName(), Type.getMethodDescriptor(method), or.value()));
    }

    private static void collectRedirect(Method method, Redirect re, List<MixinDescriptor.RedirectEntry> out) {
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
        out.add(new MixinDescriptor.RedirectEntry(re.method(), re.at().desc(), re.at().index(),
            call, method.getName(), handlerDesc));
    }

    private static void collectShadowMethod(Method method, Shadow sh, List<MixinDescriptor.ShadowEntry> out) {
        if (method.getParameterCount() == 0 || method.getParameterTypes()[0] != Object.class)
            throw new IllegalArgumentException("@Shadow first param must be Object self on " + method);
        String targetName = resolveShadowName(method.getName(), sh.value(), sh.prefix());
        out.add(new MixinDescriptor.ShadowEntry(method.getName(), Type.getMethodDescriptor(method), targetName));
    }

    private static void collectInject(
        Method method, Inject in,
        List<MixinDescriptor.InjectEntry> out, List<MixinDescriptor.InjectLocalEntry> localsOut
    ) {
        if (in.method().isEmpty()) throw new IllegalArgumentException("@Inject#method() empty on " + method);
        if (Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException("@Inject must be non-static: " + method);
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 || params[0] != Object.class)
            throw new IllegalArgumentException("@Inject first param must be Object self on " + method);
        boolean cancellable = in.cancellable() || method.isAnnotationPresent(Cancellable.class);
        boolean returnable = false;
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
            throw new IllegalArgumentException("@Inject point " + point + " requires @At#desc() on " + method);
        }
        String handlerDesc = Type.getMethodDescriptor(method);
        out.add(new MixinDescriptor.InjectEntry(in.method(), point, at.desc(), at.index(),
            cancellable, returnable, method.getName(), handlerDesc));
        java.lang.annotation.Annotation[][] paramAnns = method.getParameterAnnotations();
        for (int pi = 0; pi < paramAnns.length; pi++) {
            for (java.lang.annotation.Annotation a : paramAnns[pi]) {
                if (a instanceof Local lo) {
                    localsOut.add(new MixinDescriptor.InjectLocalEntry(
                        method.getName(), handlerDesc, pi, lo.index(), lo.ordinal(), lo.argsOnly()));
                }
            }
        }
    }

    private static String resolveShadowName(String simpleName, String value, String prefix) {
        if (!value.isBlank()) return value;
        if (!prefix.isEmpty() && simpleName.startsWith(prefix)) return simpleName.substring(prefix.length());
        return simpleName;
    }

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
     * PUTFIELD:  {@code (Lowner;Ldesc;)V}  — owner modelled as Object to mirror @Redirect's self
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
