package net.echo.hypermixins.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ClassWriter that resolves the common superclass for stack-map frames by reading
 * {@code .class} resources through the supplied ClassLoader instead of calling
 * {@code Class.forName}. Loading the target during transform would trigger class init and
 * deadlock against the agent's own transform call — streaming the bytes is the safe path.
 */
public final class SafeClassWriter extends ClassWriter {

    private final ClassLoader loader;

    SafeClassWriter(int flags, ClassLoader loader) {
        super(flags);
        this.loader = loader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) return type1;
        if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) return "java/lang/Object";
        try {
            List<String> chain1 = superChain(type1);
            Set<String> set1 = new HashSet<>(chain1);
            for (String t : superChain(type2)) { if (set1.contains(t)) return t; }
        } catch (IOException ignored) {}
        return "java/lang/Object";
    }

    private List<String> superChain(String name) throws IOException {
        List<String> chain = new ArrayList<>();
        String cur = name;
        while (cur != null && !cur.equals("java/lang/Object")) {
            chain.add(cur); cur = superOf(cur);
        }
        chain.add("java/lang/Object");
        return chain;
    }

    private String superOf(String name) throws IOException {
        ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
        try (InputStream is = cl.getResourceAsStream(name + ".class")) {
            if (is == null) return null;
            return new ClassReader(is).getSuperName();
        }
    }
}
