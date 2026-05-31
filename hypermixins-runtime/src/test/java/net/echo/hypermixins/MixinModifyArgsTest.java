package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.ModifyArgs;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reference-only @ModifyArgs: handler receives Object[] of every arg of the matched INVOKE,
 * may mutate the array in place, and the transformer reloads each (possibly modified) entry
 * back onto the stack before the call.
 */
public class MixinModifyArgsTest {

    public static class Target {
        public String run(String tag) {
            HashMap<String, String> map = new HashMap<>();
            map.put(tag, "v");
            return map.toString();
        }
    }

    @Mixin("net.echo.hypermixins.MixinModifyArgsTest$Target")
    public static class Mix {
        @ModifyArgs(method = "run",
            at = @At(desc = "java/util/HashMap.put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
        public static void capture(Object[] args) {
            args[0] = "swapped-" + args[0];
            args[1] = "fixed";
        }
    }

    @AfterEach
    void clear() { MixinRegistry.clearForTests(); }

    @Test
    void modifyArgsMutatesInvokeArgsInPlace() throws Exception {
        Class<?> t = applyMixin(Target.class, Mix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        Object result = t.getMethod("run", String.class).invoke(inst, "orig");
        assertEquals("{swapped-orig=fixed}", result);
    }

    // ---- primitive args boxed into Object[] ----

    public static class PrimitiveTarget {
        public int run(int x) {
            return Integer.max(x, 0);
        }
    }
    @Mixin("net.echo.hypermixins.MixinModifyArgsTest$PrimitiveTarget")
    public static class PrimitiveMix {
        @ModifyArgs(method = "run",
            at = @At(desc = "java/lang/Integer.max(II)I"))
        public static void capture(Object[] args) {
            args[0] = ((Integer) args[0]) + 100;
            args[1] = ((Integer) args[1]) + 1;
        }
    }

    @Test
    void modifyArgsBoxesPrimitivesInArray() throws Exception {
        Class<?> t = applyMixin(PrimitiveTarget.class, PrimitiveMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // input 7 → Integer.max(7+100=107, 0+1=1) = 107
        assertEquals(107, t.getMethod("run", int.class).invoke(inst, 7));
    }

    // ---- @ModifyArgs handler accepts Args wrapper ----

    public static class ArgsWrapperTarget {
        public String run(String input) {
            return String.format("[%s]", input);
        }
    }
    @Mixin("net.echo.hypermixins.MixinModifyArgsTest$ArgsWrapperTarget")
    public static class ArgsWrapperMix {
        @ModifyArgs(method = "run",
            at = @At(desc = "java/lang/String.format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
        public static void rewriteFormat(net.echo.hypermixins.annotations.Args args) {
            args.set(0, "<%s>");
        }
    }

    @Test
    void argsWrapperShapeMutatesThroughSet() throws Exception {
        Class<?> t = applyMixin(ArgsWrapperTarget.class, ArgsWrapperMix.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        // Handler swaps the format spec; "hello" → "<hello>".
        assertEquals("<hello>", t.getMethod("run", String.class).invoke(inst, "hello"));
    }
}
