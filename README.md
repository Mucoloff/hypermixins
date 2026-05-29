# HyperMixins

Lightweight mixin framework for the JVM. Compile-time annotation processing (KSP) +
runtime ASM bytecode rewriting + atomic INVOKEDYNAMIC dispatch.

- **Zero runtime annotation reflection** — KSP bakes every entry into a
  `<MixinFQN>$$Descriptor` class consumed by the runtime.
- **Drop-in `-javaagent:`** — `hypermixins-agent` shadow jar auto-discovers every
  `META-INF/hypermixins/*.mixins.yml` on the classpath; host application needs zero
  glue code.
- **Hot-swap** — `MixinHandle.enable() / disable()` flip dispatch atomically via
  `MutableCallSite`; `MixinRegistry.uninstall / reinstall` swap handlers without
  retransforming the target.
- **Layered mixins** — multiple mixin classes may target the same class
  (overwrite + inject from independent mods compose cleanly).

## Quick start

### 1. Add the dependencies

```kotlin
dependencies {
    implementation("net.echo:hypermixins-annotations:1.2")
    implementation("net.echo:hypermixins-runtime:1.2")
    ksp("net.echo:hypermixins-processor:1.2")
}
```

### 2. Write a mixin

```java
@Mixin("net.echo.testworld.World")
public class WorldMixin {

    @Original("getPlayers")
    public native List<Player> getPlayersOrig(Object self);

    @Overwrite("getPlayers")
    public List<Player> getPlayers(Object self) {
        List<Player> all = getPlayersOrig(self);
        all.add(new Player("shelter"));
        return all;
    }

    @Inject(method = "addPlayer", at = @At(point = At.Point.HEAD))
    public void onAddPlayer(Object self) {
        System.out.println("[mixin] addPlayer intercepted");
    }
}
```

KSP emits:
- `WorldMixin$$Descriptor.java` — fully baked entry tables (overwrite, original,
  redirect, inject, synthetic names).
- `META-INF/hypermixins/<your-package>.mixins.yml` — sponge-style manifest
  listing every `@Mixin` class in that package.

### 3. Attach the agent

```bash
java -javaagent:hypermixins-agent.jar -cp app.jar com.example.Main
```

The agent scans every `*.mixins.yml` under `META-INF/hypermixins/` across the
classpath and calls `HyperMixins.registerFromClasspath(inst)` automatically.

The agent accepts one optional argument:

```bash
java -javaagent:hypermixins-agent.jar=com.example.SomeAppClass -cp app.jar com.example.Main
```

The argument is a fully qualified class name; its `ClassLoader` is used as the
search root for `META-INF/hypermixins/*.mixins.yml`. Omit it to scan the system
class loader.

Programmatic registration if you prefer:

```java
public static void premain(String args, Instrumentation inst) throws Exception {
    HyperMixins.register(inst, WorldMixin.class);
}
```

## Annotations

| Annotation | Effect |
|---|---|
| `@Mixin("fqn")` | Marks the class as a mixin targeting `fqn`. No-arg ctor required. |
| `@Overwrite("name")` | Replaces the target method's body with the handler. First param must be `Object self`. |
| `@Original("name")` | Trampoline back to the un-mixed implementation. Declare as `native`. |
| `@Redirect(method, at)` | Replaces an invoke / field site inside `method` with a static handler. |
| `@Inject(method, at, cancellable)` | Side-effect hook at HEAD / RETURN / TAIL / INVOKE / FIELD / CONSTANT / JUMP. |
| `@Cancellable` | Shorthand for `@Inject(..., cancellable=true)`. |
| `@Shadow("name")` | Forwards a `native` mixin method to a method on the target class. Also forwards `this.field` access on an `@Shadow` instance field to the target's field. |
| `@Local(index = N)` | On an `@Inject` handler param: capture the target's local at slot `N` directly instead of relying on positional capture. |

Inject handlers may capture target parameters positionally between `Object self`
and the optional trailing `CallbackInfo[Returnable]`:

```java
@Inject(method = "compute", at = @At(point = At.Point.HEAD), cancellable = true)
public void onCompute(Object self, int x, CallbackInfoReturnable<Integer> cir) {
    if (x < 0) cir.setReturnValue(-1);
}
```

## Architecture

```
KSP (compile time)                Runtime
───────────────────               ──────────────────────────────────
@Mixin/@Overwrite/...   ──►       (annotations never read at runtime)
   │
   ├─►  <MixinFQN>$$Descriptor.java
   │       mixinClass()
   │       targetClass()
   │       overwriteEntries()
   │       originalEntries()
   │       redirectEntries()
   │       injectEntries()
   │       syntheticNames()    ──►  MixinDescriptor.load(Class)
   │                                       │
   └─►  META-INF/hypermixins/                 │
        <pkg>.mixins.yml      ──►  MixinsConfig.discoverAll
                                              │
                                              ▼
                                  HyperMixins.registerFromClasspath
                                              │
                                              ▼
                                  MixinTransformer  ──► ASM (INVOKEDYNAMIC)
                                              │
                                              ▼
                                  MixinRegistry (MutableCallSite per key)
                                              │
                                              ▼
                                  MixinHandle (enable / disable / unregister)
```

See [CONTINUE.md](CONTINUE.md) for the descriptor ABI, build commands, and backlog.

## Modules

| Module | Purpose |
|---|---|
| `hypermixins-annotations` | Annotation surface (`@Mixin`, `@Overwrite`, `@At`, ...). Pure Java, zero deps. |
| `hypermixins-processor` | KSP processor: emits `$$Descriptor` + YAML. |
| `hypermixins-runtime` | `MixinDescriptor`, `MixinTransformer`, `MixinRegistry`, `MixinHandle`, `HyperMixins`. |
| `hypermixins-agent` | Drop-in `-javaagent:` shadow jar with `Premain-Class` calling `registerFromClasspath`. |
| `hypermixins-example` | `WorldMixin` + smoke main + descriptor integration test. |
| `hypermixins-intellij-plugin` | IDEA plugin: gutter navigation, inspections, completion. |

## Status

- 27 runtime tests + 2 example tests green.
- Supported `@At.Point` for `@Inject`: HEAD, RETURN, TAIL, INVOKE, FIELD,
  CONSTANT (LDC values), JUMP (conditional), NEW (object allocations).
- `@At#shift = BEFORE | AFTER` lets handlers anchor either side of the
  matched instruction.
- Layered mixins (multiple `@Mixin` classes on the same target) compose for
  overwrites + injects; duplicate overwrites on the same key are rejected at
  transform time.

## License

MIT.
