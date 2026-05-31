# Changelog

All notable changes are listed here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). No release tags yet —
master is the only published surface so far.

## Unreleased

### Added
- **`@Definition` + `@Expression` DSL (v1)** — MixinExtras-style site
  selector. `@Definition` (repeatable via `@Definitions`) aliases a
  method or field signature behind an `id`; `@Expression` references
  those ids in a small DSL. New `At.Point.EXPRESSION` routes through
  the parser + matcher. v1 scope: single-instruction expressions
  (method call or bare field reference), `?` as inert placeholder.
- **`@Expression` DSL v2** — extends the v1 grammar with the four
  pieces needed for realistic mixin authoring while keeping the
  single-instruction match model:
  - `this` keyword (receiver or call argument).
  - Chained access: `this.field`, `this.method(args)`. Receiver must
    be `ALOAD 0` for the match to succeed.
  - Assignment: `this.field = ?` / `field = ?` matches PUTFIELD only.
  - `?` placeholders bind positionally to handler params after
    `Object self`, mirroring `@Local`. Each `?` backwalks to a clean
    `*LOAD` predecessor — non-load producers throw
    `InjectSignatureMismatch` so `@Surrogate` can retry. When the
    handler has no capture params, `?` stays inert (v1 semantics).
- **`@Surrogate`** — fallback handler chain for `@Inject`. A sibling
  `@Inject`-annotated method also marked `@Surrogate` is collected at
  `MixinMapping` build time and attached to every primary on the same
  target method name (or narrowed via `@Surrogate(value=)`). When the
  primary fails capture / `@Local` / slot resolution at transform time
  (now a dedicated `InjectSignatureMismatch` subclass of
  `IllegalStateException`), `InjectPass` retries with each surrogate in
  order. Site-matching failures remain fatal — a silent fallthrough
  would hide real bugs. Surrogate failures are reported via
  `Throwable#addSuppressed` on the primary exception.
- **Instance `@Unique`** — `UniquePass` now accepts non-static `@Unique`
  helpers. The clone is a public static synthetic with `Object self`
  prepended (matching the `@Overwrite` dispatch convention). The
  implicit `this` slot is reused for `self` so no slot shift is needed.
  Limitation: the body must be self-contained — references to mixin
  fields / methods / type are rejected at transform with a clear error.
  Caller-side rewrite of instance-style invocations from other mixin
  bodies is deferred.

- **`@Inject(require, allow)`** — match-count enforcement. `require` is the
  minimum number of matched call sites (default 0 disables); `allow` is the
  maximum (negative default disables). Mismatches fail the transform with
  expected vs. actual counts. Read reflectively, no descriptor change.
- **`@Share(key)` on `@Inject` parameters** — shared mutable `Ref` cell
  identified by `key`, served by `SharedSlots.acquire`. Lets multiple
  handlers across mixins exchange state via the same key. CaptureEmitter
  emits the load; no descriptor schema change.

### Refactored
- `ReflectionCollectorChecks` consolidates the eight reflection
  collectors' duplicate validation prologue (non-empty `method()`, non-empty
  `@At#desc()`, static/non-static enforcement) into one helper.

### Added
- **`@At.Shift.BY` + `by()`** — signed instruction-offset shift for
  `@Inject` site anchoring. Walks the matched insn ± by() positions
  before inserting the handler block. Read reflectively from the
  handler Method; no descriptor schema change.
- **`@Coerce` on handler params** — paired with `@Local`, relaxes
  `InjectLocalResolver`'s strict type-equality check to an
  `assignableFrom` check when both types are references. Primitives
  ignore the marker. Read reflectively from the handler Method.
- **`Args` wrapper as a second `@ModifyArgs` handler shape** —
  `static void handler(Args args)` is now legal alongside the existing
  raw `Object[]` form. `Args.set(int, Object)` mutates the same backing
  array so the post-handler reload still observes the change.

## 1.4 — 2026-05-31

### Added
- **`@WrapWithCondition`** — conditional suppression of an `@At`-matched
  INVOKE / FIELD call site. Handler returns `boolean`; `true` lets the
  original site fire, `false` skips it and pushes the default value for
  the original return type. `index <= 0` wraps every match; `index > 0`
  selects the 1-based occurrence.
- **`@WrapOperation`** — wraps a single matched INVOKE site with an
  `Operation<R>` lambda. Handler receives the original args plus the
  lambda as the last param; `op.call(args...)` re-invokes the wrapped
  site with whatever args the handler hands in. Lambda is built via
  `INVOKEDYNAMIC` + `LambdaMetafactory` bound to a per-site static
  adapter that lives on the target class.
- **`@WrapMethod`** — wraps the entire target method. Original body is
  cloned into `__wrappedOrig$<name>$<hash>`; the method's body is
  rewritten to build the same `Operation<R>` lambda + `INVOKEVIRTUAL`
  the instance handler on the mixin class. Conflicts with `@Overwrite`
  on the same target are rejected at transform.

## 1.3 — 2026-05-31

### Added (Sponge-style annotation suite)
- **`@Soft` on `@Shadow` / `@Invoker`** — optional target binding. When the
  target method isn't on the target class at transform time the trampoline
  body is replaced with `throw new UnsupportedOperationException(...)` so
  the absence only surfaces at call time.
- **`@Implements({Foo.class, ...})`** — appends the listed interfaces to
  the target class's `interfaces` list at transform time.
- **`@Slice(from = @At(...), to = @At(...))`** — constrains `@Inject` site
  search to an [from, to] instruction-index window. Either side may stay at
  the default `@At(HEAD)` for "no bound on this side".
- **`@Unique` on static methods** — copies the static helper into the target
  class under `__unique$<mixin-flat>$<name>$<hash>`. Restricted to static
  for this release; instance `@Unique` deferred.

### Fixed
- **Compile-time validation gaps** on the `@Inject` and `@Original` collectors:
  the processor now rejects non-`Object`/`Any` first parameters and static
  `@Inject` declarations, matching the runtime reflection-fallback checks.
- **Reflection-fallback empty-string checks** on `@ModifyReceiver`,
  `@ModifyArgs`, `@ModifyExpressionValue`, `@ModifyArg`, `@ModifyConstant`,
  and `@ModifyReturnValue`. Empty `#method()` / `@At#desc()` now fail at
  descriptor load instead of producing entries that crashed at transform.
- **Processor bail-out** after `@Inject` cancellable-mismatch and
  `@Redirect` signature-mismatch errors so the malformed descriptor entry
  is no longer emitted alongside the diagnostic.
- **Wildcard / regex `@At#desc` exemption** on the `@Redirect` and
  `@ModifyReturnValue` signature checks (both processor and reflection
  paths) — DescriptorMatcher resolves the concrete shape at transform
  time, so the static handler-signature comparison can't be enforced.

### Added
- **`@Final` on `@Shadow` fields, with `@Mutable` opt-out.** Sponge-style
  marker enforced by ShadowFieldPass: `PUTFIELD` / `PUTSTATIC` on a
  `@Shadow @Final` mixin field throws at transform unless the same field
  carries `@Mutable`. Reads remain unaffected.
- **Frame-driven `@Local` at every non-HEAD `@Inject` point.** Ordinal and bare
  bindings now resolve through the target's preserved `LocalVariableTable`
  (`LocalFrameAnalyzer`), so a handler can pick mid-method locals at TAIL /
  RETURN / INVOKE / FIELD / CONSTANT / JUMP / NEW — not just incoming params.
  `@Local(argsOnly = true)` at non-HEAD points relocates the injection block
  to before the earliest preceding `*LOAD` of the source slot so the writeback
  lands before the consuming push.
- **Per-feature pass split** of `MixinTransformer` (1595 → 200 LOC),
  `MixinDescriptor` (884 → 318 LOC), and `MixinSymbolProcessor` (900 → 183
  LOC). Each annotation family lives in its own `*Pass` / `*Collector` /
  `*Reader` class — see `CONTINUE.md` for the new architecture map.
- **`style.md` §22 (Map<K, Boolean> → Set<K>) + §23 (no God classes)** with
  the codebase already conforming.

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

### Added (static @Shadow fields)
- **Static field-level `@Shadow`** — declare `@Shadow public static int foo;`
  on the mixin and reference it as `foo` (i.e., GETSTATIC/PUTSTATIC) inside
  `@Overwrite` / `@Inject` handlers; the transformer rewrites the owner to
  the target class. Companion to the existing instance field shadow.

### Added (extras)
- `@At.Point.NEW` — `@Inject` may hook object-allocation sites; the matcher
  walks `TypeInsnNode(NEW)` and compares the type owner against `@At#desc`.
- `@At#shift = BEFORE | AFTER` — the handler call may be inserted after the
  matched instruction instead of before it.
- `@Shadow(prefix = "...")` — when no explicit name is given, the prefix is
  stripped from the mixin's method or field name to derive the target name.
- `@ModifyReturnValue` — static `T (T)` handler that wraps the return value
  of a specific INVOKE inside the target method (companion to `@Redirect`).

### Added (annotation wave 2 — A → G phases)
- `@Accessor` — native mixin method resolves to a direct `GETFIELD`/`PUTFIELD`
  on the target via `Object self`. `getFoo`/`setFoo`/`isFoo` auto-derive.
- `@Invoker` — native mixin method resolves to an `INVOKEVIRTUAL` against the
  target. `invokeFoo`/`callFoo` auto-derive.
- `@ModifyConstant` — replaces matching `LDC` / `ICONST_n` / `BIPUSH` /
  `SIPUSH` / `LCONST_*` / `FCONST_*` / `DCONST_*` constants. v1 covers int,
  long, float, double, and String constants.
- `@ModifyArg` — replaces the last argument of a matched INVOKE call site
  (other indices rejected at transform time in v1).
- `@At#desc` wildcard / regex — `*` is treated as `.*` and `regex:<pat>`
  short-circuits to a Java `Pattern`. Wired into `@Inject`, `@Redirect`,
  `@ModifyReturnValue`, `@ModifyArg`, `@ModifyExpressionValue`, and
  `@ModifyArgs`.

### Added (annotation wave 3 — modify / accessor expansion)
- `@ModifyExpressionValue` — generalises `@ModifyReturnValue` over
  `INVOKE`, `FIELD`, and `CONSTANT` producing instructions.
- `@ModifyArgs` — handler `void(Object[])` may mutate every
  reference-typed arg of a matched INVOKE in place (primitive args still
  rejected at transform time).
- **Private-target `@Shadow` / `@Invoker`** — private target methods are
  reached via a public synthetic `__access$<name>$<hash>` method generated
  on the target. KSP probes the target via the resolver; the legacy
  reflection path uses `Class.forName`.

### Documentation
- New `README.md` with quick start, annotation reference, architecture diagram,
  module table.
- New `hypermixins-example/README.md` documenting smoke runs.
- `CONTINUE.md` rewritten around the descriptor pipeline.
