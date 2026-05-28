package net.echo.hypermixins.agent;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spawns a child JVM with the real {@code -javaagent:hypermixins-agent.jar} pointing at the
 * shadow jar produced by Gradle, with the example + test-world jars on the classpath, and
 * checks that the agent auto-registers the mixin and the host class behaviour shifts.
 */
class HyperMixinsAgentTest {

    @Test
    void agentPremainAutoRegistersFromClasspathYaml() throws Exception {
        String agentJar    = sysProp("hypermixins.agent.jar");
        String exampleJar  = sysProp("hypermixins.example.jar");
        String testworld   = sysProp("hypermixins.testworld.jar");
        String javaHome    = sysProp("hypermixins.java.home");

        File java = new File(javaHome, "bin/java");
        assertTrue(java.canExecute(), "java launcher not found at " + java);

        String cp = exampleJar + File.pathSeparator + testworld;
        ProcessBuilder pb = new ProcessBuilder(
            java.getAbsolutePath(),
            "-javaagent:" + agentJar,
            "-cp", cp,
            "net.echo.testworld.Start"
        ).redirectErrorStream(true);

        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        assertTrue(finished, "child JVM did not exit within 60s; output so far:\n" + out);
        assertEquals(0, p.exitValue(), "child JVM failed; output:\n" + out);

        String stdout = out.toString();
        assertTrue(stdout.contains("[hypermixins] addPlayer intercepted"),
            "@Inject HEAD did not fire in child JVM; output:\n" + stdout);
        assertTrue(stdout.contains("shelter"),
            "@Overwrite did not extend getPlayers in child JVM; output:\n" + stdout);
    }

    private static String sysProp(String key) {
        String v = System.getProperty(key);
        assertNotNull(v, "missing system property " + key + " — see hypermixins-agent/build.gradle.kts");
        return v;
    }
}
