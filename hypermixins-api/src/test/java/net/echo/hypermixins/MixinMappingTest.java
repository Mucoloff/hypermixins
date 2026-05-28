package net.echo.hypermixins;

import net.echo.hypermixins.agent.MixinMapping;
import net.echo.hypermixins.api.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MixinMappingTest {

    // ---- fixture target ----
    static class Target {
        public String greet(String name) { return "hello " + name; }
        public int add(int a, int b) { return a + b; }
    }

    // ---- valid mixin ----
    @Mixin("net.echo.hypermixins.MixinMappingTest$Target")
    static class ValidMixin {
        @Overwrite("greet")
        public String greet(Object self, String name) { return "hi " + name; }

        @Original("greet")
        public native String greetOrig(Object self, String name);
    }

    @Test
    void validMixinBuildsWithoutError() {
        assertDoesNotThrow(() -> new MixinMapping(ValidMixin.class));
    }

    @Test
    void overwriteKeyDerived() {
        MixinMapping m = new MixinMapping(ValidMixin.class);
        // key = targetMethodName + descriptor-without-first-param
        // greet(String)String → key "greet(Ljava/lang/String;)Ljava/lang/String;"
        assertTrue(m.getOverwrites().containsKey("greet(Ljava/lang/String;)Ljava/lang/String;"),
            "overwrites map: " + m.getOverwrites().keySet());
    }

    @Test
    void originalKeyStored() {
        MixinMapping m = new MixinMapping(ValidMixin.class);
        // key = mixin method name+desc (including Object self)
        assertTrue(m.getOriginals().values().contains("greet"));
    }

    // ---- missing @Mixin ----
    static class NoAnnotation {}

    @Test
    void missingMixinAnnotationThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MixinMapping(NoAnnotation.class));
    }

    // ---- empty @Mixin value ----
    @Mixin("")
    static class EmptyTargetMixin {}

    @Test
    void emptyMixinValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MixinMapping(EmptyTargetMixin.class));
    }

    // ---- @Overwrite missing self ----
    @Mixin("net.echo.hypermixins.MixinMappingTest$Target")
    static class MissingSelMixin {
        @Overwrite("greet")
        public String greet(String name) { return name; } // no Object self
    }

    @Test
    void overwriteMissingObjectSelfThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MixinMapping(MissingSelMixin.class));
    }

    // ---- @Overwrite first param wrong type ----
    @Mixin("net.echo.hypermixins.MixinMappingTest$Target")
    static class WrongFirstParamMixin {
        @Overwrite("greet")
        public String greet(String self, String name) { return name; } // String, not Object
    }

    @Test
    void overwriteWrongFirstParamThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MixinMapping(WrongFirstParamMixin.class));
    }

    // ---- @Redirect on non-static ----
    @Mixin("net.echo.hypermixins.MixinMappingTest$Target")
    static class NonStaticRedirectMixin {
        @Redirect(method = "greet", at = @At(desc = "java/lang/String.valueOf(Ljava/lang/Object;)Ljava/lang/String;", call = Call.INVOKESTATIC))
        public String handler(Object o) { return ""; } // not static
    }

    @Test
    void nonStaticRedirectThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MixinMapping(NonStaticRedirectMixin.class));
    }

    // ---- duplicate @Overwrite ----
    @Mixin("net.echo.hypermixins.MixinMappingTest$Target")
    static class DuplicateOverwriteMixin {
        @Overwrite("greet")
        public String greet(Object self, String name) { return "a"; }

        @Overwrite("greet")
        public String greet2(Object self, String name) { return "b"; }
    }

    @Test
    void duplicateOverwriteThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MixinMapping(DuplicateOverwriteMixin.class));
    }
}
