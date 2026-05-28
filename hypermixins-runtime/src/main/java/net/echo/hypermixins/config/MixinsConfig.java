package net.echo.hypermixins.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed view of a {@code .mixins.yml} descriptor.
 * <p>
 * Supported schema:
 * <pre>{@code
 * package: net.echo.example.mixins   # optional; prepended to bare entries below
 * mixins:                            # required; list of mixin class names
 *   - WorldMixin                     # bare → resolved as {package}.WorldMixin
 *   - net.echo.other.PlayerMixin     # fully qualified → used as-is
 * }</pre>
 * <p>
 * Parser is intentionally minimal — only the two keys above plus list items are honored.
 * Anchors, flow style, multiline strings, and nested maps are not supported.
 */
public final class MixinsConfig {

    private final String packageName;
    private final List<String> mixinClassNames;

    private MixinsConfig(String packageName, List<String> mixinClassNames) {
        this.packageName = packageName;
        this.mixinClassNames = List.copyOf(mixinClassNames);
    }

    /** Optional {@code package:} prefix. Empty string when absent. */
    public String packageName() { return packageName; }

    /** Fully qualified mixin class names (package prefix already applied to bare entries). */
    public List<String> mixinClassNames() { return mixinClassNames; }

    public static MixinsConfig fromStream(InputStream stream) throws IOException {
        String pkg = "";
        List<String> raw = new ArrayList<>();
        boolean inMixinList = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String stripped = stripComment(line);
                if (stripped.isBlank()) continue;
                String trimmed = stripped.trim();

                if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                    if (!inMixinList) {
                        throw new IOException("List item outside 'mixins:' section: " + line);
                    }
                    String value = trimmed.substring(1).trim();
                    if (value.isEmpty()) throw new IOException("Empty list item in mixins.yml");
                    raw.add(stripQuotes(value));
                    continue;
                }

                int colon = trimmed.indexOf(':');
                if (colon < 0) throw new IOException("Malformed line (missing ':'): " + line);
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                switch (key) {
                    case "package" -> {
                        pkg = stripQuotes(value);
                        inMixinList = false;
                    }
                    case "mixins" -> {
                        inMixinList = true;
                        if (!value.isEmpty()) {
                            throw new IOException("Inline list under 'mixins:' not supported");
                        }
                    }
                    default -> inMixinList = false; // unknown key — ignore but exit list scope
                }
            }
        }

        List<String> resolved = new ArrayList<>(raw.size());
        for (String name : raw) {
            if (name.indexOf('.') >= 0 || pkg.isEmpty()) resolved.add(name);
            else resolved.add(pkg + "." + name);
        }
        if (resolved.isEmpty()) throw new IOException("mixins.yml contains no mixin entries");
        return new MixinsConfig(pkg, resolved);
    }

    public static MixinsConfig fromUrl(URL url) throws IOException {
        try (InputStream is = url.openStream()) { return fromStream(is); }
    }

    /** Loads every {@code .mixins.yml} / {@code mixins.yml} resource found by {@code loader}. */
    public static List<MixinsConfig> discoverAll(ClassLoader loader) throws IOException {
        ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
        List<MixinsConfig> configs = new ArrayList<>();
        for (String name : List.of("mixins.yml", ".mixins.yml")) {
            for (URL url : Collections.list(cl.getResources(name))) {
                configs.add(fromUrl(url));
            }
        }
        return configs;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        if (hash < 0) return line;
        // ignore # inside quoted strings — minimal handling
        int dq = line.indexOf('"');
        int sq = line.indexOf('\'');
        int firstQuote = (dq >= 0 && (sq < 0 || dq < sq)) ? dq : sq;
        if (firstQuote >= 0 && firstQuote < hash) return line; // quote opens before #
        return line.substring(0, hash);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) ||
                                (s.startsWith("'")  && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
