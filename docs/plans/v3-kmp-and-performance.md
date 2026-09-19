# avro4k v3 — Kotlin Multiplatform migration + performance program

> **Status:** active. Started 2026-09-19.
> This file is the **copy of record** for the initiative. `~/.claude/plans/` is scratch and does not
> survive; this file is git-tracked, reviewable and bisectable. Live status lives in
> [`PROGRESS.md`](PROGRESS.md); per-unit findings in [`notes/`](notes/).

## Locked decisions — do not re-open

1. Public API swaps to avro4k's own `AvroSchema`. Breaking → **v3**. Apache interop stays available
   as an explicit **JVM-only bridge module** (`apache-interop`).
2. First-milestone targets: **jvm + js(IR) + native** (full native matrix). **wasmJs deferred.**
3. Perf focus areas: **large collections / nested records**, **per-call allocations & GC pressure**,
   **small-record throughput**.
4. Benchmarks: **multiplatform common benchmarks + the existing JVM-only comparison suite** (both).
5. **Stay on kotlinx-serialization compile-time serializers.** No reflection fallback, no new codegen.
6. **Every perf change must be platform-neutral.** No `Unsafe`, no `ClassValue`, no JIT-shape tricks.
7. Prior art is *cherry-picked*, never merged wholesale: branches `kmp` (best), `kmp-bak`,
   `feat/multiplatform` (reference only). `kmp` is 5+ months stale.

---

## Context

avro4k is a ~8,700 LOC reflection-less Avro codec built on kotlinx-serialization. Two things are
wanted:

1. **Become a real KMP library** (today: JVM-only, `README.md:23-25` carries a warning and
   [issue #207](https://github.com/avro-kotlin/avro4k/issues/207) tracks demand).
2. **Improve performance**, or at minimum surface where the performance problems and hard limits
   are — with fixes that are platform-neutral, not JVM tricks.

These are not independent. The single largest KMP blocker — `org.apache.avro.Schema` and
`org.apache.avro.io.Encoder/Decoder` being the internal data model and the byte-level ABI — is
*also* the thing that forces several per-value allocations (`Utf8`, `ByteBuffer`,
`GenericData.Fixed`). Removing Apache Avro from the core is simultaneously the KMP unlock and the
biggest single perf lever. So the two efforts share one critical path.

**Measured starting point** (`benchmark/README.md`, MacBook Air M2, vs Apache `ReflectData`):

| Workload | avro4k read | avro4k write |
|---|---|---|
| `simple` (25 flat records) | **+15%** faster | **+25%** faster |
| `complex` (15 nested `Clients`, unions, polymorphism, maps) | **−7%** slower | **−17%** slower |
| `lists` (10 × 10,000 `List<Long>`) | **+39%** faster | **−15%** slower |

The pattern is clear: avro4k wins on flat records and loses as soon as structures nest. That points
straight at per-structure cost, which is exactly what the code shows.

**Prior art already in the repo — do not restart from zero:**

- `kotlin-generator/src/main/kotlin/com/github/avrokotlin/avro4k/AvroSchema.kt` (280 lines, on
  `main`) is a pure-Kotlin sealed `AvroSchema` model, explicitly commented as *"the future drop-in
  multiplatform replacement for the Apache Avro Schema class… it will be moved to core when
  ready"*. With `AvroSchemaJsonSerialization.kt` (261 lines) and `AvroSchemaJvmInterop.kt`
  (133 lines, the only JVM-tied one).
- Branch **`kmp`** (last commit 2026-04-06) already has: a
  `library-multiplatform-module-conventions` plugin, the full target matrix in
  `core/build.gradle.kts`, a **607-line** (more complete) `AvroSchema.kt` in `commonMain`,
  `AvroSchemaApacheInterop.kt` in `jvmMain`, and `expect`/`actual` `WeakIdentityKeyCache` +
  `IdentitySet` for js/native. The encoders/decoders were never moved out of `jvmMain`.
- Branches `kmp-bak` and `feat/multiplatform` are earlier attempts; treat as reference only.

---

## Guiding constraints

- **Stay on kotlinx-serialization compile-time serializers.** No reflection-based fallback, no new
  codegen. Confirmed as a hard constraint.
- **Every perf change must be platform-neutral.** `java.util.concurrent` has no common equivalent —
  see §C7.
- **No JIT on native/JS.** Megamorphic virtual dispatch, capturing lambdas, and boxing cost
  proportionally *more* there. Optimizations that help common code help every platform; JVM-only
  micro-tuning is out of scope.
- **Measure before and after every change.** A baseline captured on today's `main` is the reference
  for "did the migration cost us anything".

---

## Plan persistence — surviving across Claude Code sessions

This is a multi-month, multi-milestone effort. It cannot live in a session transcript. Four layers,
cheapest-to-recall first:

1. **`CLAUDE.md` at the repo root** — the auto-recall hook, loaded into *every* session opened in
   this repo. Short, points outward.
2. **`docs/plans/v3-kmp-and-performance.md`** — this file, the plan itself, committed to git.
3. **`docs/plans/PROGRESS.md`** — the live ledger. One row per unit of work. Updated by the
   **orchestrator** (the main session) after each unit completes — never by the sub-agent that did
   the work, so the ledger stays consistent even if an agent fails midway.
4. **A `project` memory entry** at
   `~/.claude/projects/-Users-antoine-michaud-Documents-projets-perso-avro4k/memory/project_v3_kmp_perf.md`,
   so a session started outside the repo root still recalls the initiative.

**Durability rule for every work session:** nothing is "done" until `PROGRESS.md` is updated **and
committed**. Measured numbers go into `benchmark/README.md` in the same commit as the change that
produced them.

---

## Execution model — sub-agents, not the main session

**The main session acts as orchestrator and does no implementation work itself.**

**Orchestrator loop:**

1. Read `docs/plans/PROGRESS.md` → pick the next unit.
2. Spawn one sub-agent with a self-contained brief.
3. Receive a short report; the agent's tool output never enters the main context.
4. Update `PROGRESS.md`, commit, move on.

**Every agent brief must contain**, so the agent never re-derives context:

- the exact files to touch (the *Critical files* table below has them),
- the relevant excerpt of this plan's section (the agent does **not** get the whole plan),
- the locked decisions above, verbatim,
- the exact acceptance command and what a pass looks like,
- an instruction to **write detailed findings to `docs/plans/notes/<unit>.md`** and return **at most
  20 lines**.

**Agent type per kind of unit:**

| Kind of unit | Agent type | Notes |
|---|---|---|
| Recon on unfamiliar code before a unit | `Explore` | read-only; returns conclusions, not file dumps |
| Detailed design of a milestone right before it starts | `Plan` | M3 and M5 warrant this; M0–M2 are specified enough |
| Implementation of one unit | `general-purpose` | one unit per agent, never a whole milestone |
| Review after each milestone | `feature-dev:code-reviewer` | confidence-filtered; catches what tests don't |

**Concurrency rules:**

- **At most 3 concurrent agents.**
- Units touching disjoint files run in parallel with `isolation: "worktree"` — M1's C2 / C3 / C9 are
  the obvious first parallel batch.
- **Benchmark runs are strictly serialized.** Two JMH runs on one machine contend for CPU and
  silently corrupt each other's numbers. In practice, run benchmarks from the orchestrator between
  units rather than inside them.
- Anything touching `core/api/*.api` runs alone.

**Granularity:** one unit ≈ one commit ≈ one agent. M3 and M5 are too large for a single agent and
must be decomposed into per-file or per-subsystem units first (that decomposition is itself a `Plan`
agent task, done at the start of the milestone, output written to `docs/plans/notes/`).

---

## Workstream A — Performance measurement foundation

**Do this first, on `main`, before touching anything else.** Without it, the migration is
unfalsifiable.

### A1. Fix methodology bugs in the existing benchmarks

Three real defects in `benchmark/src/main/kotlin/com/github/avrokotlin/benchmark/`:

1. **Dead-code elimination risk on every `read()`.** `complex/Avro4kBenchmark.kt:27` is:

   ```kotlin
   @Benchmark
   fun read() { Avro.decodeFromByteArray<Clients>(schema, data) }   // returns Unit — result discarded
   ```

   JMH only treats a benchmark's *return value* as consumed. Change every `read()` to
   `fun read(): Clients = ...` (same for the `simple`, `lists`, Apache and Jackson variants, so the
   comparison stays fair).

2. **`write()` measures sink construction.** `Avro4kBenchmark.kt:33` allocates
   `OutputStream.nullOutputStream().asSink().buffered()` *inside* the measured method. Hoist a
   reusable `kotlinx.io.Buffer` into `@State` and `clear()` it, or build one discarding sink in
   `@Setup`. Today a meaningful slice of the "write" number is allocation of the harness, and it
   differs between the avro4k and Apache variants.

3. **Only one data shape per workload.** Add `@Param` on the generators in
   `internal/ClientsGenerator.kt` / `SimpleDataClass.kt` / `ListWrapperDataClass.kt` so record count
   and list length vary (e.g. 1 / 15 / 200). The `simple`-wins / `complex`-loses split is a
   size-scaling signal, and one point per curve can't show it.

### A2. Make allocation rate a first-class metric

Allocation pressure is the portable proxy for "will this be slow on native/JS too".

- **JMH GC profiler.** kotlinx-benchmark does not expose JMH profilers through its DSL. The plugin
  does produce a runnable JMH jar under `benchmark/build/benchmarks/main/jars/`; add a Gradle task
  that invokes it with `-prof gc` and records `gc.alloc.rate.norm` (bytes allocated per op)
  alongside throughput. *Verify the jar path and that `-prof gc` runs before building tooling around
  it — if the jar isn't self-contained, fall back to a small plain-JMH source set in the same
  module.*
- **Allocation flame graphs.** `benchmark/src/main/kotlin/com/github/avrokotlin/benchmark/ManualProfiling.kt`
  already provides `ManualProfilingRead` / `ManualProfilingWrite` `main()`s looping 1,000,000
  iterations — the right entry point for async-profiler (`-e alloc`). Document the invocation in
  `benchmark/README.md`.

`gc.alloc.rate.norm` becomes the primary acceptance metric for workstream C: **bytes/op must go down
and must not be regressed by the migration.**

### A3. Add micro-benchmarks for the specific hot paths

The three existing workloads are end-to-end; they can't attribute a regression. Add a `micro`
package isolating the suspects identified in §C:

| Micro-benchmark | Isolates |
|---|---|
| Deeply-nested record (depth 1/3/8, one field each) | per-structure encoder/decoder allocation (C1) + `resolveFields` re-lookup (C2) |
| `List<Long>` / `List<SmallRecord>` at 10 / 1k / 100k | collection serializer wrapping (C3), block decoding |
| `String?` and `String` field storms | union resolution chain (C4), string encode/decode (C5) |
| Sealed-interface field storms | polymorphic resolve cost (C6) |
| Record with descriptor field order ≠ schema field order | `ReorderingCompositeEncoder` (C8) |
| `Avro.schema<T>()` cold, and warm cache hit | schema inference + cache lookup cost (C7) |

### A4. Capture and commit the baseline

Run the full suite on `main`, commit the numbers (throughput **and** bytes/op) into
`benchmark/README.md` with machine spec and date. Every later claim is measured against this file.

**Deliverable of A: a `perf/baseline` commit on `main` that is mergeable independently of
everything else.**

---

## Workstream B — KMP migration

Target shape:

```
core/                  KMP: jvm, js(IR), linux/mingw/macos/ios/tvos/watchos/androidNative
  src/commonMain       AvroSchema, binary codec, encoders/decoders, schema visitors,
                       resolvers, kotlin-stdlib serializers
  src/jvmMain          java.time / BigDecimal / BigInteger / UUID / URL serializers,
                       java.io stream + ByteBuffer extensions
  src/jsMain,
  src/nativeMain       actuals for the identity/weak caches

apache-interop/        NEW, JVM-only: Schema <-> AvroSchema, encodeWithApacheEncoder /
                       decodeWithApacheDecoder, GenericData extensions, ListRecord/Record,
                       JVM object-container codecs (deflate/snappy/bzip2)

confluent-kafka-serializer/   JVM, depends on core + apache-interop
kotlin-generator/             JVM, drops its duplicate AvroSchema and uses core's
gradle-plugin/                unchanged
benchmark/                    KMP (see A / C verification)
```

Putting Apache interop in a **separate module** rather than `core/jvmMain` means plain JVM users
stop pulling `org.apache.avro` transitively — today it's an `api` dependency of `core`.

### B0. Build conventions

Add `buildSrc/src/main/kotlin/library-multiplatform-module-conventions.gradle.kts` (start from the
`kmp` branch's version, `git show kmp:buildSrc/src/main/kotlin/library-multiplatform-module-conventions.gradle.kts`).
Keep `library-module-conventions.gradle.kts` for the JVM-only modules. Carry over from the `kmp`
branch:

- the target list in `core/build.gradle.kts`,
- the Apple-simulator guard (`git show kmp:core/build.gradle.kts`, the `HostManager.hostIsMac` block
  that skips simulator test tasks when no runtime is installed).

`explicitApi()`, spotless and binary-compatibility-validator all work under MPP; `apiDump` will start
emitting per-target `.api` files. `dokka-javadoc` is JVM-only — keep it scoped to the JVM modules.

**Test stack:** `mockk` is JVM-only. Move the handful of `mockk` usages to `jvmTest`, or replace them
with hand-written fakes so the tests can run in `commonTest`. `kotest-assertions-core` is
multiplatform; `kotest-runner-junit5` is not — `commonTest` uses `kotlin("test")` + kotest
assertions.

### B1. `AvroSchema` into `core/commonMain` — the critical path

Take the **607-line `kmp`-branch version** (`git show kmp:core/src/commonMain/kotlin/AvroSchema.kt`),
not the 280-line one on `main`, plus `AvroSchemaJsonDeserializer.kt` / `AvroSchemaJsonSerializer.kt`.
Then delete `kotlin-generator/.../AvroSchema.kt`, `AvroSchemaJsonSerialization.kt`,
`AvroSchemaJvmInterop.kt` — `kotlin-generator` already has `implementation(project(":core"))`.

> **Perf caveat, decide before porting further:** the `kmp` `AvroSchema` variants are `data class`es,
> so `equals`/`hashCode` are structural and recursive over record fields. Apache's `Schema` is the
> same, which is why `RecordResolver` needs a two-level cache keyed on schema. Under the new model,
> **every schema-keyed cache must be identity-based** (`WeakIdentityKeyCache` on the `kmp` branch
> already is). Audit that no hot path ever calls `AvroSchema.equals`. Consider caching a
> lazily-computed `hashCode` on `RecordSchema`.

Then propagate the type through the public API:

```kotlin
// commonMain
public fun Avro.schema(descriptor: SerialDescriptor): AvroSchema
public val AvroDecoder.currentWriterSchema: AvroSchema
public fun <T> Avro.encodeToByteArray(writerSchema: AvroSchema, serializer: …, value: T): ByteArray
public abstract fun AvroSerializer<T>.getSchema(context: SchemaSupplierContext): AvroSchema

// apache-interop, JVM-only
public fun org.apache.avro.Schema.toAvro4k(): AvroSchema
public fun AvroSchema.toApacheSchema(): org.apache.avro.Schema
```

`AvroSchemaApacheInterop.kt` on the `kmp` branch already implements both, cached via
`WeakIdentityKeyCache`, routing through JSON. That JSON round-trip is fine for a one-off conversion
but must never sit in a hot path — hence the cache; keep it.

Two public types disappear from the common API and move to `apache-interop`:

- `AvroDecoder.decodeFixed(): GenericFixed` → return `ByteArray` in common (`AvroDecoder.kt:63`).
- `interface Record : GenericRecord, SpecificRecord` and `ListRecord` (`ListRecord.kt`) — already
  `@Deprecated`.

### B2. Replace the Apache byte-level codec ABI

`KotlinxIoEncoder` extends `org.apache.avro.io.BinaryEncoder` and `KotlinxIoDecoder` extends
`org.apache.avro.io.Decoder`. Introduce avro4k's own minimal interfaces in `commonMain`:

```kotlin
internal interface AvroBinaryEncoder { fun writeInt(v: Int); fun writeLong(v: Long); fun writeString(v: String); … }
internal interface AvroBinaryDecoder { fun readInt(): Int; fun readLong(): Long; fun readString(): String; … }
```

- `KotlinxIoDecoder` (`internal/decoder/direct/KotlinxIoDecoder.kt`) is already 95% platform-free:
  hand-rolled `readVarInt`/`readVarLong` over `kotlinx.io` with an `UnsafeBufferOperations` segment
  fast path. What must go: `org.apache.avro.util.Utf8`, `java.nio.ByteBuffer` in `readBytes`, and
  `org.apache.avro.SystemLimitException.checkMaxStringLength/checkMaxBytesLength` (reimplement as a
  simple bound check).
- `KotlinxIoEncoder` delegates varint writing to `org.apache.avro.io.BinaryData.encodeInt/encodeLong`
  — reimplement in common (~20 lines, mirror of the existing decode side).
- Keep `AvroBinaryEncoder`/`Decoder` adapters over Apache's `Encoder`/`Decoder` in `apache-interop`,
  so `@InternalAvro4kApi encodeWithApacheEncoder` / `decodeWithApacheDecoder`
  (`internal/AvroInternalExtensions.kt`) keep working — the Confluent module depends on them.
- `configuration.validateSerialization` currently wraps in Apache's
  `ValidatingEncoder`/`ValidatingDecoder`. Either reimplement in common or make the flag JVM-only for
  v3.0 and restore it later. **Prefer JVM-only initially** and say so in the migration guide — it's a
  debugging aid, not a hot path.

This step is where B2 and C5 merge: dropping `Utf8` and `ByteBuffer` removes two per-value
allocations outright.

### B3. Move the serialization core to `commonMain`

Mostly mechanical once B1/B2 land, because the logic is already platform-free:

- `internal/schema/*` (8 visitors). Replace `SchemaBuilder.enumeration(...)` (`ValueVisitor.kt:48`)
  and `JsonProperties` (`ClassVisitor.kt`) with direct `AvroSchema` construction.
- `internal/encoder/**`, `internal/decoder/**`, `internal/RecordResolver.kt`,
  `PolymorphicResolver.kt`, `EnumResolver.kt`, `SerializerLocatorMiddleware.kt`, `helpers.kt`,
  `NumberUtils.kt`, `exceptions.kt`.
- **Drop Jackson.** `internal/helpers.kt:3-5,206` uses `com.fasterxml.jackson.databind.ObjectMapper`
  (an undeclared transitive of Apache Avro) to parse `@AvroProp` JSON. Replace with
  `kotlinx.serialization.json` — `RecordResolver.kt` already parses `@AvroDefault` with
  `kotlinx.serialization.json`, so follow that pattern.
- `internal/NumberUtils.kt`: the `BigDecimal` overloads move to `jvmMain`; the `Int`/`Long`/`Double`
  exact-conversion helpers stay common.

### B4. Split the serializers

| Serializer file | Destination |
|---|---|
| `serializer/KotlinStdLibrarySerializers.kt` (`Uuid`, `Instant`, …), `KotlinInstantExtensions.kt`, `AvroDuration.kt`, `AvroSerializer.kt` | `commonMain` (`AvroDuration.kt` needs its `java.nio.ByteBuffer`/`ByteOrder` use replaced; `@JvmStatic` at lines 69/79/94/114 must go or be guarded) |
| `serializer/JavaTimeSerializers.kt` (550 lines, `java.time.*`) | `jvmMain` |
| `serializer/JavaStdLibSerializers.kt` (`BigDecimal`, `BigInteger`, `UUID`, `URL`, `Conversions`, `LogicalTypes`) | `jvmMain` |
| `serializer/AnySerializer.kt` (`Class<out Any>`, `isAssignableFrom`, `ConcurrentHashMap`) | `jvmMain`, with a reduced common variant |

`Avro.Default`'s serializers module (`Avro.kt:48-54`) composes
`JavaStdLibSerializersModule + JavaTimeSerializersModule + KotlinStdLibSerializersModule + AnyTypeSerializersModule`.
Make this an `expect`/`actual` default: JVM keeps all four, other platforms get the Kotlin ones.
**This is a silent behaviour difference between platforms** — document it prominently.

Open gaps with no common answer:

- **`BigDecimal`/`BigInteger`** — no Kotlin stdlib equivalent, so `decimal`/`big-decimal` logical
  types are JVM-only in v3.0 unless a common arbitrary-precision type is introduced. Flag as a known
  limitation; do not block the milestone on it.
- **`AnySerializer.inferSerializationStrategyFromNonSerializableType(type: Class<out Any>)`** is a
  `protected`/open extension point typed on `java.lang.Class` — it cannot be common. Keep the JVM
  one; common gets a `KClass`-keyed variant.
- **`helpers.kt:188`** `capturedKClass?.java?.let(serializersModule::serializerOrNull)` uses the
  JVM-only `serializer(Class)` overload. Needs a common path via
  `SerializersModule.getContextual(KClass)`.

### B5. Object container (OCF) and single-object encoding

- `AvroSingleObject.kt:42` uses `SchemaNormalization.parsingFingerprint("CRC-64-AVRO", schema)`.
  Implement **Parsing Canonical Form + CRC-64-AVRO in common** (~80 lines; the canonical form is a
  well-specified subset transform and CRC-64-AVRO is a fixed table). Also memoize the fingerprint per
  schema — today it's recomputed on **every encode**, which is a standalone perf bug.
- `AvroObjectContainer.kt` delegates the whole OCF format to `DataFileWriter`/`DataFileStream`/
  `CodecFactory`. Reimplement the container format in common (header + metadata map + 16-byte sync
  marker + block framing) behind a pluggable codec:

  ```kotlin
  public interface AvroCodec { val name: String; fun compress(b: ByteArray): ByteArray; fun decompress(b: ByteArray): ByteArray }
  ```

  `null` codec in `commonMain`; `deflate`/`snappy`/`bzip2` in `apache-interop` adapting
  `CodecFactory`. `AvroObjectContainerWriter : java.io.Closeable` becomes `AutoCloseable`.

  This is the **largest discretionary chunk of B**. If the milestone needs to shrink, OCF can stay
  JVM-only for v3.0 and land in v3.1 — nothing else depends on it.

### B6. Caches → `expect`/`actual`

`internal/Cache.kt`'s `WeakKeyCache` (`ConcurrentHashMap` + `WeakReference` + `ReferenceQueue`) and
the two `IdentityHashMap`s in `SerializerLocatorMiddleware.kt` need platform implementations. The
`kmp` branch already has `WeakIdentityKeyCache` + `IdentitySet` with `jsMain`/`nativeMain` actuals —
start there. See §C7 for why this is also a perf item.

Note `WeakKeyCache` is `@InternalAvro4kApi` **public**; changing it is not an API break for users but
is for the Confluent module (`Serializers.kt:206`).

### B7. Downstream modules

- `confluent-kafka-serializer`: JVM-only, add `apache-interop`. Uses `Utf8`, `GenericData`,
  `Class.forName`, `ThreadLocal<Schema>` — all fine on JVM. Mechanical fixes for the
  `Schema` → `AvroSchema` signature change.
- `kotlin-generator`: delete its three `AvroSchema*.kt` files, use core's. `KotlinGenerator.kt:489,558`
  use `Charsets.ISO_8859_1` — JVM-only module, so no change needed.
- `gradle-plugin`: no source change; it publishes `core` + `bom` to mavenLocal in tests, which now
  means multi-variant publication — verify `:gradle-plugin:test` still resolves.
- `bom`: unchanged shape, add `apache-interop`.

### B8. API surface, publication, docs

- `./gradlew apiDump` regenerates per-target `.api` files; review the diff as the authoritative list
  of breaking changes.
- `com.vanniktech.maven.publish` handles KMP publication; artifact IDs stay `avro4k-<module>` with
  the usual `-jvm`, `-js`, `-linuxx64`… suffixes.
- Rewrite `README.md:23-25` (the JVM-only warning), add a **v2 → v3 migration guide** alongside
  `Migrating-from-v1.md`, covering: `Schema` → `AvroSchema`, `decodeFixed` returning `ByteArray`, the
  `apache-interop` module, `java.time`/`BigDecimal` being JVM-only, and `validateSerialization` (if
  deferred).

---

## Workstream C — Performance improvements

Ranked by expected payoff on the chosen focus areas. Each is platform-neutral and each is verified
against the A-baseline. C1 and C2 are the two that explain the `simple`-wins / `complex`-loses split.

### C1. Stop allocating an encoder/decoder per structure — *highest payoff*

`AbstractAvroDirectDecoder.beginStructure` (`internal/decoder/direct/AbstractAvroDirectDecoder.kt:48-95`)
allocates a fresh `RecordDirectDecoder` / `ArrayBlockDirectDecoder` / `MapBlockDirectDecoder` /
`PolymorphicDecoder` for **every** record, array block, map block and polymorphic value. The encode
side mirrors this. For `List<Client>` that is one decoder object per element; for nested records, one
per node.

This is the structural reason avro4k beats Apache on flat records and loses as nesting grows — and it
is *worse* on native/JS, where there is no escape analysis to rescue short-lived objects.

**Fix:** restructure to a single decoder/encoder instance holding an explicit stack of frames (the
approach `kotlinx.serialization.json`'s `StreamingJsonDecoder` uses — reuse one object, push/pop
state). Frames are reusable array slots, so steady-state allocation is zero.

This is the largest refactor in the plan. It touches `AbstractAvroDirectDecoder`,
`AbstractAvroDirectEncoder`, `RecordDirect{En,De}coder`, `CollectionsDirect{En,De}coder`,
`AbstractPolymorphicDecoder`. **Do it after B2/B3**, when the codec interface is already avro4k's own
— doing it before means doing it twice.

### C2. Don't re-resolve the record workflow per instance

`RecordResolver.resolveFields` (`internal/RecordResolver.kt:47-53`) is correct and well-cached, but it
is called from **every** `RecordDirectDecoder` / `RecordDirectEncoder` / `RecordGenericDecoder`
constructor — i.e. once per record *instance*. Each call is two nested `WeakKeyCache.getOrPut`s, and
each of those:

```kotlin
override fun getOrPut(key: K, compute: () -> V): V {
    removeStaleEntries()                            // ReferenceQueue.poll(), synchronized, per call
    val hash = key.hashCode()
    map[Key.Lookup(hash, key)]?.let { return it }   // allocates Key.Lookup, passed to CHM.get -> escapes
    …
}
```

So decoding a `List<Client>` of 10,000 elements performs 20,000 cache lookups and 20,000 `Key.Lookup`
allocations for a workflow that was already resolved on element 1.

**Fix (cheap, do this first — it is independent of B and can land on `main` today):** memoize the
resolved `SerializationWorkflow` on the *parent* structure. Concretely, add a lazily-filled field to
`DecodingStep.DeserializeWriterField` (and the array/map element schema holder) caching the child
workflow, so a homogeneous collection resolves once. A one-entry inline cache on the resolver keyed by
last-seen `(schema, descriptor)` identity would capture most of the same win with less surgery.

**Also:** `removeStaleEntries()` on every read is wasteful — amortize it (e.g. every N misses, or only
on `put`).

### C3. Stop wrapping collection serializers per collection

`SerializerLocatorMiddleware.apply(deserializer)` runs on **every** `decodeSerializableValue`
(`AbstractAvroDirectDecoder.kt:42`) and allocates a new wrapper per collection:

```kotlin
(deserializer as? AbstractCollectionSerializer<*, T, *>)?.let { return AvroCollectionSerializer(deserializer) }
```

**Fix:** memoize in an identity map keyed by the collection serializer (it's a stable singleton per
type). Directly targets the `lists` workload, where avro4k currently loses 15% on write.

### C4. Precompute union branch selection on encode

`AbstractAvroEncoder.encodeString` (`internal/encoder/AbstractAvroEncoder.kt:323`) walks up to **nine**
resolution attempts per string value: STRING → BYTES → `trySelectFixedSchemaForSize` →
`trySelectEnumSchemaForSymbol` → BOOLEAN → INT → LONG → FLOAT → DOUBLE. Two of those are linear scans
over the union branches (`AvroEncoder.kt`, `trySelectFixedSchemaForSize` /
`trySelectEnumSchemaForSymbol`). `encodeBytes`/`encodeFixed` do up to 3 including a scan; numeric
encoders do up to 3 map lookups.

The common `String?` case (union `["null","string"]`) hits on attempt 1 and is fine. Wide unions are
where this bites.

**Fix:** cache, per union schema, a small `Kind → branchIndex` table computed once (identity-keyed,
alongside the schema). Turns the whole chain into one array read.

**Also:** `SerialDescriptor.aliases` (`internal/helpers.kt:86`) allocates a **new `Set` on every call**
via `findAnnotation<AvroAlias>()?.value?.toSet()`, and `findAnnotation` linearly scans the annotation
list. It's already passed lazily as a lambda in `trySelectNamedSchema`, but should be cached per
descriptor.

### C5. String and bytes encode/decode without intermediate arrays

- `KotlinxIoEncoder.writeString` encodes to a `ByteArray` then copies into the sink — two passes and
  one allocation per string. **Fix:** compute the UTF-8 byte length with a non-allocating scan, write
  the varint length, then `sink.writeString(value)` directly. kotlinx-io supports this on all targets.
- `KotlinxIoDecoder.readString(old: Utf8?)` **ignores the reusable `old` buffer**, allocating a
  `ByteArray` *and* a `Utf8` per call. Removing `Utf8` entirely in B2 deletes this.
- `AbstractAvroDirectDecoder`'s `readFixedBytes(size)` and `decodeFixed` allocate a `ByteArray` plus a
  `GenericData.Fixed` wrapper per value. B1 changes `decodeFixed` to return `ByteArray`, removing the
  wrapper.
- `decodeBytes` on a STRING writer schema goes `binaryDecoder.readString(null).bytes` — a `Utf8`
  allocation purely to reach its backing array.

> **Correctness note found while reading:** the private `Decoder.readBytes()` helper at the bottom of
> `AbstractAvroDirectDecoder.kt` does `if (buffer.hasArray()) return buffer.array()` — it returns the
> **whole backing array**, ignoring `arrayOffset()`/`position()`/`limit()`. It happens to be safe with
> the current `KotlinxIoDecoder` (whose fast path returns a read-only buffer where `hasArray()` is
> false, and whose slow path allocates an exactly-sized buffer), but it is wrong for any other
> `Decoder` implementation — and `decodeFromByteBuffer` in `AvroJVMExtensions.kt` supplies a different
> one. Worth a test and a fix regardless of this plan.

### C6. Polymorphic decode allocations

`AbstractPolymorphicDecoder.decodeString()` (`internal/decoder/AbstractPolymorphicDecoder.kt:26-53`)
allocates a `Pair<String, Schema>` per polymorphic value plus a `WeakKeyCache` lookup. The direct path
is otherwise O(1); the **generic** path (`PolymorphicGenericDecoder.kt:19`) linearly scans all union
branches. The `Clients` benchmark model has a sealed `Partner` field, so this is on the measured path.

**Fix:** return the two values via fields instead of a `Pair`; fold the resolver lookup into the cached
record workflow.

### C7. Cache implementation — a migration decision with a perf consequence

`WeakKeyCache` must become `expect`/`actual` anyway (B6). All these caches are **read-mostly,
write-once**: schemas, record workflows, polymorphic name maps, enum defaults. That makes a
copy-on-write map behind an atomic reference a genuinely *better* fit than `ConcurrentHashMap` — reads
become a plain volatile read plus a hash lookup with no `ReferenceQueue` poll, on every platform.

**Proposal:** identity-keyed, copy-on-write, `kotlinx.atomicfu`-backed, with the weak-reference
behaviour `expect`/`actual` (real weak refs on JVM, `kotlin.native.ref.WeakReference` on native, strong
refs on JS where `WeakRef` support varies). The `kmp` branch's `WeakIdentityKeyCache` is the starting
point. Identity keying also sidesteps the `AvroSchema` structural-equality cost flagged in B1.

Measure this one carefully: it changes GC behaviour for long-lived `Avro` instances, and strong refs on
JS are a deliberate leak trade-off that must be documented.

### C8. `ReorderingCompositeEncoder` without closures

`internal/encoder/ReorderingCompositeEncoder.kt` buffers every field as a `BufferedCall` data class
wrapping a **capturing lambda**, in an `Array<BufferedCall?>(n)`, plus a `BufferingInlineEncoder` per
inline element. This only triggers on `EncodingWorkflow.NonContiguous` (descriptor field order ≠ writer
schema order), so it is not on the default path — but for schema-evolution-heavy users it is the
dominant cost.

**Fix:** buffer primitives into typed arrays with an index permutation instead of closures. Lower
priority than C1–C4; capture it in the A3 micro-benchmark so the cost is at least visible.

### C9. Smaller, independent wins

- **`AvroSingleObject` recomputes `SchemaNormalization.parsingFingerprint` on every encode**
  (`AvroSingleObject.kt:42`). Memoize per schema. Trivial fix, real win for Kafka-style workloads.
- `EnumResolver` caches `EnumDefault(index: Int?)` — a data class holding a **boxed** `Int?`. Use a
  sentinel `Int` instead.
- `MapGenericEncoder` allocates **two** `Pair`s per entry (`ArrayList<Pair<…>>` then
  `entries.associate { it.first to it.second }`); `MapGenericDecoder` builds a sequence pipeline with 2
  `Pair`s per entry. Generic tree only, so lower priority — but cheap to fix.
- `Avro.encodeToByteArray` does `Buffer()` + `readByteArray()` (full copy). `decodeFromByteArray` is
  already zero-copy via `UnsafeBufferOperations.moveToTail` (`internal/helpers.kt:217`); consider a
  sized-buffer path for encode.

### Known limits worth stating honestly

Some of the gap to Apache `ReflectData` is **structural to kotlinx-serialization** and not fixable
here:

- kotlinx-serialization cannot encode elements out of order, which is exactly why
  `ReorderingCompositeEncoder` exists (its own KDoc says so).
- `decodeSerializableValue` / `encodeSerializableValue` are virtual calls through
  `SerializationStrategy` per value — no inlining across the boundary.
- `beginStructure` returning a `CompositeDecoder` is what invites C1's per-structure allocation; C1
  works *around* the API (one instance, many frames) rather than removing the call.

These must be written down in `benchmark/README.md` at the end so the remaining delta is understood
rather than re-investigated.

---

## Verification

**Per change:**

```bash
./gradlew :core:test :confluent-kafka-serializer:test :kotlin-generator:test   # after B: also jsTest, <native>Test
./gradlew apiDump && git diff --exit-code   # intentional API changes only
./gradlew spotlessApply
./gradlew actionsBeforeCommit               # the repo's own pre-commit aggregate
```

**Per perf change:**

```bash
./gradlew :benchmark:benchmark              # full suite -> build/reports/benchmarks/main
./gradlew :benchmark:avro4k                 # avro4k only, faster loop
# allocation: run the generated JMH jar with -prof gc (A2), compare gc.alloc.rate.norm
# attribution: async-profiler -e alloc on ManualProfilingRead / ManualProfilingWrite
```

Accept a change only if **throughput is non-regressed and bytes/op is down** on the A3
micro-benchmark that targets it, and the `complex` / `lists` end-to-end numbers move in the right
direction.

**Migration correctness:** the existing `core/src/test` suite (`encoding/`, `schema/`, `serializer/`)
is the safety net — it must pass unchanged in `jvmTest` throughout B, then progressively move to
`commonTest` as each piece lands. A round-trip test asserting
`AvroSchema.toApacheSchema().toAvro4k() == original` over every schema in the test suite guards B1.
Cross-platform: the same encode/decode fixtures must produce byte-identical output on jvm, js and
native.

**End-to-end:** publish to mavenLocal (`./gradlew publishToMavenLocal`) and build a tiny consumer for
jvm + js + one native target that encodes and decodes a nested data class.

---

## Sequencing

| Milestone | Contents | Agents | Shippable? |
|---|---|---|---|
| **M-1 — Persist the plan** | Create `CLAUDE.md`, `docs/plans/v3-kmp-and-performance.md`, `docs/plans/PROGRESS.md`, the `project` memory entry. Commit. **Do this first.** | orchestrator, inline | Yes |
| **M0 — Measure** | A1–A4. Fix benchmark DCE + harness allocation, add `@Param`, add micro-benchmarks, wire allocation profiling, commit baseline. | 1 `general-purpose` per unit; benchmark runs serialized from the orchestrator | Yes, merges to `main` alone |
| **M1 — Cheap perf wins on `main`** | C2 (workflow memoization), C3 (collection serializer cache), C9 (fingerprint memoization, enum boxing), and the `readBytes()` offset fix from C5. No API change, no KMP dependency. | 3–4 parallel `general-purpose` in worktrees (disjoint files), then one `feature-dev:code-reviewer` | Yes — ship as **v2.x** |
| **M2 — KMP foundation** | B0, B1, B6. Multiplatform build, `AvroSchema` in `commonMain`, identity caches. Core still compiles JVM-only internally. | sequential `general-purpose` (B0 → B1 → B6); B0 touches the build, so no parallelism | No |
| **M3 — Decouple from Apache** | B2, B3, B4. Own codec ABI, visitors/resolvers/encoders/decoders to `commonMain`, serializer split, Jackson removed. **Re-run the M0 baseline here — this is where a regression would hide.** | `Plan` agent decomposes into units first → then `general-purpose` per unit → `feature-dev:code-reviewer` | No |
| **M4 — Feature parity** | B5 (CRC-64-AVRO + OCF in common), B7 (downstream modules), B8 (api dump, publication, migration guide). | `general-purpose` per unit; B8 runs alone (`apiDump`) | **v3.0.0** |
| **M5 — Structural perf** | C1 (frame-stack encoder/decoder), C4 (union branch tables), C5 (string/bytes fast paths), C6, C8. Done *after* the codec is avro4k's own so it's done once. | `Plan` agent decomposes C1 first; C1 gets a dedicated worktree and a dedicated agent | **v3.1** |

M1 exists deliberately: it delivers measurable improvement to current users before the multi-month v3
work lands, and it de-risks M5 by proving the measurement setup catches real wins.

---

## Critical files

| Concern | Files |
|---|---|
| Build | `buildSrc/src/main/kotlin/library-module-conventions.gradle.kts`, new `library-multiplatform-module-conventions.gradle.kts`, `core/build.gradle.kts`, `settings.gradle.kts` |
| Schema model | `kotlin-generator/src/main/kotlin/com/github/avrokotlin/avro4k/AvroSchema*.kt` (→ delete), `git show kmp:core/src/commonMain/kotlin/AvroSchema.kt` (→ source of truth) |
| Public API | `core/src/main/kotlin/com/github/avrokotlin/avro4k/{Avro,AvroEncoder,AvroDecoder,AvroSingleObject,AvroObjectContainer,ListRecord}.kt`, `core/api/core.api` |
| Codec ABI | `internal/{encoder,decoder}/direct/KotlinxIo{Encoder,Decoder}.kt`, `internal/AvroInternalExtensions.kt` |
| Perf hot paths | `internal/decoder/direct/AbstractAvroDirectDecoder.kt`, `internal/encoder/AbstractAvroEncoder.kt`, `internal/RecordResolver.kt`, `internal/Cache.kt`, `internal/SerializerLocatorMiddleware.kt`, `internal/encoder/ReorderingCompositeEncoder.kt` |
| Benchmarks | `benchmark/build.gradle.kts`, `benchmark/README.md`, `benchmark/src/main/kotlin/com/github/avrokotlin/benchmark/**` |
| Plan persistence | `CLAUDE.md`, `docs/plans/v3-kmp-and-performance.md`, `docs/plans/PROGRESS.md`, `docs/plans/notes/` |

## Risks

- **M5 (C1) is the riskiest change** — rewriting the encoder/decoder as a frame stack touches every
  structural path. Mitigation: it comes last, behind a full test suite that by then runs on three
  platform families.
- **The `kmp` branch is 5+ months stale** and `main` has moved (`kotlin.time.Instant` support,
  Confluent 8.3.0, kotlinx-serialization 1.11.0). Expect to cherry-pick ideas and files rather than
  merge.
- **`BigDecimal`/`decimal` logical types will be JVM-only in v3.0.** A genuine feature regression for
  non-JVM targets, not a temporary gap — no common arbitrary-precision type exists.
- **Weak references on JS** are inconsistent; strong-ref caches there mean unbounded growth if callers
  create many `Avro` instances or descriptors. Must be documented.
- **A2 depends on an unverified mechanism** — whether kotlinx-benchmark's generated JMH jar accepts
  `-prof gc`. Check this early in M0; if it doesn't, a small plain-JMH source set is the fallback and
  costs about a day.
- **Orchestration drift.** The failure mode of a multi-session, agent-driven effort is a sub-agent
  re-deciding something already settled (e.g. reintroducing `org.apache.avro.Schema`, or "helpfully"
  restarting from the stale `kmp` branch). Mitigation: every brief carries the locked decisions
  verbatim, and `PROGRESS.md` records *why* a unit was done, not just that it was.
