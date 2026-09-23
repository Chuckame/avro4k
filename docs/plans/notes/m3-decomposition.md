# M3 — Decouple from Apache: decomposition into units

> Output of the M3 `Plan` agent, 2026-09-23, on `v3/m2-foundation` at `eaae5c6`. Read-only analysis: every path and claim
> below was checked against the code by the agent; nothing was built.
>
> **Orchestrator review (spot-checked before commit):** the `GenericData` default-value path (§0.4) and the absence of
> any downstream use of the generic-data APIs (§11 Q1) are confirmed. Two corrections: there are **22**
> `qualifiedName!!` sites in `core/src/jvmMain`, not 13 (§0.3 — M3-13 must grep, not use the list); and C3's wrapper
> was removed in `131221b`, not `5c8a318` (§0.7). The open questions in §11 are answered in `PROGRESS.md`'s decision
> log before the units that depend on them start.

## Executive summary

- **17 units** (M3-01 … M3-17), one commit / one agent each, in 4 parallel batches of ≤ 3 agents; api-touching units run alone.
- **Order of the switch.** The Apache → `AvroSchema` switch is cut only at boundaries that are not crossed per value: schema
  generation (per descriptor) and the encode/decode entry points (per call). M3-05 temporarily hosts the converter in core
  `jvmMain`, so core can convert at those boundaries; M3-14 moves it back to `apache-interop`. Order: groundwork → visitors
  (M3-09) → runtime internals behind an Apache facade (M3-11) → public API flip (M3-12) → serializer cleanup, Apache exit,
  relocation to `commonMain` (M3-13 … M3-15).
- **One named intermediate state crosses a per-value boundary:** between M3-11 and M3-12, the public `currentWriterSchema` and
  the `decodeResolving*` lambdas are served through an identity-cached `toApacheSchema()`. M3-11 is therefore never
  benchmarked alone, only with M3-12.
- **Tests.** The ~761 `jvmTest` tests are an Apache-oracle suite (bytes compared with `GenericDatumWriter`, schemas built with
  `SchemaBuilder`, encoding through `ValidatingEncoder`). They stay on the JVM for good, as a conformance suite bridged through
  `apache-interop`. Cross-platform coverage comes from a new `commonTest` golden-byte suite (M3-16). The plan's "move the suite
  progressively to `commonTest`" cannot be done as written.
- **B2:** the codec ABI becomes `@InternalAvro4kApi public abstract class`es, not `internal interface`s as the plan says,
  because `apache-interop` must implement adapters over them. `Utf8`, `ByteBuffer` and `SystemLimitException` go in M3-02.
- **A/B checkpoints:** M3-02, M3-06, M3-11+12 together, then the full re-baseline in M3-17. EA-off allocation, ≥ 3 separate
  invocations per side.
- **Open questions for the user (§11):** drop or move the deprecated Apache-typed APIs; the semantics of
  `validateSerialization`; pulling CRC-64-AVRO into M3 so that core ends M3 with no Apache dependency.

## 0. Where the plan is stale or wrong, verified against the code

1. **B2's `internal interface AvroBinaryEncoder/Decoder`** must be `@InternalAvro4kApi public`, because the Apache adapters live
   in `apache-interop`, a separate module. Abstract classes rather than interfaces: interface dispatch costs more on
   Kotlin/Native than a vtable call, and abstract classes are platform-neutral (§4).
2. **B4's "`@JvmStatic` in `AvroDuration.kt` must go or be guarded"** is not needed: `kotlin.jvm.JvmStatic` and `JvmField` are
   `@OptionalExpectation` in the common stdlib (`kotlin-stdlib-2.3.21-sources`, `commonMain/kotlin/JvmAnnotationsH.kt`).
3. **JS/native blockers the plan misses:**
   - **`KClass.qualifiedName` returns `null` on JS** (stdlib-js `KClassImpl`). `X::class.qualifiedName!!` is used as the serial
     descriptor name at 22 sites in `core/src/jvmMain` (e.g. `SerializerLocatorMiddleware.kt`, `KotlinStdLibrarySerializers.kt`,
     `AvroDuration.kt`, `AnySerializer.kt`); each would throw an NPE at object init on JS.
   - **`Math.multiplyExact` / `Math.addExact`** in `KotlinInstantExtensions.kt:30,32`.
   - **`AvroConfiguration.logicalTypes`'s default map** (`AvroConfiguration.kt:62-74`) references JVM serializers. It needs
     `expect`/`actual` exactly like `Avro.Default`'s module.
4. **B3 misses the default-value path.** Missing fields are decoded through Apache `GenericData`:
   `RecordResolver.parseValueToGenericData` builds `GenericData.Record` / `Fixed`, and `RecordDirectDecoder.decodeDefault` reads
   them back with the Apache-typed generic decoder (`internal/decoder/generic/*`). So that tree cannot simply be moved out;
   M3-08 replaces it.
5. **`helpers.kt:188`.** The plan suggests `getContextual(KClass)`, but `getContextualDescriptor` already does that. The JVM-only
   fallback (`capturedKClass.java` → `serializerOrNull(Class)`) looks up the *generated* serializer; its common equivalent is
   `capturedKClass.serializerOrNull()` (kotlinx-serialization, common, `@InternalSerializationApi`).
6. **`JavaStdLibSerializers` uses Apache `Conversions.DecimalConversion` / `BigDecimalConversion`** (lines 238-239). Core cannot
   drop Apache until they are rewritten with `java.math` and stay byte-identical (M3-13).
7. **Already done:** B7's "kotlin-generator: delete its three `AvroSchema*.kt`" (B1); C3's holder, which the M2 review said to
   revisit in M3, is deleted (`131221b`).
8. **The model has three hot-path costs Apache does not have**, to fix before any runtime code reads `AvroSchema` (M3-04):
   `AvroSchema.type` is an abstract getter over 14 subclasses (Apache: a field read); `logicalTypeName` is a map lookup plus a
   JSON cast per call; `EnumSchema.symbols.indexOf` is O(n) per encoded enum.
9. **Line drift:** `AbstractAvroDirectDecoder.beginStructure` is now at lines 74-121 (plan: 48-95). `helpers.kt:3-5,188,206`,
   `ValueVisitor.kt:48` and `Serializers.kt:206` are still accurate.

## 1. Rules every M3 brief carries, on top of the locked decisions

- **Per-value boundary.** No unit adds an `AvroSchema`↔`Schema` conversion per value, except the named M3-11→12 facade
  (identity-cached, §2). Per-call and per-descriptor conversions are allowed only through the identity caches. Fresh-per-call
  schemas (e.g. `getSchema()` results) use the **uncached** converter (`WeakIdentityKeyCache` KDoc: "a key created per call is
  not" OK).
- **No structural equality on the hot path.** Runtime code never uses `==`, `indexOf`, `contains` or an equality-hashed map key
  on an `AvroSchema` or a `Field` (`equals` is a structural walk). Acceptance includes a grep of each changed file.
- **Shared state** (decision log, 2026-09-23, C3 rows): shared caches immutable or CAS-based; per-operation state on the
  decoder/encoder; anything else designed away.
- **Test count:** each unit records the exact `:core:jvmTest` count before and after; every change is listed in `notes/m3-XX.md`.
- **`ACC`**, the acceptance command of every unit:
  `./gradlew :core:jvmTest :apache-interop:test :confluent-kafka-serializer:test :kotlin-generator:test :gradle-plugin:test :benchmark:compileKotlin apiCheck spotlessApply actionsBeforeCommit`.
  Pass: all green; `git diff --exit-code core/api apache-interop/api` clean, except in units marked **api**, where the diff is
  reviewed; kotlin-generator's golden sources unchanged under `CI=true`.
- **`ACC+KMP`** = `ACC` + `./gradlew :core:check`: `allTests` (jvm, jsNode, jsBrowser, macosArm64, iosSimulatorArm64) plus the
  test binaries of every target, including `androidNativeArm32`.

## 2. How core switches from Apache `Schema` to `AvroSchema`

| After | Schema generation | Runtime (encoders, decoders, resolvers) | Public API | Conversion, and how often |
|---|---|---|---|---|
| M3-08 | Apache | Apache | Apache | none |
| M3-09 | **AvroSchema** | Apache | Apache | `Avro.schema()`: `toApacheSchema` once per descriptor (cached); `getSchema()` results: uncached `toAvro4k`, cold path |
| M3-11 | AvroSchema | **AvroSchema** | Apache (**facade**) | entry points: `toAvro4k` per **call** (identity-cached). **Named intermediate:** public `currentWriterSchema` / `decodeResolving*` go through `toApacheSchema` per **value**, identity-cached; with M3-05's two-way seeding it returns the caller's original Apache node, so identity and `GenericFixed` semantics hold. Core's own helpers read the internal `AvroSchema` and skip the facade |
| M3-12 | AvroSchema | AvroSchema | **AvroSchema** | none in core; Confluent keeps identity-cached per-value conversions until B7 (§7) |

**Why this order.** `currentWriterSchema` couples three things per value: encoders/decoders, every `AvroSerializer`, and the
public type. They cannot flip separately without a per-value conversion. Schema generation and the entry points are the only
seams that are not per value, so they flip first (M3-09) and last (M3-12). The two directions of the facade cost the same;
"internals first" is chosen because M3-11 then needs **no test changes** — the Apache oracle validates the runtime rewrite
untouched. M3-12 carries the API, the serializers and the bridge bodies; the bridges (M3-01, M3-03) shrink that last step to a
handful of files.

## 3. Units

Path shorthands: `J` = `core/src/jvmMain/kotlin/com/github/avrokotlin/avro4k`, `C` = `core/src/commonMain/…/avro4k`,
`T` = `core/src/jvmTest/…/avro4k`, `AI` = `apache-interop/src/main/…/avro4k`, `CF` = `confluent-kafka-serializer/src/main/…/kafka/confluent`,
`BM` = `benchmark/src/main/kotlin/com/github/avrokotlin/benchmark`.

**M3-01 · Test bridge.** `T/AvroAssertions.kt`, new `T/ApacheTestBridge.kt`, every `T/**` file except the codec tests (M3-02);
`core/build.gradle.kts` (`jvmTest` gets `implementation(project(":apache-interop"))`). Every test call into core that takes or
returns a schema goes through bridge helpers (e.g. `Avro.apacheSchema<T>()`, `encodeWith(schema: Schema, …)`), identities today.
Test imports of core's Apache-typed internals (`internal.nullable`, `internal.copy`: 15 files) are replaced by test-local
copies. Deps: none. Not api. Batch 1. `ACC`, same test count, no main-code change. Perf-neutral (tests only).
**Risk:** core's `jvmTest` depends on a module that depends on core; this must be proven to resolve to core's own jvm jar next
to its classes. Fallback: M3-12 moves the public-API oracle tests into `apache-interop/src/test`, internal-API tests stay.

**M3-02 · B2 own codec ABI** (§4). New `C/internal/codec/{AvroBinaryEncoder,AvroBinaryDecoder}.kt` and the varint writer;
`git mv` `KotlinxIoEncoder.kt`, `KotlinxIoDecoder.kt` to `C/internal/codec/`; split `J/internal/NumberUtils.kt` (`Int`/`Long`/
`Double` helpers → common, `BigDecimal` overloads stay); new `J/internal/codec/ApacheCodecAdapters.kt`. Changed: direct
encoders/decoders (`J/internal/{encoder,decoder}/direct/*`, `AbstractAvroEncoder.kt`), `J/internal/AvroInternalExtensions.kt`,
`J/{AvroKotlinxIoExtensions,AvroJVMExtensions,AvroOkioExtensions}.kt` (no Apache `DecoderFactory`/`EncoderFactory`; ByteBuffer
and okio through kotlinx-io), `T/{KotlinxIoEncoderTest,ReadBytesBufferBoundsTest,DecodeFromByteBufferTest}.kt`, new
`core/src/commonTest/.../codec/*`. `encodeWithApacheEncoder`/`decodeWithApacheDecoder` keep their Apache signatures, so
Confluent is untouched. Deps: none. Not api (`@InternalAvro4kApi` is hidden; `apiCheck` must prove it). Batch 1. `ACC+KMP`,
plus a JVM differential test of the varint writer against `BinaryData.encodeInt`/`encodeLong`. **A/B:** yes (§9).
**Risk:** Apache encoders handed in from outside (Confluent, `ValidatingEncoder`) must see exactly the same call sequence.

**M3-03 · Downstream bridges.** `CF/*`, `BM/**` (@Setup and schema plumbing only, never a measured method), the build files of
`confluent-kafka-serializer` and `benchmark` (both get `implementation(project(":apache-interop"))`). Each module routes its
schema-typed core calls through one local `CoreBridge.kt`, identities today (`avro.schema(…)`, `currentWriterSchema`,
`decodeFixed`, `trySelectNamedSchema(Schema)`, `isNamedSchema`). Deps: none. Not api. Batch 1; merged **after** the M3-02 A/B,
so that A/B runs on unchanged benchmark sources. `ACC`. Perf-neutral. **Risk:** low.

**M3-04 · Model readiness** (**api**, alone). `C/AvroSchema.kt`: `type` becomes a stored `val` of the sealed base;
`logicalTypeName` on `AvroSchema` (null for unions), cached per instance; `EnumSchema` O(1) symbol → index map; `RecordSchema`
`getFieldOrNull` and field-index lookup by name or alias; `FixedSchema.size: UInt → Int` (§11 Q4). Adapt
`AI/AvroSchemaApacheInterop.kt` and kotlin-generator's fixed-size uses. Deps: none. `ACC+KMP` + `commonTest` additions in
`AvroSchemaTest`. Perf-neutral (the runtime does not read `AvroSchema` yet); it exists so no hot path is ever built on the slow
shapes. **Risk:** B1's cycle-safe `equals`/`hashCode` must not regress; its tests guard it.

**M3-05 · Converter hosted in core, two-way seeding.** `git mv` the implementation from `AI/AvroSchemaApacheInterop.kt` to
`J/internal/AvroSchemaApacheConversion.kt` (`@InternalAvro4kApi`); `apache-interop` keeps its public `toAvro4k()` /
`toApacheSchema()` as one-line delegates (API unchanged). **Seeding:** every conversion fills both identity caches for every
node it creates (on the JSON route of `toApacheSchema`, a parallel walk after parsing), so `x.toAvro4k().toApacheSchema() === x`
for every sub-node and vice versa. Also an uncached entry point. Deps: M3-04. Not api. Batch 2. `ACC` + identity round-trip
tests in `apache-interop`. Perf: cold only. **Risk:** adds Jackson/Apache reachability to core `jvmMain` until M3-14 (intended).

**M3-06 · Common caches, descriptor-keyed call sites** (B6 follow-up, §8). `WeakKeyCache` becomes a common `expect class` with
the same FQN (Confluent's import still works): JVM/native = the shared copy-on-write table in `jvmAndNativeMain` in equality mode;
JS = a strong `HashMap`. New `IdentityFirstCache`: identity layer backed by the equality cache, seeding the identity layer only
with the instance that missed in the equality layer — never equal-but-distinct fresh instances, which would hit the O(capacity)
put. Converts `Avro.schemaCache`, `EnumResolver`, `PolymorphicResolver`, and the inner level of `RecordResolver`. Deps: none.
Not api. Batch 2. `ACC+KMP`; `WeakKeyCacheTest` moves to `commonTest`, new identity-first tests. **A/B:** yes.
**Risk:** Confluent's cache goes from compute-once to may-recompute (the `Cache` contract allows it).

**M3-07 · `AvroSchema` helper layer.** New `C/internal/schema/AvroSchemaHelpers.kt` + `commonTest`s porting every helper the
runtime and serializers use on Apache `Schema`: `isNamedSchema`, `aliases`, `isFullNameOrAliasMatch`, `getIndexTyped`,
`getIndexLogicallyTyped`, `asSchemaList`, `move{Head,Tail}OfUnion`, `AvroBinaryDecoder.skip(AvroSchema)`, and internal
Apache-shaped accessors (`isUnion`, `types`, `fixedSize`, `enumSymbols`, `elementType`, `valueType`) that throw on the wrong kind,
as Apache does. Makes M3-09/11/12 mostly import swaps. Deps: M3-02, M3-04. Not api. Batch 2. `ACC+KMP`. Perf-neutral (unused until
M3-09). **Risk:** dead code for 2-3 units (acceptable).

**M3-08 · Defaults without `GenericData`** (**api** if Q1 = drop; alone). `DecodingStep.GetDefaultValue` keeps the `JsonElement`
parsed from `@AvroDefault` (as `J/internal/RecordResolver.kt` does today); new `J/internal/decoder/direct/JsonDefaultDecoder.kt`
(an `AvroDecoder` over `JsonElement`) decodes it, records through the `RecordResolver` workflow by writer field name. Preserve
current semantics exactly: bytes/fixed defaults as UTF-8 `toByteArray()`, the char logical type from the annotation string. If
Q1 = drop, also delete `J/AvroGenericDataExtensions.kt`, `J/internal/encoder/generic/*`, `J/internal/decoder/generic/*`,
`T/encoding/GenericDataMapEncodingTest.kt` and the generic parts of `ArrayEncodingTest` and `AvroAssertions`; the ledger's
`ArrayGenericDecoder` backlog row becomes obsolete. Deps: M3-01, M3-06. `ACC`; `AvroDefaultEncodingTest` and
`RecordDecodingCompatibilityTests` are the oracle. Perf: no benchmark reads missing fields, so not covered — say so in the note.
**Risk:** subtle default semantics; mutation-check the new decoder.

**M3-09 · Visitors → `AvroSchema`, Jackson dropped.** `J/internal/schema/*` (8 files); `J/internal/helpers.kt` (`AvroProp.jsonNode`
→ `JsonElement`, no `ObjectMapper`); `J/Avro.kt` (internal `avroSchema(descriptor)`; public `schema()` returns the cached Apache
form); `J/serializer/AvroSerializer.kt` (internal supplier converting public `getSchema()` results with the uncached converter).
Deps: M3-05, M3-06, M3-07. Not api. Batch 3 (with M3-10). `ACC`: the whole `schema/` suite passes unchanged. **Also update** the
BigIntegerNode pin test in `apache-interop` (`AvroSchemaApacheInteropTest.kt:101`): `Avro.schema()` now goes through a JSON parse,
so its int defaults become `IntNode`. Perf: schema generation is cold; `SchemaInferenceMicroBenchmark` warm unchanged by
construction; cold gets slower (JSON round trip), not gated. **Risk:** prop ordering in `toString(true)` comparisons.

**M3-10 · CRC-64-AVRO + Parsing Canonical Form in common** (pulled from B5, Q3). New `C/internal/SchemaFingerprint.kt` +
`commonTest`s; a `jvmTest` differential test against `SchemaNormalization.parsingFingerprint64` over the 51
`core/src/jvmTest/resources` schemas plus generated ones. Deps: M3-04. Not api. Batch 3. `ACC+KMP`. Perf: cold, memoized.
**Risk:** canonical-form corner cases (aliases, namespaces, `order` all stripped).

**M3-11 · Runtime flip behind an Apache facade** (the largest unit). `J/internal/{encoder,decoder}/**`, `RecordResolver.kt`,
`PolymorphicResolver.kt`, `EnumResolver.kt`, `exceptions.kt`, `helpers.kt`; the internals of `J/{AvroEncoder,AvroDecoder}.kt`
(facade getter + internal `currentAvroSchema`); the per-call conversion at the entry points; `J/serializer/AnySerializer.kt`
(`decodingCache` identity on `AvroSchema`); `T/internal/RecordResolverTest.kt`, `T/encoding/AnySerializerTest.kt` (internal APIs).
Also converts the outer `fieldCache` (identity) and retypes the C2 inline memo. Deps: M3-07, M3-08, M3-09. Not api. Alone. `ACC`,
no other test file changed. **A/B:** only with M3-12. **Fallback split:** decode side first (encoders resolving workflows via
identity-cached `toAvro4k()` per record, a named intermediate), then encode side. **Risk:** size (~3,000 lines of type swaps).

**M3-12 · Public API flip** (**api**, alone). `Avro.schema(): AvroSchema`; `currentWriterSchema: AvroSchema`; `decodeResolving*`
lambdas take `(AvroSchema)`; `getSchema()` and `AvroStringable`/`AvroFixed.createSchema()` return `AvroSchema`;
`decodeFixed(): ByteArray`; every entry point takes `writerSchema: AvroSchema` (incl. `AvroSingleObject` and
`AvroObjectContainer.openWriter`, converting once per call/writer through the core-hosted converter until M3-14);
`MissingFieldsEncodingException`, `trySelectNamedSchema`, `namedSchemaNotFoundInUnionError`, `isNamedSchema`; the `AnySerializer`
hooks. All `J/serializer/*.kt` bodies change; the facade is deleted. Bridge bodies change in `T/ApacheTestBridge.kt`,
`CF/CoreBridge.kt`, `BM/.../CoreBridge.kt`, plus Confluent's overrides. Tests changed: `CustomAvroSerializerTest`,
`AvroCustomSchemaTest`, `AvroFixedEncodingTest`, `RecordBuilderForTest`, `AvroSingleObject*Test`, `AvroObjectContainerTest`.
Deps: M3-01, M3-03, M3-11. `ACC+KMP`, review the api diff. **A/B:** M3-11+12 against the commit before M3-11. **Risk:** size; the
`core.api` diff is the authoritative list of breaking changes — record it for B8.

**M3-13 · Serializers ready for common** (B4 part 1). `J/serializer/{KotlinStdLibrarySerializers,KotlinInstantExtensions,AvroDuration}.kt`:
literal serial names instead of `qualifiedName!!` (**grep all 22 sites**, incl. `J/internal/SerializerLocatorMiddleware.kt`,
`J/serializer/AvroSerializer.kt`, `J/internal/exceptions.kt`, `J/internal/schema/{VisitorContext,InlineClassVisitor}.kt`); exact
arithmetic in pure Kotlin instead of `Math.*Exact`; manual little-endian packing instead of `ByteBuffer` in `AvroDuration`.
`AnySerializer` split into a common `AbstractAnySerializer` + `expect open class AnySerializer()`: the JVM actual keeps today's
`Class`-typed hook and `ConcurrentHashMap`; the js/native actual gets a `KClass` hook over the common `WeakKeyCache`.
`J/serializer/JavaStdLibSerializers.kt`: decimal and big-decimal bytes via `java.math`, no Apache `Conversions`. `helpers.kt:188`
→ `capturedKClass.serializerOrNull()`. Deps: M3-12. Not api (`AnySerializer` is `@InternalAvro4kApi`; `apiCheck` must confirm).
Runs alone (touches the serializers). `ACC` + a JVM differential test of the decimal bytes against Apache `Conversions`. Perf: JVM
paths unchanged; Kotlin `Duration`/`Uuid`/`Instant` are in no benchmark, so not covered.

**M3-14 · Apache exit from core** (**api**, alone). To `apache-interop` (same packages): the converter implementation (back);
`encodeWithApacheEncoder`/`decodeWithApacheDecoder` + adapters + the validating wrapper, in a renamed file (e.g. `ApacheCodec.kt`,
since two jars must not both contain `…internal.AvroInternalExtensionsKt`); `J/AvroObjectContainer.kt` and its test (Q3);
`J/ListRecord.kt` (or delete it, Q1). `AvroSingleObject` uses M3-10's fingerprint (`fingerprintCache` → `WeakIdentityKeyCache`,
still per instance). Remove `api(libs.apache.avro)` from core `jvmMain`. Deps: M3-10, M3-12, M3-13. `ACC`; pass also requires
`grep -r "org.apache.avro\|com.fasterxml" core/src/*Main` to be empty. Perf-neutral (files move). **Risk:** `:gradle-plugin:test`
publication, and the BOM.

**M3-15 · Relocation to `commonMain` + B4's `expect`/`actual`** (**api** if facades move; alone). `git mv` every platform-free
file to `C/…` (what stays in `jvmMain`: §6). `expect`/`actual` for `Avro.Default`'s module and for the default `logicalTypes`.
`RecordResolver`'s `@Volatile` → `kotlin.concurrent.Volatile`; the note must give the native publication argument for the C2
array, as B6 did. Keep JVM facade class names stable where cheap (`@file:JvmName`). Deps: M3-14. `ACC+KMP`; pass: every target
compiles, jvmTest count unchanged. Perf-neutral by construction; M3-17 confirms it.

**M3-16 · Cross-platform golden fixtures.** `core/src/commonTest/.../fixtures/*`: ~40 representative models (primitives, unions,
nested records, collections split into blocks, enums, fixed, logical types, defaults). Expected bytes generated once on the JVM
through the Apache oracle and pasted as hex; expected schemas as JSON strings. Deps: M3-15. `ACC+KMP`; pass: jvm, js and native
produce identical bytes. Tests only.

**M3-17 · Re-baseline + review.** The orchestrator runs the benchmarks, strictly serialized (§9), then a
`feature-dev:code-reviewer` pass over M3-01 … M3-16 together (the M1/M2 lesson).

## 4. B2 decisions

- **ABI** (`C/internal/codec/`, `@InternalAvro4kApi public abstract class`), mirroring Apache's grammar so `ValidatingEncoder` and
  any Apache `Encoder` handed in by Confluent see exactly today's calls.
  - Encoder: `writeNull/Boolean/Int/Long/Float/Double`, `writeString(String)`, `writeString(utf8: ByteArray)` (replaces
    `encodeStringUnchecked(Utf8(value))`, `AbstractAvroEncoder.kt:177,193`), `writeBytes`, `writeFixed`, `writeEnum`,
    `writeIndex`, `writeArrayStart(count)`, `writeMapStart(count)`, `startItem`, `writeArrayEnd`, `writeMapEnd`.
  - Decoder: `readNull/Boolean/Int/Long/Float/Double`, `readString(): String`, `readStringBytes(): ByteArray` (replaces
    `readString(null).bytes` in `decodeBytes`/`decodeChar`/`decodeFixed`), `readBytes(): ByteArray`, `readFixed(size)`,
    `readEnum`, `readIndex`, `readArrayStart/arrayNext/readMapStart/mapNext`, `skipString/Bytes/Fixed/Array/Map`.
- **Adapters:** `ApacheEncoderAdapter`/`ApacheDecoderAdapter` implement the ABI over an Apache `Encoder`/`Decoder`. Validation
  needs the reverse too: an ABI → Apache bridge, wrapped in `ValidatingEncoder`, wrapped back in the adapter. In core `jvmMain`
  from M3-02 to M3-14, then in `apache-interop` next to `encodeWithApacheEncoder`/`decodeWithApacheDecoder` (same names, package).
- **`validateSerialization`:** JVM-only from M3-02, through the adapters. After M3-14 core has no Apache, so "JVM-only" can only
  mean "honoured by `apache-interop`'s entry points" (Q2). The test oracle keeps validating: `AvroAssertions` encodes through
  `apache-interop`'s validating path.
- **Removed:** `Utf8` (above); `ByteBuffer` (`readBytes` → `source.readByteArray(len)`, an exact copy safe across segments; this
  deletes the segment fast path, and `ReadBytesBufferBoundsTest` must stay green); `SystemLimitException` → a common bound check
  (negative length or length > `Int.MAX_VALUE - 8` → `SerializationException`). The `org.apache.avro.limits.*` system properties
  are gone — B8's migration guide must say so.
- **Varint writer:** zig-zag varint in common with an `UnsafeBufferOperations.writeToTail(buffer, 5|10)` fast path, mirroring the
  decode side. Tested by `commonTest` vectors (0, ±1, 7/14/21/28-bit boundaries, Int/Long `MIN`/`MAX`) and by a JVM test comparing
  bytes with `BinaryData`.
- **Out of scope** (M5/C5): the non-allocating string write; `writeString` keeps `encodeToByteArray()`.

## 5. Tests: who changes what, and when

- **The `jvmTest` suite stays the Apache-oracle conformance suite, permanently.** It cannot move to `commonTest`: it depends on
  `GenericDatumWriter`, `SchemaBuilder`, `Schema.Parser` over resources, `ValidatingEncoder`, and mockk. From M3-01 it reaches
  core only through `T/ApacheTestBridge.kt`, and uses `apache-interop`'s public `toAvro4k()`/`toApacheSchema()`.
- **Which unit changes which tests:** M3-01 every test's call sites (bridge); M3-02 the codec tests; M3-06 the cache tests; M3-08
  the generic-data tests (if Q1 = drop); M3-09 the BigIntegerNode pin in `apache-interop`; M3-11 `RecordResolverTest`,
  `AnySerializerTest`; M3-12 the bridge bodies and the 7 tests listed in its unit; M3-14 `AvroObjectContainerTest` moves.
- **New common tests:** M3-02, M3-04, M3-06, M3-07, M3-10 add `commonTest`s (jvm, js, native); M3-16 adds the golden fixtures.
- **"Passes unchanged throughout B"** holds literally for M3-02 … M3-11 except the internal-API tests, and at M3-12 modulo the
  bridge. That is the guard's real meaning: the same oracle assertions, untouched.

## 6. Jackson, B4, and where each file ends up

- **Jackson:** `@AvroProp` parsing (`helpers.kt:3-5,206-214`) goes in M3-09 (props → `JsonElement` via `Json.parseToJsonElement`,
  following `RecordResolver`'s `@AvroDefault` pattern). The hosted converter still reaches Jackson through Apache until M3-14,
  whose acceptance grep must then find no `com.fasterxml` in core.
- **B4 split (M3-13, M3-15):**
  - `commonMain`: `KotlinStdLibrarySerializers`, `KotlinInstantExtensions`, `AvroDuration`, `AvroSerializer`, `AbstractAnySerializer`.
  - `jvmMain`: `JavaTimeSerializers`, `JavaStdLibSerializers` (BigDecimal/decimal JVM-only — known limitation), the JVM
    `AnySerializer` actual, `AvroJVMExtensions`, `AvroOkioExtensions`, `AvroSingleObject`'s stream overloads, `IdentitySet.jvm`,
    `WeakRef.jvm`.
  - `expect`/`actual`: the default module and the default `logicalTypes` — JVM gets all four modules and the
    java.time/UUID/decimal logical types, other platforms the Kotlin ones only. **B8 must document this silent behaviour
    difference prominently.**
- **`AnySerializer` extension point:** `protected open inferSerializationStrategyFromNonSerializableType(type: Class<out Any>)`
  exists only on the JVM actual, so Confluent's override is unchanged; non-JVM targets get a `KClass<out Any>` variant.
- **Destinations:** `apache-interop` gets the codec adapters, `encodeWithApacheEncoder`/`decodeWithApacheDecoder`, the converter,
  `AvroObjectContainer`, and `ListRecord` (or deleted, Q1). Everything else in `J/` moves to `commonMain` in M3-15.

## 7. Downstream within M3, and what B7 does later

- **`confluent-kafka-serializer`** only needs to compile and pass its 72 tests in M3: M3-03 the bridge + `apache-interop`
  dependency; M3-12 bridge bodies, `AnySerializer` override signatures, `getSchema` overrides,
  `GenericData.Fixed(currentWriterSchema.toApacheSchema(), decodeFixed())`; M3-14 unchanged imports (same package and names).
  **Left for M4/B7:** the per-value identity-cached `toApacheSchema()`/`toAvro4k()` in its generic serializers (explicitly accepted
  until B7), `ThreadLocal<Schema>`, identity keys for its registry cache.
- **`kotlin-generator`:** only M3-04's `FixedSchema.size` type; its golden tests guard it.
- **`benchmark`:** the M3-03 bridge and M3-12's bodies (@Setup only).
- **`gradle-plugin`:** in `ACC`, which covers publication in M3-14 and M3-15.

## 8. Cache call sites (per the table in `notes/b6.md`)

| Call site | Key | Becomes | Unit |
|---|---|---|---|
| `Avro.schemaCache` | descriptor | `IdentityFirstCache` (identity, backed by equality) | M3-06 |
| `RecordResolver.fieldCache` inner level | descriptor | `IdentityFirstCache` | M3-06 |
| `PolymorphicResolver.cache` (read per value) | descriptor | `IdentityFirstCache` (generic sealed descriptors are fresh per call) | M3-06 |
| `EnumResolver.defaultIndexCache` | enum descriptor | `WeakIdentityKeyCache` | M3-06 |
| `RecordResolver.fieldCache` outer level + C2 memo | writer `AvroSchema` | `WeakIdentityKeyCache`; memo retyped | M3-11 |
| `AnySerializer.decodingCache` | `AvroSchema` | `WeakIdentityKeyCache` | M3-11 |
| `AnySerializer.encodingCache` | `Class` / `KClass` | JVM: `ConcurrentHashMap` (unchanged); other platforms: common `WeakKeyCache` | M3-13 |
| `AvroSingleObject.fingerprintCache` | `AvroSchema` | `WeakIdentityKeyCache`, per instance | M3-14 |
| `toAvro4k` / `toApacheSchema` | schema | identity caches, seeded both ways | M3-05 |
| Confluent `Serializers.cache` | Apache `Schema` | common `WeakKeyCache` for now; identity in B7 | M3-06 (implicitly) |

## 9. Measurement

**A/B protocol.** The orchestrator runs every benchmark, strictly serialized, under `caffeinate -i`; base commit vs unit
commit; allocation EA-off (`-PallocJmhArgs='-jvmArgsAppend -XX:-DoEscapeAnalysis'`, the platform-neutral metric); any throughput
delta under 10% confirmed over **≥ 3 separate invocations per side** (M1); numbers into `benchmark/README.md` in the same commit.

**Checkpoints.**
- **M3-02 (codec):** `:benchmark:benchmarkAlloc` with `-Pbench='micro\.CollectionMicroBenchmark\.(readLongs|writeLongs)' -Pparams='size=100000'`,
  `-Pbench='micro\.StringFieldMicroBenchmark'`, and `-Pbench='complex\.Avro4kBenchmark'`. Expected: throughput neutral or better,
  fewer B/op on any path decoding bytes.
- **M3-06 (caches):** `micro\.PolymorphicMicroBenchmark` at `shapeCount=1000` (at 10, escape analysis hides allocation),
  `micro\.NestedRecordMicroBenchmark` at `depth=8`, `micro\.SchemaInferenceMicroBenchmark` warm. Expected: `Key.Lookup` gone
  (EA-off B/op down on polymorphic), throughput neutral.
- **M3-11+12 together**, against the commit before M3-11: `:benchmark:avro4k` (complex, simple, lists; read and write) plus the
  micro families above and `FieldOrderMicroBenchmark`. This is where a hot-path regression from the model (dispatch,
  `logicalTypeName`, union index) would show.
- **M3-17 re-baseline** ("regressions hide here"): the full `:benchmark:benchmark` suite (~25 min) and `benchmarkAlloc` EA-off on
  the avro4k rows, compared with A8, M1 and M2; committed as "Results — M3". Plus a first non-JVM smoke run: a timing of the
  M3-16 fixtures on jsNode and macosArm64, recorded but not gated.
- **Not measured, and why:** perf-neutral by construction — M3-01, 03, 05, 07, 09 (cold), 14, 15; not covered by any benchmark —
  M3-08 (defaults), M3-10 (memoized fingerprint), M3-13 (Kotlin serializers). Each note says so, as C9 did.

## 10. Parallel execution (max 3 agents, worktrees, api units alone)

| Step | Units | Notes |
|---|---|---|
| Batch 1 | M3-01 ∥ M3-02 ∥ M3-03 | disjoint files; merge 02 first, then its A/B, then 01 and 03 |
| 2 | M3-04 | alone (api) |
| Batch 2 | M3-05 ∥ M3-06 ∥ M3-07 | M3-06 A/B after merging |
| 3 | M3-08 | alone (api if Q1 = drop) |
| Batch 3 | M3-09 ∥ M3-10 | |
| 4 | M3-11, then M3-12 | back to back, each alone; one A/B covers both |
| 5 | M3-13, then M3-14, then M3-15 | 13 not api but touches the serializers; 14 and 15 are api |
| 6 | M3-16 | may overlap with M4 planning |
| 7 | M3-17 | orchestrator benchmarks, then the reviewer |

## 11. Open questions (the user decides before M3-08 / M3-14)

1. **Deprecated Apache-typed APIs** — the `GenericData` extensions with their generic encode/decode trees, and
   `ListRecord` / `Record`. **Recommendation: drop them in v3.** Deprecated since the Confluent module appeared, used by nothing
   outside core's tests, and v3 is breaking anyway. Moving them to `apache-interop` instead would force core to expose
   `AbstractAvroEncoder`, `RecordResolver` and more as `@InternalAvro4kApi`, and add about one unit.
2. **`validateSerialization` once core drops Apache.** **Recommendation:** honour it only in `apache-interop`'s
   `encodeWithApacheEncoder` / `decodeWithApacheDecoder`; deprecate it in `AvroConfiguration` with that wording; document it in the
   B8 guide. A common validating encoder can come later if anyone asks.
3. **Core ends M3 with no Apache dependency?** That means pulling CRC-64-AVRO + canonical form (M3-10, ~80 lines) forward from
   B5, and moving the Apache-`DataFile`-based `AvroObjectContainer` into `apache-interop` as-is (B5 can still reimplement OCF in
   common, in M4 or v3.1). **Recommendation: yes** — otherwise the converter stays in core `jvmMain` until M4 and "Decouple from
   Apache" is only half done.
4. *(Default taken in M3-04; veto if you disagree.)* `FixedSchema.size` becomes `Int` rather than `UInt` (`UInt` compiles to
   mangled JVM names and costs a conversion on the hot-path size compare), and `AvroSchema.type` becomes a stored field.

## Critical files for implementation

- `core/src/jvmMain/kotlin/com/github/avrokotlin/avro4k/internal/decoder/direct/AbstractAvroDirectDecoder.kt`
- `core/src/jvmMain/kotlin/com/github/avrokotlin/avro4k/internal/encoder/AbstractAvroEncoder.kt`
- `core/src/jvmMain/kotlin/com/github/avrokotlin/avro4k/internal/RecordResolver.kt`
- `core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/AvroSchema.kt`
- `apache-interop/src/main/kotlin/com/github/avrokotlin/avro4k/AvroSchemaApacheInterop.kt`
- `core/src/jvmTest/kotlin/com/github/avrokotlin/avro4k/AvroAssertions.kt`
- `confluent-kafka-serializer/src/main/kotlin/com/github/avrokotlin/avro4k/kafka/confluent/Serializers.kt`
