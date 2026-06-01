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

## Why HyperMixins

If you know SpongePowered Mixin / MixinExtras, the model is familiar:
`@Overwrite` / `@Inject` / `@Redirect` / `@Shadow` plus a MixinExtras-style
`@Definition` + `@Expression` DSL for matching call / field / comparison /
boolean sites by *shape* rather than a hand-counted `@At` index.

What's different:

- **No reflection at runtime.** Mixin entries are baked into a generated
  `$$Descriptor` at compile time by a KSP processor; the runtime never reads
  an annotation. Lower per-class load cost, no annotation classes pinned.
- **`INVOKEDYNAMIC` dispatch with hot-swap.** `@Overwrite` routes through a
  `MutableCallSite`, so a handler can be disabled / enabled / reinstalled at
  runtime without retransforming the target.
- **Self-contained.** ASM is shaded into the runtime jar — one dependency, no
  transitive ASM version clash with the host.
- **Plain JVM, not Minecraft-coupled.** No mod-loader assumptions; attach the
  agent to any JVM process.

Use it when you need to patch a class you don't own (a library, a closed
artifact) and want the patch to survive target refactors better than a raw
descriptor string would.

## Quick start

### 1. Add the dependencies

Published via [JitPack](https://jitpack.io/#Mucoloff/hypermixins). Add the
repository, then depend on the three modules at a released tag (e.g. `1.7`):

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.Mucoloff.hypermixins:hypermixins-annotations:1.7")
    implementation("com.github.Mucoloff.hypermixins:hypermixins-runtime:1.7")
    ksp("com.github.Mucoloff.hypermixins:hypermixins-processor:1.7")
}
```

(Maven: group `com.github.Mucoloff.hypermixins`, artifact = module name,
version = git tag.)

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

| Annotation                         | Effect                                                                                                                                                                                                                |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@Mixin("fqn")`                    | Marks the class as a mixin targeting `fqn`. No-arg ctor required.                                                                                                                                                     |
| `@Overwrite("name")`               | Replaces the target method's body with the handler. First param must be `Object self`.                                                                                                                                |
| `@Original("name")`                | Trampoline back to the un-mixed implementation. Declare as `native`.                                                                                                                                                  |
| `@Redirect(method, at)`            | Replaces an invoke / field site inside `method` with a static handler.                                                                                                                                                |
| `@Inject(method, at, cancellable)` | Side-effect hook at HEAD / RETURN / TAIL / INVOKE / FIELD / CONSTANT / JUMP.                                                                                                                                          |
| `@Cancellable`                     | Shorthand for `@Inject(..., cancellable=true)`.                                                                                                                                                                       |
| `@Shadow("name")`                  | Forwards a `native` mixin method to a method on the target class. Also forwards `this.field` access on an `@Shadow` instance field to the target's field. Supports `prefix = "..."` for Sponge-style name resolution. |
| `@Final` / `@Mutable`              | On a `@Shadow` field: `@Final` rejects mixin-side writes at transform; `@Mutable` opts back in (used together when the contract says the field is final but the mixin needs to mutate it).                            |
| `@Soft`                            | On `@Shadow` / `@Invoker`: optional target. Replaces trampoline body with a UOE-throw when the target is absent so the rest of the mixin still loads.                                                                 |
| `@Implements({Foo.class,...})`     | Class-level on the `@Mixin` class: adds each interface to the target's `interfaces` list at transform time.                                                                                                           |
| `@Slice(from = @At, to = @At)`     | On an `@Inject` handler: constrains the site scan to an [from, to] instruction-index window. Either side defaults to `@At(HEAD)` for "no bound".                                                                      |
| `@Unique`                          | Copies the helper into the target under `__unique$<mixin>$<name>$<hash>`. Static helpers keep the original descriptor; instance helpers gain an `Object self` prepended slot. Callers in mixin handler bodies (`@Overwrite` / `@Inject` / `@ModifyXxx`) automatically dispatch through the merged target copy when the receiver is `this` — no reflection needed. Instance helpers must be self-contained — references to the mixin class itself are rejected at transform. |
| `@Surrogate` on `@Inject`          | Sibling fallback handler. The runtime tries the primary `@Inject` first; on a capture / `@Local` / slot-resolution failure it retries each `@Surrogate` in declared order. Optional `value()` narrows by primary handler name. Site-matching failures remain fatal.                                                                                  |
| `@Definition` + `@Expression` (v5) | DSL site selector for `@At(point = EXPRESSION)`. `@Definition(id, method/field)` aliases a JVM signature; `@Expression` selects matching instructions. Grammar: unchained `id(?, ?)` / `id`; this-qualified `this.field` / `this.method(?)`; multi-segment chains `a.b().c(?)`; assignment `this.field = ?`; named captures `id(x, y)` (binds by handler param name via `-parameters`); arithmetic `? + ?` / `? - ?` / `? * ?` / `? / ?` (matches IADD-family); comparisons `? == ?` / `? != ?` / `? < ?` / `? <= ?` / `? > ?` / `? >= ?` (matches IF_ICMP/IF_ACMP both branch directions); literal args `accept(42)` / `accept("hi")` / `accept(true)` / `accept(null)`; type checks `? instanceof TypeId` and casts `(TypeId) ?` (via `@Definition(id, type)`). `?` placeholders bind positionally to handler params after `Object self`. Inner-chain captures, capture-through-arithmetic, boolean combinators are v6. |
| `@WrapWithCondition`               | On a static method returning `boolean`: scans the target body for the matched INVOKE / FIELD site and skips it when the handler returns `false` (default value is pushed for the original return type).               |
| `@WrapOperation`                   | On a static method: wraps a matched INVOKE site with an `Operation<R>` lambda; handler can call `op.call(...)` zero / one / many times.                                                                               |
| `@WrapMethod("name")`              | On an instance method: wraps the entire target method body; handler receives the original args plus an `Operation<R>` to invoke the saved body.                                                                       |
| `@At(shift = Shift.BY, by = N)`    | Signed offset shift for `@Inject` anchoring: walks `by` instructions forward (positive) / backward (negative) from the matched site before inserting the handler block.                                               |
| `@Coerce` on a handler parameter   | Paired with `@Local`: relaxes the type-equality match to `assignableFrom` so a handler can bind a concrete target local through a wider reference.                                                                    |
| `@Inject(require, allow)`          | Match-count enforcement: `require` rejects too-few sites, `allow` rejects too-many. Useful for refactor assertions; both default to "disabled".                                                                       |
| `@Share("key")` on `@Inject` param | Shared mutable `Ref` cell keyed by `key`. Multiple `@Inject` handlers in the same JVM can exchange state via the same key.                                                                                            |
| `@Local(index = N, ordinal = K, argsOnly = boolean)` | On an `@Inject` handler param: bind to a specific target slot (`index`), the `K`-th live local of the parameter's type at the injection site (`ordinal`), or — bare — the unique live local of that type. Type-driven resolution at non-HEAD points walks the target's `LocalVariableTable` (`-g`). `argsOnly = true` requires the handler param to be a single-element array; the handler may mutate `arr[0]` and the value is written back into the source slot before the matched site reads it. |
| `@ModifyReturnValue`               | Static handler `T (T)` wraps the return value of a specific INVOKE inside the target method.                                                                                                                          |
| `@ModifyConstant`                  | Static handler `T (T)` replaces a numeric / String constant load matching the typed `@Constant` clause.                                                                                                               |
| `@ModifyArg`                       | Static handler `T (T)` replaces the last argument of a specific INVOKE call site (v1 last-arg only).                                                                                                                  |
| `@Accessor`                        | Native mixin method becomes a `GETFIELD`/`PUTFIELD` trampoline; field name auto-resolves from the `getFoo`/`setFoo`/`isFoo` convention.                                                                               |
| `@Invoker`                         | Native mixin method becomes an `INVOKEVIRTUAL` trampoline to a named target method (auto-derives via `invokeFoo`/`callFoo`). Supports private targets via a generated public synthetic accessor.                      |
| `@ModifyExpressionValue`           | Static handler `T (T)` wraps the value produced by an INVOKE / FIELD / CONSTANT site.                                                                                                                                 |
| `@ModifyArgs`                      | Static handler `void(Object[])` mutates every reference-typed argument of a matched INVOKE before it fires.                                                                                                           |

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
See [docs/expression.md](docs/expression.md) for the full `@Expression` DSL grammar with runnable examples.

## Modules

| Module                        | Purpose                                                                                |
|-------------------------------|----------------------------------------------------------------------------------------|
| `hypermixins-annotations`     | Annotation surface (`@Mixin`, `@Overwrite`, `@At`, ...). Pure Java, zero deps.         |
| `hypermixins-processor`       | KSP processor: emits `$$Descriptor` + YAML.                                            |
| `hypermixins-runtime`         | `MixinDescriptor`, `MixinTransformer`, `MixinRegistry`, `MixinHandle`, `HyperMixins`.  |
| `hypermixins-agent`           | Drop-in `-javaagent:` shadow jar with `Premain-Class` calling `registerFromClasspath`. |
| `hypermixins-example`         | `WorldMixin` + smoke main + descriptor integration test.                               |
| `hypermixins-intellij-plugin` | IDEA plugin: gutter navigation, inspections, completion.                               |

## Status

- 170 runtime tests + 6 processor tests + 5 example tests + 1 agent test green (1.7).
- Supported `@At.Point` for `@Inject`: HEAD, RETURN, TAIL, INVOKE, FIELD,
  CONSTANT (LDC values), JUMP (conditional), NEW (object allocations).
- `@At#shift = BEFORE | AFTER` lets handlers anchor either side of the
  matched instruction.
- Layered mixins (multiple `@Mixin` classes on the same target) compose for
  overwrites + injects; duplicate overwrites on the same key are rejected at
  transform time.

## Limitations

- **`@Expression` matches the `if`-condition short-circuit shape.** A
  comparison / boolean expression is recognised where javac emits it as a
  branch (`if`, `while`, ternary condition). The boolean-materialisation
  form (`boolean r = a && b;`, ICONST/GOTO) is not matched.
- **`@Local` at non-HEAD points needs `-g`.** Type-/ordinal-driven local
  capture mid-method reads the target's `LocalVariableTable`; targets
  compiled without debug info must use `@Local(index = …)`.
- **Instance `@Unique` helpers must be self-contained** — references to the
  mixin class itself are rejected at transform.
- **`MixinHandle.reload(Class<?>)` across a *different* mixin class is not
  shipped** — the `__mixin$X` field is typed against the original class; use
  `uninstall` + a fresh `register`, or redefine the class bytecode in place.
- **JDK 25 toolchain** required to build (KSP + `-parameters`); consumers run
  on any JVM the agent attaches to.

## License

MIT — see [LICENSE](LICENSE).
