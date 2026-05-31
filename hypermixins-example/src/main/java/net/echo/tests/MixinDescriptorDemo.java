package net.echo.tests;

import net.echo.hypermixins.agent.MixinDescriptor;
import net.echo.hypermixins.config.MixinsConfig;

import java.util.List;

/**
 * Smoke test for the compile-time → runtime descriptor pipeline. No agent attach required:
 * loads the KSP-generated {@code WorldMixin$$Descriptor} via {@link MixinDescriptor#load} and
 * prints every column the runtime would consume during a real {@code HyperMixins.register}.
 * Also exercises the {@link MixinsConfig#discoverAll classpath YAML scan}.
 * <p>
 * Run with {@code java -cp <runtime+example jars> net.echo.tests.MixinDescriptorDemo}.
 */
public final class MixinDescriptorDemo {

    private MixinDescriptorDemo() {}

    static void main() throws Exception {
        dump(MixinDescriptor.load(WorldMixin.class));
        System.out.println();
        dump(MixinDescriptor.load(WorldExtrasMixin.class));

        System.out.println();
        System.out.println("Discovered YAMLs on classpath:");
        List<MixinsConfig> configs = MixinsConfig.discoverAll(
            MixinDescriptorDemo.class.getClassLoader());
        for (MixinsConfig c : configs) {
            System.out.println("  package=" + c.packageName() + " mixins=" + c.mixinClassNames());
        }
    }

    private static void dump(MixinDescriptor descriptor) {
        System.out.println("mixinClass:  " + descriptor.mixinClass().getName());
        System.out.println("targetClass: " + descriptor.targetClass());

        System.out.println("overwrites:");
        for (MixinDescriptor.OverwriteEntry e : descriptor.overwrites()) {
            System.out.println("  " + e);
        }
        System.out.println("originals:");
        for (MixinDescriptor.OriginalEntry e : descriptor.originals()) {
            System.out.println("  " + e);
        }
        System.out.println("redirects:");
        for (MixinDescriptor.RedirectEntry e : descriptor.redirects()) {
            System.out.println("  " + e);
        }
        System.out.println("injects:");
        for (MixinDescriptor.InjectEntry e : descriptor.injects()) {
            System.out.println("  " + e);
        }
        System.out.println("synthetics:");
        descriptor.synthetics().forEach((k, v) ->
            System.out.println("  " + k + " -> " + v[0] + " / " + v[1]));
    }
}
