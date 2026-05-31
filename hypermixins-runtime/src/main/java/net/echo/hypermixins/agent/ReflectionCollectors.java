package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Accessor;
import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Inject;
import net.echo.hypermixins.annotations.Invoker;
import net.echo.hypermixins.annotations.ModifyArg;
import net.echo.hypermixins.annotations.ModifyArgs;
import net.echo.hypermixins.annotations.ModifyConstant;
import net.echo.hypermixins.annotations.ModifyExpressionValue;
import net.echo.hypermixins.annotations.ModifyReceiver;
import net.echo.hypermixins.annotations.ModifyReturnValue;
import net.echo.hypermixins.annotations.Operation;
import net.echo.hypermixins.annotations.WrapMethod;
import net.echo.hypermixins.annotations.WrapOperation;
import net.echo.hypermixins.annotations.WrapWithCondition;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-fallback collectors for the {@link MixinDescriptor#fromAnnotations} path. One
 * method per annotation, same shape as the KSP-side Collectors class in the processor.
 * Validation throws {@link IllegalArgumentException} on misuse — the fallback path runs at
 * register time, not compile time, so loud failures beat silent skips.
 */
final class ReflectionCollectors {

    private ReflectionCollectors() {}

    static List<MixinDescriptor.WrapMethodEntry> wrapMethods(Class<?> mixinClass) {
        List<MixinDescriptor.WrapMethodEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            WrapMethod ann = m.getAnnotation(WrapMethod.class);
            if (ann == null) continue;
            if (ann.value().isEmpty())
                throw new IllegalArgumentException("@WrapMethod#value() must not be empty on " + m);
            if (Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@WrapMethod must not be static: " + m);
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 0 || params[params.length - 1] != Operation.class)
                throw new IllegalArgumentException("@WrapMethod handler last parameter must be Operation<R>: " + m);
            out.add(new MixinDescriptor.WrapMethodEntry(ann.value(), m.getName(), Type.getMethodDescriptor(m)));
        }
        return out;
    }

    static List<MixinDescriptor.WrapOperationEntry> wrapOperations(Class<?> mixinClass) {
        List<MixinDescriptor.WrapOperationEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            WrapOperation ann = m.getAnnotation(WrapOperation.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@WrapOperation#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @WrapOperation " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@WrapOperation must be static: " + m);
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 0 || params[params.length - 1] != Operation.class)
                throw new IllegalArgumentException("@WrapOperation handler last parameter must be Operation<R>: " + m);
            out.add(new MixinDescriptor.WrapOperationEntry(ann.method(), ann.at().desc(), ann.at().index(),
                m.getName(), Type.getMethodDescriptor(m)));
        }
        return out;
    }

    static List<MixinDescriptor.WrapConditionEntry> wrapConditions(Class<?> mixinClass) {
        List<MixinDescriptor.WrapConditionEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            WrapWithCondition ann = m.getAnnotation(WrapWithCondition.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@WrapWithCondition#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @WrapWithCondition " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@WrapWithCondition must be static: " + m);
            String handlerDesc = Type.getMethodDescriptor(m);
            if (!handlerDesc.endsWith(")Z"))
                throw new IllegalArgumentException("@WrapWithCondition handler must return boolean: " + m);
            out.add(new MixinDescriptor.WrapConditionEntry(ann.method(), ann.at().desc(), ann.at().index(),
                m.getName(), handlerDesc));
        }
        return out;
    }

    static List<MixinDescriptor.ModifyReceiverEntry> modifyReceivers(Class<?> mixinClass) {
        List<MixinDescriptor.ModifyReceiverEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            ModifyReceiver ann = m.getAnnotation(ModifyReceiver.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@ModifyReceiver#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @ModifyReceiver " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@ModifyReceiver must be static: " + m);
            out.add(new MixinDescriptor.ModifyReceiverEntry(ann.method(), ann.at().desc(), m.getName(), Type.getMethodDescriptor(m)));
        }
        return out;
    }

    static List<MixinDescriptor.ModifyArgsEntry> modifyArgsAll(Class<?> mixinClass) {
        List<MixinDescriptor.ModifyArgsEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            ModifyArgs ann = m.getAnnotation(ModifyArgs.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@ModifyArgs#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @ModifyArgs " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@ModifyArgs must be static: " + m);
            String handlerDesc = Type.getMethodDescriptor(m);
            if (!handlerDesc.equals("([Ljava/lang/Object;)V")
                && !handlerDesc.equals("(Lnet/echo/hypermixins/annotations/Args;)V"))
                throw new IllegalArgumentException("@ModifyArgs handler must be (Object[]): void or (Args): void on " + m);
            out.add(new MixinDescriptor.ModifyArgsEntry(ann.method(), ann.at().desc(), m.getName(), handlerDesc));
        }
        return out;
    }

    static List<MixinDescriptor.ModifyExpressionValueEntry> modifyExpressionValues(Class<?> mixinClass) {
        List<MixinDescriptor.ModifyExpressionValueEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            ModifyExpressionValue ann = m.getAnnotation(ModifyExpressionValue.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@ModifyExpressionValue#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @ModifyExpressionValue " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@ModifyExpressionValue must be static: " + m);
            out.add(new MixinDescriptor.ModifyExpressionValueEntry(ann.method(), ann.at().point(), ann.at().desc(),
                ann.at().index(), m.getName(), Type.getMethodDescriptor(m)));
        }
        return out;
    }

    static List<MixinDescriptor.ModifyArgEntry> modifyArgs(Class<?> mixinClass) {
        List<MixinDescriptor.ModifyArgEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            ModifyArg ann = m.getAnnotation(ModifyArg.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@ModifyArg#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @ModifyArg " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@ModifyArg must be static: " + m);
            out.add(new MixinDescriptor.ModifyArgEntry(ann.method(), ann.at().desc(), ann.index(),
                m.getName(), Type.getMethodDescriptor(m)));
        }
        return out;
    }

    static List<MixinDescriptor.ModifyConstantEntry> modifyConstants(Class<?> mixinClass) {
        List<MixinDescriptor.ModifyConstantEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            ModifyConstant ann = m.getAnnotation(ModifyConstant.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@ModifyConstant#method() must not be empty on " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@ModifyConstant must be static: " + m);
            ModifyConstant.Constant c = ann.constant();
            String type; String value;
            if (c.intValue() != Integer.MIN_VALUE) { type = "I"; value = Integer.toString(c.intValue()); }
            else if (c.longValue() != Long.MIN_VALUE) { type = "J"; value = Long.toString(c.longValue()); }
            else if (!Float.isNaN(c.floatValue())) { type = "F"; value = Float.toString(c.floatValue()); }
            else if (!Double.isNaN(c.doubleValue())) { type = "D"; value = Double.toString(c.doubleValue()); }
            else if (!c.stringValue().isEmpty()) { type = "Ljava/lang/String;"; value = c.stringValue(); }
            else throw new IllegalArgumentException("@ModifyConstant must specify a constant on " + m);
            out.add(new MixinDescriptor.ModifyConstantEntry(ann.method(), type, value, ann.index(),
                m.getName(), Type.getMethodDescriptor(m)));
        }
        return out;
    }

    static List<MixinDescriptor.InvokerEntry> invokers(Class<?> mixinClass) {
        List<MixinDescriptor.InvokerEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            Invoker ann = m.getAnnotation(Invoker.class);
            if (ann == null) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 0 || params[0] != Object.class)
                throw new IllegalArgumentException("@Invoker first param must be Object self on " + m);
            String targetName = !ann.value().isBlank() ? ann.value() : deriveInvokerName(m.getName());
            out.add(new MixinDescriptor.InvokerEntry(m.getName(), Type.getMethodDescriptor(m), targetName));
        }
        return out;
    }

    static List<MixinDescriptor.AccessorEntry> accessors(Class<?> mixinClass) {
        List<MixinDescriptor.AccessorEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            Accessor ann = m.getAnnotation(Accessor.class);
            if (ann == null) continue;
            Class<?>[] params = m.getParameterTypes();
            boolean returnsVoid = m.getReturnType() == void.class;
            boolean isSetter = returnsVoid && params.length == 2;
            boolean isGetter = !returnsVoid && params.length == 1;
            if (!isGetter && !isSetter)
                throw new IllegalArgumentException("@Accessor must be (Object): T or (Object, T): void on " + m);
            String targetField = !ann.value().isBlank() ? ann.value() : deriveAccessorField(m.getName());
            out.add(new MixinDescriptor.AccessorEntry(m.getName(), Type.getMethodDescriptor(m), isGetter ? "GET" : "SET", targetField));
        }
        return out;
    }

    static List<MixinDescriptor.ModifyReturnValueEntry> modifyReturnValues(Class<?> mixinClass) {
        List<MixinDescriptor.ModifyReturnValueEntry> out = new ArrayList<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            ModifyReturnValue ann = m.getAnnotation(ModifyReturnValue.class);
            if (ann == null) continue;
            if (ann.method().isEmpty())
                throw new IllegalArgumentException("@ModifyReturnValue#method() must not be empty on " + m);
            if (ann.at().desc().isEmpty())
                throw new IllegalArgumentException("@At#desc() must not be empty on @ModifyReturnValue " + m);
            if (!Modifier.isStatic(m.getModifiers()))
                throw new IllegalArgumentException("@ModifyReturnValue must be static: " + m);
            String desc = ann.at().desc();
            String handlerDesc = Type.getMethodDescriptor(m);
            int parenIdx = desc.indexOf('(');
            // Wildcard / regex matchers skip the signature check — DescriptorMatcher resolves
            // the actual invoke shape at transform time.
            if (parenIdx >= 0 && desc.indexOf('*') < 0 && !desc.startsWith("regex:")) {
                String invokeSig = desc.substring(parenIdx);
                String invokeReturn = invokeSig.substring(invokeSig.indexOf(')') + 1);
                String expected = "(" + invokeReturn + ")" + invokeReturn;
                if (!handlerDesc.equals(expected)) {
                    throw new IllegalArgumentException(
                        "@ModifyReturnValue handler signature must match " + expected
                        + " (got " + handlerDesc + ") on " + m);
                }
            }
            out.add(new MixinDescriptor.ModifyReturnValueEntry(ann.method(), desc, ann.at().index(),
                m.getName(), handlerDesc));
        }
        return out;
    }

    static Map<String, At.Shift> injectShifts(Class<?> mixinClass) {
        Map<String, At.Shift> out = new HashMap<>();
        for (Method method : mixinClass.getDeclaredMethods()) {
            Inject in = method.getAnnotation(Inject.class);
            if (in == null) continue;
            At.Shift shift = in.at().shift();
            if (shift == At.Shift.BEFORE) continue;
            String handlerDesc = Type.getMethodDescriptor(method);
            out.put(method.getName() + handlerDesc, shift);
        }
        return out;
    }

    private static String deriveInvokerName(String method) {
        for (String prefix : new String[]{"invoke", "call"}) {
            if (method.startsWith(prefix) && method.length() > prefix.length()
                && Character.isUpperCase(method.charAt(prefix.length()))) {
                String tail = method.substring(prefix.length());
                return Character.toLowerCase(tail.charAt(0)) + tail.substring(1);
            }
        }
        return method;
    }

    private static String deriveAccessorField(String method) {
        for (String prefix : new String[]{"get", "set", "is"}) {
            if (method.startsWith(prefix) && method.length() > prefix.length()
                && Character.isUpperCase(method.charAt(prefix.length()))) {
                String tail = method.substring(prefix.length());
                return Character.toLowerCase(tail.charAt(0)) + tail.substring(1);
            }
        }
        return method;
    }
}
