package net.echo.hypermixins;

import net.echo.hypermixins.config.MixinsConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class MixinsConfigTest {

    private static MixinsConfig parse(String yaml) throws IOException {
        return MixinsConfig.fromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void packagePlusBareEntries() throws Exception {
        MixinsConfig cfg = parse("""
            package: net.echo.mix
            mixins:
              - WorldMixin
              - PlayerMixin
            """);
        assertEquals("net.echo.mix", cfg.packageName());
        assertEquals(2, cfg.mixinClassNames().size());
        assertEquals("net.echo.mix.WorldMixin", cfg.mixinClassNames().get(0));
        assertEquals("net.echo.mix.PlayerMixin", cfg.mixinClassNames().get(1));
    }

    @Test
    void fullyQualifiedEntriesOverridePackage() throws Exception {
        MixinsConfig cfg = parse("""
            package: net.echo.mix
            mixins:
              - net.other.PlayerMixin
              - WorldMixin
            """);
        assertEquals("net.other.PlayerMixin", cfg.mixinClassNames().get(0));
        assertEquals("net.echo.mix.WorldMixin", cfg.mixinClassNames().get(1));
    }

    @Test
    void noPackagePrefixUsesBareNames() throws Exception {
        MixinsConfig cfg = parse("""
            mixins:
              - net.echo.foo.Mixin
            """);
        assertEquals("", cfg.packageName());
        assertEquals("net.echo.foo.Mixin", cfg.mixinClassNames().get(0));
    }

    @Test
    void commentsAndBlankLinesIgnored() throws Exception {
        MixinsConfig cfg = parse("""
            # top comment
            package: pkg   # trailing

            mixins:
              # nested comment
              - A
              - B   # tail comment
            """);
        assertEquals(2, cfg.mixinClassNames().size());
        assertEquals("pkg.A", cfg.mixinClassNames().get(0));
        assertEquals("pkg.B", cfg.mixinClassNames().get(1));
    }

    @Test
    void quotedValuesStripped() throws Exception {
        MixinsConfig cfg = parse("""
            package: "net.echo.mix"
            mixins:
              - 'WorldMixin'
              - "PlayerMixin"
            """);
        assertEquals("net.echo.mix", cfg.packageName());
        assertEquals("net.echo.mix.WorldMixin", cfg.mixinClassNames().get(0));
        assertEquals("net.echo.mix.PlayerMixin", cfg.mixinClassNames().get(1));
    }

    @Test
    void emptyMixinsThrows() {
        assertThrows(IOException.class, () -> parse("package: pkg\nmixins:\n"));
    }

    @Test
    void listItemOutsideMixinsThrows() {
        assertThrows(IOException.class, () -> parse("package: pkg\n  - Stray\n"));
    }

    @Test
    void inlineListUnsupported() {
        assertThrows(IOException.class, () -> parse("mixins: [A, B]\n"));
    }

    @Test
    void discoverAllFindsAllProjectYamls() throws Exception {
        var configs = net.echo.hypermixins.config.MixinsConfig.discoverAll(
            getClass().getClassLoader());
        // The two test fixtures under src/test/resources/META-INF/hypermixins/.
        long alpha = configs.stream().flatMap(c -> c.mixinClassNames().stream())
            .filter(s -> s.equals("net.echo.sample.a.AlphaMixin")).count();
        long beta  = configs.stream().flatMap(c -> c.mixinClassNames().stream())
            .filter(s -> s.equals("net.echo.sample.b.BetaMixin")).count();
        long gamma = configs.stream().flatMap(c -> c.mixinClassNames().stream())
            .filter(s -> s.equals("net.echo.sample.b.GammaMixin")).count();
        assertEquals(1, alpha);
        assertEquals(1, beta);
        assertEquals(1, gamma);
    }
}
