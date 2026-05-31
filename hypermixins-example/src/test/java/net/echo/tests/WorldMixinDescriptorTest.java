package net.echo.tests;

import net.echo.hypermixins.agent.MixinDescriptor;
import net.echo.hypermixins.config.MixinsConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that the KSP processor produces a descriptor compatible with the runtime
 * loader. The example module is the only place where production-style codegen (KSP) and the
 * runtime loader meet in the same JVM at test time.
 */
class WorldMixinDescriptorTest {

    @Test
    void descriptorLoadsAllEntries() {
        MixinDescriptor d = MixinDescriptor.load(WorldMixin.class);
        assertNotNull(d);
        assertEquals(WorldMixin.class, d.mixinClass());
        assertEquals("net/echo/testworld/World", d.targetClass());

        assertEquals(1, d.overwrites().size());
        MixinDescriptor.OverwriteEntry ow = d.overwrites().getFirst();
        assertEquals("getPlayers", ow.targetName());
        assertEquals("()Ljava/util/List;", ow.targetDesc());
        assertEquals("getPlayers", ow.handlerName());
        assertEquals("(Ljava/lang/Object;)Ljava/util/List;", ow.handlerDesc());

        assertEquals(1, d.originals().size());
        assertEquals("getPlayers", d.originals().getFirst().targetName());

        assertEquals(1, d.redirects().size());
        MixinDescriptor.RedirectEntry r = d.redirects().getFirst();
        assertEquals("run", r.targetMethod());
        assertEquals("java/lang/Thread.sleep(J)V", r.invokeDesc());
        assertEquals(1, r.index());
        assertEquals(net.echo.hypermixins.annotations.Call.INVOKESTATIC, r.call());

        assertEquals(1, d.injects().size());
        MixinDescriptor.InjectEntry inj = d.injects().getFirst();
        assertEquals("addPlayer", inj.targetMethod());
        assertEquals(net.echo.hypermixins.annotations.At.Point.HEAD, inj.point());
        assertFalse(inj.cancellable());
        assertFalse(inj.returnable());

        // syntheticNames populated for the @Overwrite key.
        String[] synth = d.synthetics().get("getPlayers()Ljava/util/List;");
        assertNotNull(synth, "synthetic names missing for getPlayers");
        assertTrue(synth[0].startsWith("__original$getPlayers$"), "mangled name format: " + synth[0]);
        assertTrue(synth[1].startsWith("__dispatch$getPlayers$"), "dispatch name format: " + synth[1]);
    }

    @Test
    void expressionInjectPointSurvivesKspRoundTrip() {
        // Guards a processor bug: @At lives inside @Inject's `at` member, so the inject
        // collector must read the nested annotation. Reading a (non-existent) top-level @At
        // silently defaulted every point to HEAD, breaking EXPRESSION / INVOKE / FIELD via KSP.
        MixinDescriptor d = MixinDescriptor.load(WorldExtrasMixin.class);
        long expressionInjects = d.injects().stream()
            .filter(e -> e.point() == net.echo.hypermixins.annotations.At.Point.EXPRESSION)
            .count();
        assertEquals(2, expressionInjects,
            "expected onListAdd + onPlayersAccess to serialize as EXPRESSION, not HEAD");

        // The DSL state must round-trip through the descriptor too (schema v3 tables).
        var onListAdd = d.expressions().get("onListAdd(Ljava/lang/Object;Ljava/lang/Object;)V");
        assertNotNull(onListAdd, "onListAdd expression metadata missing from descriptor");
        assertEquals("listAdd(?)", onListAdd.expression());
        assertEquals(1, onListAdd.definitions().size());
        assertEquals("java/util/List.add(Ljava/lang/Object;)Z", onListAdd.definitions().getFirst().method());
    }

    @Test
    void injectPointsSurviveKspRoundTrip() {
        MixinDescriptor d = MixinDescriptor.load(WorldPointsMixin.class);
        java.util.Map<String, net.echo.hypermixins.annotations.At.Point> byHandler =
            new java.util.HashMap<>();
        for (MixinDescriptor.InjectEntry e : d.injects()) byHandler.put(e.handlerName(), e.point());

        assertEquals(net.echo.hypermixins.annotations.At.Point.INVOKE, byHandler.get("onInvoke"));
        assertEquals(net.echo.hypermixins.annotations.At.Point.FIELD, byHandler.get("onField"));
        assertEquals(net.echo.hypermixins.annotations.At.Point.CONSTANT, byHandler.get("onConstant"));
        assertEquals(net.echo.hypermixins.annotations.At.Point.NEW, byHandler.get("onNew"));
    }

    @Test
    void injectShiftsSurviveKspRoundTrip() {
        MixinDescriptor d = MixinDescriptor.load(WorldPointsMixin.class);
        String afterKey = "onShiftAfter(Ljava/lang/Object;)V";
        String byKey = "onShiftBy(Ljava/lang/Object;)V";
        // AFTER is the only shift baked into the descriptor; reading it the wrong way (the
        // a096a55 bug) would have left it absent / defaulted to BEFORE.
        assertEquals(net.echo.hypermixins.annotations.At.Shift.AFTER, d.injectShifts().get(afterKey));
        // BY (with by()) is resolved reflectively off the handler @At at transform time, so it
        // is intentionally NOT baked into the injectShifts table.
        assertFalse(d.injectShifts().containsKey(byKey),
            "BY shift must stay out of the descriptor — it is read reflectively");
    }

    @Test
    void yamlDiscoveredOnClasspath() throws IOException {
        List<MixinsConfig> configs = MixinsConfig.discoverAll(getClass().getClassLoader());
        boolean found = configs.stream()
            .flatMap(c -> c.mixinClassNames().stream())
            .anyMatch(s -> s.equals("net.echo.tests.WorldMixin"));
        assertTrue(found, "expected WorldMixin to be advertised via auto-generated YAML");
    }
}
