# HyperMixins — Continuation Context

## What's done
- `hypermixins-annotations`: complete (29 annotations, pure Java, zero deps)
- `hypermixins-processor`: KSP processor, generates `$$Descriptor` Java source + `META-INF/hypermixins/index.txt`
- `hypermixins-runtime`: INVOKEDYNAMIC dispatch via `MixinRegistry` + `MutableCallSite`, `MixinHandle` enable/disable
- `hypermixins-example`: compiles, KSP generates `WorldMixin$$Descriptor.java`
- `hypermixins-intellij-plugin`: F1-F4 implemented (gutter nav, inspections, completion)

## Known issues to fix

### 1. Descriptor: Java collection types mapped to Kotlin internal types
`WorldMixin$$Descriptor.java` `overwriteEntries` shows `()Lkotlin/collections/MutableList;` — wrong.
Fix `toJvmDesc()` in `hypermixins-processor/src/main/kotlin/net/echo/hypermixins/processor/MixinSymbolProcessor.kt`:
add cases before the `else`:
```kotlin
"java.util.List", "kotlin.collections.List", "kotlin.collections.MutableList" -> "Ljava/util/List;"
"java.util.Map",  "kotlin.collections.Map",  "kotlin.collections.MutableMap"  -> "Ljava/util/Map;"
"java.util.Set",  "kotlin.collections.Set",  "kotlin.collections.MutableSet"  -> "Ljava/util/Set;"
"java.util.Collection", "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "Ljava/util/Collection;"
```

### 2. Descriptor: `Call` enum arg reads as `INVOKEVIRTUAL` (wrong default)
`redirectEntries` shows `"INVOKEVIRTUAL"` but `WorldMixin.java` declares `call = Call.INVOKESTATIC`.
In `validateAndCollectRedirect` (same file, line ~174):
```kotlin
val call = (atAnn?.arg("call") as? KSType)?.declaration?.simpleName?.asString() ?: "INVOKEVIRTUAL"
```
The `KSType` cast may be wrong for Java enum annotation args — KSP might return the value as `KSClassDeclaration` or the enum entry name differently.
Try: check if `atAnn?.arg("call")` is a `KSType` and trace what it actually returns. May need:
```kotlin
val callVal = atAnn?.arg("call")
val call = when (callVal) {
    is KSType -> callVal.declaration.simpleName.asString()
    is String -> callVal
    else -> "INVOKEVIRTUAL"
}
```

## Next tasks (in order)
1. Fix `toJvmDesc` for Java collection types (5 min)
2. Fix `Call` enum arg reading in `validateAndCollectRedirect` (10 min, may need debug print)
3. `rm -rf hypermixins-example/build/generated/ksp && ./gradlew :hypermixins-example:jar` — verify clean descriptor
4. Write runtime tests: INVOKEDYNAMIC swap, disable/enable, `__original$` fallback  
   Tests live in `hypermixins-api/src/test/` — see existing `MixinTransformerTest`
5. `@Inject` transformer support (HEAD/RETURN/TAIL injection in `MixinTransformer.java`)
6. `.mixins.yml` registration support — auto-register mixin classes from YAML without calling `HyperMixins.register()` programmatically

## Build commands
```bash
./gradlew :hypermixins-example:jar           # builds example + runs KSP
./gradlew :hypermixins-runtime:test          # runtime tests
./gradlew :hypermixins-processor:test        # processor tests
./gradlew :hypermixins-intellij-plugin:buildPlugin  # plugin zip
```

## Key files
- Processor: `hypermixins-processor/src/main/kotlin/net/echo/hypermixins/processor/MixinSymbolProcessor.kt`
- Transformer: `hypermixins-runtime/src/main/java/net/echo/hypermixins/agent/MixinTransformer.java`
- Registry: `hypermixins-runtime/src/main/java/net/echo/hypermixins/registry/MixinRegistry.java`
- Example mixin: `hypermixins-example/src/main/java/net/echo/tests/WorldMixin.java`
- Style guide: `style.md`
