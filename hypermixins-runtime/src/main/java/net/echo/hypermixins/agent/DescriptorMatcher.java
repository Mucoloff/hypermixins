package net.echo.hypermixins.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Compiles an {@code @At#desc} pattern once and matches descriptor strings against it. Supports
 * three forms:
 * <ul>
 *   <li>{@code regex:&lt;pattern&gt;} — Java regex evaluated literally.</li>
 *   <li>{@code *}-bearing string — glob; {@code *} matches any run of chars, everything else
 *       matches literally.</li>
 *   <li>plain string — exact match (the historical behaviour).</li>
 * </ul>
 */
public final class DescriptorMatcher {

    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private DescriptorMatcher() {}

    public static boolean matches(String pattern, String candidate) {
        if (pattern.equals(candidate)) return true;
        if (pattern.startsWith("regex:")) {
            return cachedPattern(pattern.substring(6)).matcher(candidate).matches();
        }
        if (pattern.indexOf('*') >= 0) {
            return cachedPattern(globToRegex(pattern)).matcher(candidate).matches();
        }
        return false;
    }

    private static Pattern cachedPattern(String regex) {
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') out.append(".*");
            else if ("\\.[]{}()+?^$|".indexOf(c) >= 0) out.append('\\').append(c);
            else out.append(c);
        }
        return out.toString();
    }
}
