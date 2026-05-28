package net.echo.hypermixins.agent;

import net.echo.hypermixins.HyperMixins;

import java.lang.instrument.Instrumentation;

/**
 * Drop-in {@code -javaagent:} entry point for HyperMixins. Auto-discovers every
 * {@code META-INF/hypermixins/*.mixins.yml} on the classpath at startup and registers the
 * mixins they list, with no code required from the host application.
 *
 * <pre>{@code
 *   java -javaagent:hypermixins-agent.jar -cp app.jar com.example.Main
 * }</pre>
 *
 * Also supports late attach via {@code agentmain} (e.g., from a tool that uses
 * {@code ByteBuddyAgent} or {@code com.sun.tools.attach.VirtualMachine}).
 */
public final class HyperMixinsAgent {

    private HyperMixinsAgent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        register(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        register(args, inst);
    }

    private static void register(String args, Instrumentation inst) throws Exception {
        ClassLoader loader = pickLoader(args);
        HyperMixins.registerFromClasspath(inst, loader);
    }

    /**
     * Optional agent argument: a fully qualified class name. When present, the loader of that
     * class is used for classpath YAML discovery — useful if the host app loads its mixin jars
     * on a non-system class loader.
     */
    private static ClassLoader pickLoader(String args) {
        if (args == null || args.isBlank()) return ClassLoader.getSystemClassLoader();
        try {
            return Class.forName(args).getClassLoader();
        } catch (ClassNotFoundException e) {
            return ClassLoader.getSystemClassLoader();
        }
    }
}
