package net.echo.hypermixins;

import net.echo.hypermixins.annotations.At;
import net.echo.hypermixins.annotations.Mixin;
import net.echo.hypermixins.annotations.Operation;
import net.echo.hypermixins.annotations.WrapOperation;
import net.echo.hypermixins.registry.MixinRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.echo.hypermixins.TransformerTestSupport.applyMixin;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixinWrapOperationTest {

    @AfterEach
    void cleanRegistry() {
        MixinRegistry.clearForTests();
    }

    public static class WopTarget {
        public int run(String input) {
            return Integer.parseInt(input);
        }
    }

    @Mixin("net.echo.hypermixins.MixinWrapOperationTest$WopTarget")
    public static class WopMixin {
        @WrapOperation(method = "run",
            at = @At(desc = "java/lang/Integer.parseInt(Ljava/lang/String;)I"))
        public static int doubleParse(String s, Operation<Integer> op) throws Throwable {
            int parsed = (Integer) op.call(s);
            return parsed * 2;
        }
    }

    @Test
    void wrapOperationRoundtripsThroughHandler() throws Exception {
        Class<?> t = applyMixin(WopTarget.class, WopMixin.class);
        Object inst = t.getDeclaredConstructor().newInstance();
        int result = (int) t.getMethod("run", String.class).invoke(inst, "21");
        // op.call("21") = 21; handler doubles → 42.
        assertEquals(42, result);
    }
}
