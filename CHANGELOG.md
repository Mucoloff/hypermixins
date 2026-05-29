# Changelog

All notable changes are listed here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). No release tags yet —
master is the only published surface so far.

## Unreleased

### Added
- **Compile-time descriptor pipeline.** KSP processor emits `<MixinFQN>$$Descriptor.java`
  with pre-computed `overwriteEntries`, `originalEntries`, `redirectEntries`,
  `injectEntries`, `syntheticNames`, and `mixinClass()` / `targetClass()`.
  Runtime `MixinDescriptor.load` reads these directly — zero annotation
  reflection on the production path.
- **Sponge-style `*.mixins.yml` discovery.** Processor auto-emits
  `META-INF/hypermixins/<pkg-slug>.mixins.yml` per package; runtime
  `MixinsConfig.discoverAll` wildcards every `*.mixins.yml` under
  `META-INF/hypermixins/` across file + jar classpath roots.
- **Drop-in `-javaagent:` module** (`hypermixins-agent`). Premain + agentmain
  call `HyperMixins.registerFromClasspath(inst)` with no host-side glue.
  Optional CLI argument names a class whose ClassLoader is used for discovery.
- **`@Inject` HEAD / RETURN / TAIL / INVOKE / FIELD / CONSTANT / JUMP** with
  optional `cancellable` + `CallbackInfo[Returnable]` and positional
  target-parameter capture.
- **Layered mixins.** Multiple `@Mixin` classes may target the same class;
  overwrites + injects from independent mods compose. Duplicate overwrites on
  the same target method are rejected before any retransform with a message
  naming both offending mixins.
- **Hot-swap primitives.** `MixinRegistry.uninstall(key)` /
  `reinstall(key, MethodHandle)` flip the call-site atomically without
  retransforming the target.
- **Per-Class descriptor cache** in `MixinDescriptor.load`.
- **CI workflow** scoped to runtime + example + agent modules (IntelliJ plugin
  module skipped — requires local IDEA install).

### Removed
- Legacy `hypermixins-api` module. Runtime is the single source of truth.

### Fixed
- `MixinRegistry.bootstrap` no longer leaves the call-site at the JVM-provided
  uninitialized sentinel target — installs the resolved handle immediately
  after lazy resolution.
- KSP processor maps `java.util.List/Map/Set/Collection` + Kotlin equivalents
  to proper JVM descriptors (was emitting `Lkotlin/collections/MutableList;`).
- KSP processor reads enum annotation arguments (`@At#call`, `@At#point`)
  robustly across `KSType` / `KSClassDeclaration` / `String` shapes —
  `Call.INVOKESTATIC` no longer fell back to default `INVOKEVIRTUAL`.

### Added (phase A–C)
- **Field-level `@Shadow`** — mixins may declare `@Shadow private int foo;`
  and reference it as `this.foo` inside `@Overwrite` / `@Inject` handlers;
  the transformer rewrites the canonical `ALOAD 0; GETFIELD/PUTFIELD` pattern
  to access the target's field via the `Object self` parameter.
- **`@Local(index = N)`** — `@Inject` handler parameters may opt into reading
  a specific local slot of the target method instead of the positional
  default. Mixed positional + `@Local` in the same handler is allowed.
- **`@Overwrite` on static target methods** — the transformer adds a static
  mixin instance field on the target class, initializes it in `<clinit>`,
  emits static `__original$` / `__dispatch$` synthetics, and the registry
  bootstrap falls back to `findStatic` lazy install for the resulting
  static call-site shape.
- **`@Original` on static target methods** — works through both the legacy
  reflection path (`MixinDescriptor.fromAnnotations` reflects on the target)
  and the production KSP descriptor path. The processor probes the target
  class via the KSP `Resolver` and emits a `staticTargetMethods()` table on
  the `$$Descriptor`; the runtime merges it into the descriptor's
  `staticTargetMethods` map. The transformer emits an `INVOKESTATIC`
  trampoline (skipping the `ALOAD 1; CHECKCAST` self prologue) whenever the
  map flags a static target. Targets that the resolver cannot see at compile
  time fall back to the instance dispatch — same as before.

### Documentation
- New `README.md` with quick start, annotation reference, architecture diagram,
  module table.
- New `hypermixins-example/README.md` documenting smoke runs.
- `CONTINUE.md` rewritten around the descriptor pipeline.
