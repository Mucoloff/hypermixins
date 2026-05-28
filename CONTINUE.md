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

### Medium
- True `MixinHandle.reload(Instrumentation, Class<?>)` — see hot-reload note above.
- Processor unit tests — first attempt with `dev.zacsweers.kctfork:core:0.7.1`
  failed: opt-in markers for `KotlinCompilation` / `JvmCompilationResult` do not
  propagate against Kotlin 2.3.20 (every member read flagged "experimental"
  despite `@file:OptIn` + `compilerOptions.optIn`). Retry with a kctfork version
  built against Kotlin 2.3, or wait for the canonical
  `com.squareup.tools.testing:compile-testing` KSP support.

### Bigger
- `@At.Point.INVOKE / FIELD / CONSTANT / JUMP` support for `@Inject`.
- `@Inject` local-capture: forward target locals beyond `Object self` + `CallbackInfo*`.
- Standalone `hypermixins-agent` shadow jar with `Premain-Class` calling
  `HyperMixins.registerFromClasspath(inst)` — drop-in `-javaagent:` for users.

## Key files

- Processor: `hypermixins-processor/src/main/kotlin/.../MixinSymbolProcessor.kt`
- Descriptor loader: `hypermixins-runtime/src/main/java/.../agent/MixinDescriptor.java`
- Transformer: `hypermixins-runtime/src/main/java/.../agent/MixinTransformer.java`
- Registry: `hypermixins-runtime/src/main/java/.../registry/MixinRegistry.java`
- YAML loader: `hypermixins-runtime/src/main/java/.../config/MixinsConfig.java`
- Example mixin: `hypermixins-example/src/main/java/net/echo/tests/WorldMixin.java`
- Smoke main: `hypermixins-example/src/main/java/net/echo/tests/MixinDescriptorDemo.java`
- Style guide: `style.md`
