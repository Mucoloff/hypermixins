# hypermixins-example

End-to-end demonstration of HyperMixins against a pre-built target jar.

## Pieces

| File | Role |
|---|---|
| `run/test-world-1.0.jar` | Pre-built target. Ships `net.echo.testworld.{World, Player, Start}`. Not built from this repo; treat as opaque external dependency the mixins target. |
| `src/main/java/net/echo/tests/WorldMixin.java` | Mixin class: `@Overwrite("getPlayers")`, `@Original`, `@Redirect` on `Thread.sleep`, `@Inject(HEAD)` on `addPlayer`. |
| `src/main/java/net/echo/tests/MixinTest.java` | Premain entry that registers `WorldMixin` programmatically. Used when the example jar itself is the agent. |
| `src/main/java/net/echo/tests/MixinDescriptorDemo.java` | `main` that loads `WorldMixin$$Descriptor` via `MixinDescriptor.load` and prints every entry table — proves the compile-time → runtime descriptor pipeline without attaching an agent. |
| `src/test/java/net/echo/tests/WorldMixinDescriptorTest.java` | JUnit assertion of the same. Runs on every `:hypermixins-example:test`. |

## Build

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew :hypermixins-example:jar
```

After the build, KSP has emitted:

```
build/generated/ksp/main/java/net/echo/tests/WorldMixin$$Descriptor.java
build/generated/ksp/main/resources/META-INF/hypermixins/net-echo-tests.mixins.yml
```

Both end up inside the published `hypermixins-example-<version>.jar`.

## Smoke runs

### No agent — descriptor pipeline only

```bash
java -cp \
  hypermixins-example/build/libs/hypermixins-example-1.2.jar:\
hypermixins-runtime/build/libs/hypermixins-runtime-1.2.jar:\
hypermixins-annotations/build/libs/hypermixins-annotations-1.2.jar:\
hypermixins-example/run/test-world-1.0.jar \
  net.echo.tests.MixinDescriptorDemo
```

Prints `mixinClass`, `targetClass`, every `*Entries()` table, and the
discovered YAML config. Useful to confirm KSP output is intact before wiring
the agent.

### With agent — real-world runtime rewrite

```bash
java -javaagent:hypermixins-agent/build/libs/hypermixins-agent-1.2.jar \
  -cp hypermixins-example/build/libs/hypermixins-example-1.2.jar:\
hypermixins-example/run/test-world-1.0.jar \
  net.echo.testworld.Start
```

Expected output:

```
[hypermixins] addPlayer intercepted       # @Inject HEAD on addPlayer fires
[hypermixins] addPlayer intercepted
There are 3 players
echo
matty
shelter                                    # @Overwrite getPlayers added it
```

The agent jar's premain scans every `META-INF/hypermixins/*.mixins.yml` on the
classpath; the example jar's auto-generated YAML lists `WorldMixin` and the
agent registers it.
