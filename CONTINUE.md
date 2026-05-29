# HyperMixins — Continuation Context

## Architecture

```
KSP processor (compile time)              Runtime
─────────────────────────────             ─────────────────────────────────
@Mixin/@Overwrite/@Original/...    ──►    MixinDescriptor.load(Class)
   │                                      ▲
   ├─►  <MixinFQN>$$Descriptor.java       │ (no annotation reflection)
   │      mixinClass()                    │
   │      targetClass()                   │
   │      overwriteEntries()              │
   │      originalEntries()               │
   │      redirectEntries()               │
   │      injectEntries()                 │
   │      syntheticNames()      ──────────┘
   │
   └─►  META-INF/hypermixins/<pkg>.mixins.yml
              │
              ▼
        MixinsConfig.discoverAll(loader)
              │
              ▼
        HyperMixins.registerFromClasspath
              │
              ▼
        MixinTransformer  ──►  ASM rewrite (INVOKEDYNAMIC)
              │
              ▼
        MixinRegistry (MutableCallSite per key)
              │
              ▼
        MixinHandle (enable / disable / unregister)
```

## Conventions

### Generated descriptor ABI
Column layout is locked between processor and runtime; changing it requires
updating both `MixinSymbolProcessor.kt` and `MixinDescriptor.java` in lock-step.

```
overwriteEntries:  [targetName, targetDesc, handlerName, handlerDesc]
originalEntries:   [handlerName, handlerDesc, targetName]
redirectEntries:   [targetMethod, invokeDesc, index, call, handlerName, handlerDesc]
injectEntries:     [targetMethod, point, atDesc, atIndex,
                    cancellable, returnable, handlerName, handlerDesc]
shadowEntries:     [handlerName, handlerDesc, targetName]
syntheticNames:    [targetName, targetDesc, mangledOriginalName, dispatchName]
```

### `.mixins.yml` layout (sponge-style)
Each module emits one YAML per package under
`META-INF/hypermixins/<package-with-dashes>.mixins.yml`. Runtime
`MixinsConfig.discoverAll` scans every `*.mixins.yml` under that directory across
all classpath roots (file + jar), plus the legacy root-level `mixins.yml` /
`.mixins.yml`.

### Hot-reload primitives
- `MixinRegistry.uninstall(key)` — steer call-site back to original, forget mixin.
- `MixinRegistry.reinstall(key, MethodHandle)` — atomic swap to a fresh handler.
- `MixinHandle.unregister()` — full cleanup: uninstall all keys + removeTransformer.

A high-level `MixinHandle.reload(Instrumentation, Class<?>)` is **not** shipped:
swapping to a different mixin Class<?> would require retransforming targets whose
`__mixin$X` field is typed against the old class. Use `uninstall` + a fresh
`register` for that scenario, or redefine the existing class's bytecode in place
(virtual dispatch picks up the new code automatically — no reinstall needed).

## Modules

| Module | Purpose |
|---|---|
| `hypermixins-annotations` | `@Mixin`, `@Overwrite`, `@Original`, `@Redirect`, `@Inject`, `@At`, `CallbackInfo[Returnable]` |
| `hypermixins-processor` | KSP processor: emits `$$Descriptor` + `META-INF/hypermixins/*.mixins.yml` |
| `hypermixins-runtime` | `MixinDescriptor`, `MixinTransformer`, `MixinRegistry`, `MixinHandle`, `HyperMixins`, `MixinsConfig` |
| `hypermixins-example` | `WorldMixin` + `MixinDescriptorDemo` smoke main |
| `hypermixins-intellij-plugin` | F1-F4 (gutter, inspections, completion). Needs IntelliJ IDEA installed locally to build |

The legacy `hypermixins-api` module is removed — runtime is the single source of truth.

## Build

```bash
export JAVA_HOME=/home/sweety/.local/jdks/jdk-25.0.3+9   # KSP needs the JDK that built the processor
./gradlew :hypermixins-runtime:test                       # 20 tests: dispatch, inject, reload, YAML
./gradlew :hypermixins-example:jar                        # KSP + descriptor + YAML
./gradlew :hypermixins-processor:jar                      # processor only
./gradlew :hypermixins-intellij-plugin:buildPlugin        # needs IDEA install at the path in build.gradle.kts
```

Smoke (no agent):
```bash
java -cp \
  hypermixins-example/build/libs/hypermixins-example-1.2.jar:\
hypermixins-runtime/build/libs/hypermixins-runtime-1.2.jar:\
hypermixins-annotations/build/libs/hypermixins-annotations-1.2.jar:\
hypermixins-example/run/test-world-1.0.jar \
  net.echo.tests.MixinDescriptorDemo
```

## Backlog

### Open
- **True `MixinHandle.reload(Class<?>)` across different mixin Class instances** —
  documented limitation: the `__mixin$X` field on each target is typed against
  the original mixin Class, so swapping to a different Class would require
  retransforming the target. Workaround: redefine the existing Class's
  bytecode in place (virtual dispatch picks up new code) or call uninstall +
  fresh register.
- **Pure processor unit tests** via `dev.zacsweers.kctfork` — blocked on Kotlin
  2.3.20 compat (0.7.1 has opt-in propagation bug; 0.5.1 lacks
  `ExperimentalCompilerApi` marker and uses an older API shape). Integration
  test `WorldMixinDescriptorTest` in `hypermixins-example` covers the descriptor
  + YAML emission end-to-end as a substitute.
- **`@ModifyReceiver`** — annotation surface exists; runtime support
  deferred. Needs a SWAP/DUP_X1 stack-shuffle dance under existing args.
  `@ModifyArgs` ships for reference-typed arg lists (primitive args still
  rejected at transform time). `@ModifyExpressionValue` ships for INVOKE
  / FIELD / CONSTANT.
- **Private-target `@Shadow` / `@Invoker`** — requires a public synthetic
  accessor on the target class so the trampoline's INVOKEVIRTUAL clears the
  access check.
- **Local-variable capture beyond target parameters** — capture locals
  declared inside the target body, not just incoming params.
- **Field-level `@Shadow`** — v1 implements method shadows only; field
  forwarding would need ASM rewrites on every `GETFIELD/PUTFIELD` in the
  mixin pointing at a `@Shadow` field.

## Key files

- Processor: `hypermixins-processor/src/main/kotlin/.../MixinSymbolProcessor.kt`
- Descriptor loader: `hypermixins-runtime/src/main/java/.../agent/MixinDescriptor.java`
- Transformer: `hypermixins-runtime/src/main/java/.../agent/MixinTransformer.java`
- Registry: `hypermixins-runtime/src/main/java/.../registry/MixinRegistry.java`
- YAML loader: `hypermixins-runtime/src/main/java/.../config/MixinsConfig.java`
- Example mixin: `hypermixins-example/src/main/java/net/echo/tests/WorldMixin.java`
- Smoke main: `hypermixins-example/src/main/java/net/echo/tests/MixinDescriptorDemo.java`
- Style guide: `style.md`
