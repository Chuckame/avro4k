# M5 — Structural performance: decomposition into units

> Output of the M5 `Plan` agent, 2026-10-18, on `v3/m2-foundation` at `1f970d94`. This was a read-only analysis: nothing was built, run or benchmarked.
>
> Every path and claim below was checked against the code. Where stated, it was also checked against the kotlinx-serialization 1.11.0, kotlinx-serialization-json 1.11.0 and kotlinx-io 0.9.1 sources in the Gradle cache, and against `javap` of the build output present on 2026-10-18 (`core/build/classes`, built at 01:20 from the tree before the last two doc commits).
>
> Object counts are derived from the code. Byte sizes are estimates from field layouts (compressed oops, 8-byte alignment), checked against the escape-analysis-off B/op rows of `benchmark/README.md` where those exist.
>
> **Orchestrator review (spot-checked before commit, 2026-10-18):** the two units the plan does not list rest on facts
> confirmed in the code. `decodeCollectionBlocks` (`AbstractAvroDirectDecoder.kt:86-93`) runs a whole second `merge`
> after a non-empty first block only to read the terminating 0 (M5-01). `RecordDirectDecoder` never overrides
> `decodeSequentially`, while the collection, record-as-map, polymorphic and JSON-default decoders do (M5-03). The open
> questions in §7 are answered in `PROGRESS.md`'s decision log before any unit starts.

> **Decisions applied (2026-10-20, `PROGRESS.md` decision log):** Q1 strings stay on the standard library's
> `decodeToString`, never kotlinx-io's decoder → **M5-08 dropped**. Q3 → **M5-09 to v3.1**. Q4 → M5-01 and M5-03 in
> v3.0. Q5 per-level reuse agreed; the orchestrator explains how to the user before implementing (done for decoders,
> 2026-10-20). Q6 and Q8 → **reuse across calls and M5-10 set aside** in
> [`backlog-after-v3.0.md`](backlog-after-v3.0.md). Batch 5 is then empty: **AB-W runs after batch 4** with AB-R, without
> the reordering rows (`FieldOrder.write` stays as a control).

> **kotlinx-io leaves core before batch 1 (user, 2026-10-24, `notes/m4-no-stream-library.md`).** M5-00 writes with
> `encodeToByteArray` instead of a `kotlinx.io.Buffer`; M5-07 encodes strings straight into the new `ByteArrayEncoder`'s
> array instead of `sink.writeString`. `KotlinxIoEncoder` and `KotlinxIoDecoder`, cited below, no longer exist then.

Path shorthands:
- `C` = `core/src/commonMain/kotlin/com/github/avrokotlin/avro4k`
- `D` = `C/internal/decoder/direct`
- `E` = `C/internal/encoder`
- `K` = `C/internal/codec`
- `J` = `core/src/jvmMain/kotlin/com/github/avrokotlin/avro4k`
- `AI` = `apache-interop/src/main/kotlin/com/github/avrokotlin/avro4k`
- `CF` = `confluent-kafka-serializer/src/main/kotlin/com/github/avrokotlin/avro4k/kafka/confluent`
- `BM` = `benchmark/src/main/kotlin/com/github/avrokotlin/benchmark`
- `kx-ser` = `kotlinx-serialization-core-1.11.0-sources.jar`
- `kx-io` = `kotlinx-io-core-0.9.1-sources.jar`

## Executive summary

### Units

12 units, one commit and one agent each (§4):

| Unit | What | Plan item |
|---|---|---|
| M5-00 | Multiplatform common benchmarks (conditional on Q2) | — |
| M5-01 | One merge per single-block collection | new |
| M5-02 | Decoders reused per nesting level | C1, decode |
| M5-03 | Exact-match records decoded in order | new |
| M5-04 | Polymorphic decode without per-value allocation | C6 |
| M5-05 | Encoders reused per nesting level | C1, encode |
| M5-06 | Union branch tables and small per-value encode methods | C4 |
| M5-07 | Strings written without an intermediate array | C5 |
| M5-08 | String read decision (conditional on Q1) | C5 |
| M5-09 | `fixed`/`bytes` logical types without a copy (conditional on Q3) | C5 part A |
| M5-10 | Reordering encoder without closures | C8 |
| M5-11 | Re-baseline, review, known limits | — |

### C1 decision

**Per-level reuse, not a frame stack (§3).**
- Each decoder and encoder keeps one reusable child per structure kind, with a busy guard that falls back to allocating, and an identity memo of the resolved record workflow.
- Per-value allocation of the decoder/encoder layer goes to zero; a call keeps O(depth × kinds) objects, about 5–10.
- The classes, their small hot methods and the call shapes the JIT sees today stay as they are.

### Where the read gap really is

A8 found that allocation does not explain the JVM read gap. Two decoder-structure costs the plan does not list do contribute:
- **M5-01:** the closing empty block of every collection is a full `merge`, with a second block decoder and two array copies for `Array`/`LongArray`.
- **M5-03:** record decoders never take kotlinx's sequential path, so each field pays `decodeElementIndex`, the generated switch and a per-value step check.

### Already done or obsolete (§0)

- C2: done in M1. Its deferred deeper memo is replaced by the per-slot memo of M5-02/M5-05.
- C3: done in M1, then removed in M2.
- C7: done in M3-06.
- C9: done in M1.
- C5: three of its four bullets and its correctness note are obsolete.
- C4 and C6 are cheaper than the plan says.

### A/B checkpoints (§6)

| Checkpoint | When | Sides | Time |
|---|---|---|---|
| AC-1, allocation only | after batch 3 | 2 | ~20 min |
| AB-R, reads | after batch 4 | 4 | ~2.8 h |
| AB-W, writes + reordering rows | after batch 5 | 4 | ~3 h |
| AB-S, string read (optional) | if Q1 = threshold | 3 | ~1 h |
| AB-L, logical types (optional) | if M5-09 | 2 | ~20 min |
| Final re-baseline | M5-11 | 2 | ~4.5 h |

Total about 10–12 h of machine time, in 3–4 overnight slots. Reference: 26 s per end-to-end row-run, 13 s per micro row-run.

### API

No unit is expected to change `core/api/*.api`. Every touched public class is `@InternalAvro4kApi`, which `nonPublicMarkers` excludes (`build-logic/src/main/kotlin/library-multiplatform-module-conventions.gradle.kts:15`). A unit whose `apiCheck` fails stops and reports.

### Open questions (§7)

- Q1: the string decoding decision.
- Q2: common benchmarks.
- Q3: C5 `fixed`/bytes, parts A and B.
- Q4: the two new units.
- Q5: per-level reuse instead of a frame stack.
- Q6: no reuse across calls.
- Q7: where A/B numbers land for batched units.
- Q8: whether C8 stays in M5.

## 0. Where the plan is stale or wrong, verified against the code

1. **Paths and lines moved.**
   - Decoders and encoders are in `commonMain` since M3-15.
   - `beginStructure` is `D/AbstractAvroDirectDecoder.kt:95-136` (plan: 48-95).
   - `encodeString` is `E/AbstractAvroEncoder.kt:346-400` (plan: 323).
   - `AbstractPolymorphicDecoder.decodeString` is `C/internal/decoder/AbstractPolymorphicDecoder.kt:37-63`.
   - `PolymorphicGenericDecoder` moved to `apache-interop` in M3-19 (`AI/internal/decoder/generic/PolymorphicGenericDecoder.kt`).
   - The encoder file is `E/direct/CollectionsEncoder.kt`; there is no `CollectionsDirectEncoder`.
2. **The plan's model for C1 is wrong.** It says to "reuse one object, push/pop state, the approach `StreamingJsonDecoder` uses". In kotlinx-serialization-json 1.11.0:
   - `StreamingJsonDecoder.beginStructure` allocates a new decoder for every `LIST`, `MAP` and `POLY_OBJ` (`StreamingJsonDecoder.kt:104-112`).
   - It returns `this` only for a nested object (`:113-117`), whose state lives in the input (JSON reads the key).
   - `StreamingJsonEncoder` keeps one encoder per mode (`modeReuseCache`, `StreamingJsonEncoder.kt:96-100`).
   - Avro has no keys in the input, so a record's position must survive its nested children. §3 draws the design from this.
3. **"One decoder per array block" undercounts: it is two per non-empty collection.**
   - Since M2, blocks are merged in the decoder: `D/AbstractAvroDirectDecoder.kt:86-93` loops `merge` while the last block was non-empty. The closing `0` block is therefore a whole second `merge`.
   - That second merge goes through `beginStructure`, which allocates a second `ArrayBlockDirectDecoder`/`MapBlockDirectDecoder` (`:100-102`, `:111-113`).
   - It also runs kotlinx's `previous.toBuilder()` and `toResult()` (kx-ser `CollectionSerializers.kt:27,40`). For arrays these copy: `ArrayList(this.asList())` plus a new result array (`:196,198`); for `LongArray`, `LongArrayBuilder(this)` grown to 10, then `build()` (kx-ser `PrimitiveArraysSerializers.kt:175`).
   - avro4k (`K/KotlinxIoEncoder.kt:111-119`) and Apache always write one block plus `0`. Addressed by M5-01.
4. **"Worse on native/JS, where there is no escape analysis" is true but unmeasured.** No multiplatform benchmark exists (`benchmark/README.md:719-723`), although locked decision 4 calls for one. See M5-00 and Q2.
5. **Allocation is not the JVM read bottleneck.** A8 (`notes/a8.md:32-46`): reads were 1.2–1.4× Apache's B/op at ~2× slower, and nullable strings lost 29% throughput at ±0 B/op. M4 has since made the codec much faster (`ByteArrayDecoder`, `KotlinxIoDecoder` on the head segment), so per-field dispatch above the codec is now a larger share of read time.
   - The per-field path today:
     - generated `deserialize`
     - `decodeElementIndex` (`D/RecordDirectDecoder.kt:31-66`, 200 B of bytecode)
     - the generated `when (index)`
     - `decodeXElement` → virtual `beginElement`
     - `RecordDirectDecoder.decodeX`'s `when (currentDecodingStep)` (`:98-166`)
     - `AbstractAvroDirectDecoder.decodeX`'s `when (type)`
     - the codec.
   - Apache's generated reader calls the codec once per field.
   - M5-03 removes the first three of those steps for exact-match records. It is the CPU lever; M5-01/02/04 are the allocation levers.
6. **C2 is done (M1).** The inline identity cache is at `C/internal/RecordResolver.kt:81-128`. Its deferred "memoize on `DecodingStep.DeserializeWriterField`" variant (`notes/c2.md`, "M5 should revisit") is replaced by a one-entry identity memo on each reused record decoder/encoder (M5-02, M5-05). That needs no change to the public `@InternalAvro4kApi` `DecodingStep`.
7. **C3 is done and then superseded.** The wrapper was memoized in M1, then removed in M2 when blocks moved into the decoder (`D/AbstractAvroDirectDecoder.kt:66-93`).
8. **C4 is partly stale.**
   - "Numeric encoders do up to 3 map lookups" is stale. `getIndexTyped` is a loop comparing the stored `type` (`C/internal/schema/AvroSchemaHelpers.kt:187-193`). Name lookups are one hash lookup in `UnionSchema.typesIndexByFullName` (`C/AvroSchema.kt:216-220`).
   - Still true: fixed-size and enum-symbol selection are linear scans (`C/AvroEncoder.kt:143-165`), and `encodeString` chains up to 9 attempts (`E/AbstractAvroEncoder.kt:347-368`).
   - `SerialDescriptor.aliases` still allocates a `Set` (`C/internal/helpers.kt:53-55`), but it is now only read on a name miss (`C/internal/helpers.kt:45`, `C/AvroEncoder.kt:171-179`) or in a `throw`. The residual cost is one `Set` per record only when a descriptor matches its writer by alias alone.
   - `simple` write @25 is **no longer bimodal**: 9/9 fast forks in two A/Bs since the varint write split (`benchmark/README.md:772`, `:860`). What remains of C4's motivation is oversized encode methods and union-heavy fields.
   - Per `javap`, `encodeString` is now **776 bytes** of bytecode (437 when M3 measured it; the inline `try` of `convertForEncoding`, `C/internal/exceptions.kt:151`, grew it). `encodeInt` is 190, `encodeLong` 196, `encodeBytes` 185. All are above HotSpot's 325-byte `FreqInlineSize` except none.
9. **C5: three of four bullets are obsolete.**
   - `readString(old: Utf8?)`: `Utf8` was removed in M3-02 (`K/ByteArrayDecoder.kt:94-98`, `K/KotlinxIoDecoder.kt:126-134`).
   - `decodeFixed` returning `GenericData.Fixed`: now a `ByteArray` (`D/AbstractAvroDirectDecoder.kt:323-330`).
   - `decodeBytes` on STRING via `readString(null).bytes`: now `readStringBytes()` (`:318`).
   - The `readBytes()`/`hasArray` correctness note was fixed in M1 (`notes/c5-readbytes.md`).
   - What remains:
     - the `writeString` intermediate array (`K/KotlinxIoEncoder.kt:58-65`, with a TODO at `:63`);
     - the open string-read decision (`notes/m4-strings-and-bytebuffer.md`);
     - parts A and B of `notes/c5-fixed-and-bytes-without-copy.md`.
   - The plan's "`sink.writeString`" advice holds and is byte-identical: kotlinx-io writes `?` for an unpaired surrogate (kx-io `Utf8.kt:529,579`), which is avro4k's contract (`C/internal/Platform.kt:25-33`). But kotlinx-io's `utf8Size` is `internal` (kx-io `Utf8.kt:88`), so avro4k needs its own length scan. One already exists in `core/src/nonJvmMain/.../internal/Platform.nonJvm.kt:32-55`.
10. **C6 allocates more than the plan says.** There is no `Key.Lookup` any more (M3-06 `IdentityFirstCache`, `C/internal/PolymorphicResolver.kt:15-17`). But a polymorphic record value allocates **4** objects, not one `Pair`:
    - `PolymorphicDecoder` (`D/AbstractAvroDirectDecoder.kt:132`);
    - the `Pair` (`C/internal/decoder/AbstractPolymorphicDecoder.kt:62`);
    - an `AvroValueDirectDecoder` (`D/AbstractAvroDirectDecoder.kt:349-351`);
    - the subtype's `RecordDirectDecoder`.

    An enum subtype allocates 3. The linear branch scan is `apache-interop`'s generic tree only.
11. **C7 and C9 are done.** C7 in M3-06; C9 in M1, except `encodeToByteArray`'s copy, which was deliberately skipped (decision log 2026-09-20, C9 bullet 4).
12. **C8 is accurate but incomplete.** It misses the capturing `mapElementIndex` lambda allocated per reordered record (`E/direct/RecordDirectEncoder.kt:36-38`). Its micro-benchmark exists (`BM/micro/FieldOrderMicroBenchmark.kt:81-85`). No end-to-end workload reorders fields.
13. **The reference rows moved.** `complex` read @1 is now 413k on the `ByteArray` decoder (`README:1002`), not 342k. `simple` write @25 is not bimodal (item 8). Every A/B must take its base on the M5 start commit, not on the "M3 complete" tables.
14. **"Known limits"** is still to be written into the README, in M5-11. Two items to add:
    - kotlinx's builder protocol (`toBuilder`/`toResult`) copies arrays for every block after the first;
    - the per-call fixed cost on the @1 rows.

## 1. Rules every M5 brief carries, on top of the locked decisions

- **No renames, no new public API.** Never rename a public API, and change signature types only where the locked decisions require it. Any new public API needs the user's explicit approval: the unit stops and reports. `@InternalAvro4kApi` and `internal` members are not public API, but the `.api`/klib dumps must stay unchanged.
- **No plan step codes in source** (M5, C1, C4, AB-R, "batch 2"…): not in code, comments, KDoc, test names, strings or build scripts. Explain the reason in plain words. Every agent diff is grepped for them before merging.
- **Pre-existing bugs are not fixed here.** Reproduce them on `main` and record them in `docs/plans/notes/backlog-pre-existing-bugs.md`.
- **Units never run benchmarks**, not even `benchmarkAlloc`. The orchestrator runs them, strictly serialized, on AC power with Low Power Mode off, load average below 3, under one `caffeinate -dimsu`. At most 2 concurrent agents on the 16 GB host, and none while a benchmark runs. Any `core/api/*.api` change runs alone (none is expected).
- **Measured numbers go into `benchmark/README.md` in the same commit as the change.** For batched units, that is the commit that merges the batch and adds the A/B section (Q7). A unit's note states what it **predicts**: exact B/op deltas where they are deterministic, and the rows that should not move.
- **Shared state** (decision log 2026-09-23): reused decoders and encoders are per-call state, owned by the call that created the root. No pooling across calls, no shared mutable caches. Shared caches stay immutable or CAS-based.
- **Identity, never structural equality, on hot paths.** No `==`, `indexOf`, `contains` or equality-keyed map on an `AvroSchema`/`Field`; memos compare with `===`. Every memo guard is mutation-checked (`notes/c2.md` precedent: weaken each half of the guard, show a test failing).
- **JIT shaping is allowed** (decision 6 relaxed): a small hot method with its rare part out of line. A JVM-only actual needs a non-JVM twin with the same tests on the three platforms. Never `Unsafe`, never `ClassValue`. Each unit records in its note the `javap -c -p` bytecode size of every hot method it changes, before and after (lesson of the M4 varint split).
- **Test counts** before and after: at `1f970d94`, `:core:jvmTest` 1048, jsNode 232, macosArm64 235. List every test added.
- **`ACC`**, two invocations (Gradle 9 rejects `apiCheck` and `apiDump` in the same run):
  1. `./gradlew :core:jvmTest :core:jsNodeTest :core:macosArm64Test :apache-interop:test :confluent-kafka-serializer:test :kotlin-generator:test :benchmark:compileKotlin apiCheck`
  2. `./gradlew actionsBeforeCommit`

  Pass: all green, and `git diff --exit-code core/api apache-interop/api` clean.
- **`ACC+KMP`** = `ACC` plus linking the test binaries with no runner on this host (M3-10 finding), for units that touch `expect`/`actual` or platform source sets:
  `./gradlew :core:androidNativeArm32TestBinaries :core:androidNativeArm64TestBinaries :core:androidNativeX86TestBinaries :core:androidNativeX64TestBinaries :core:linuxArm64TestBinaries :core:iosArm64TestBinaries :core:watchosArm32TestBinaries :core:watchosArm64TestBinaries :core:watchosDeviceArm64TestBinaries :core:tvosArm64TestBinaries`
- **Native test names may not contain commas.**
- **Byte identity is guarded twice:** by the Apache oracle (`jvmTest`, unchanged) and by the common golden fixtures (M3-16) on the three platforms. No unit edits an expected byte.

## 2. Where the per-structure allocations are today

Counted per decode or encode call, excluding the decoded or encoded values themselves.

**Every call:**
- Read: a `ByteArrayDecoder` (`C/Avro.kt:165`) and a root `AvroValueDirectDecoder` (`C/internal/AvroCodecEntryPoints.kt:81`).
- Write: a `KotlinxIoEncoder` (`C/AvroKotlinxIoExtensions.kt:36`) and a root `AvroValueDirectEncoder` (`C/internal/AvroCodecEntryPoints.kt:72`).

### 2.1 Decode, per value

| Allocated | Where | Size (est.) |
|---|---|---:|
| `RecordDirectDecoder` per record | `D/AbstractAvroDirectDecoder.kt:128` | 48 B |
| `ArrayBlockDirectDecoder`/`MapBlockDirectDecoder` per block, the closing `0` block included | `:102`, `:113` | 48 B |
| kotlinx copies in the closing-block merge: `Array<String>` (ArrayList + `Object[]` + new array), `LongArray` (builder + grown buffer + built array) | kx-ser `CollectionSerializers.kt:27,40,196,198`; `PrimitiveArraysSerializers.kt:175` | ~3 objects |
| `PolymorphicDecoder` + `Pair` + `AvroValueDirectDecoder` (+ the subtype's record decoder) per polymorphic value | `:132`; `AbstractPolymorphicDecoder.kt:62`; `:349-351` | 32 + 24 + 32 (+48) B |
| `RecordAsMapDirectDecoder` per record read as a map | `:117-121` | rare |
| `JsonDefaultDecoder` per default-value read | `D/RecordDirectDecoder.kt:68` | not benchmarked |

Check: `simple` read @1 → @25 adds 259 B per record (6,824 − 616 over 24, EA off, `README:866`). By layout:
- `SimpleDataClass` 48 + `Integer` 16 + String 72 + `ByteArray(50)` 72 + list slot 4 = 212 B;
- plus `RecordDirectDecoder` 48 B, i.e. 18.5% of what a record allocates;
- total 260 B, against 259 measured.

| Workload | Objects per call today | After M5-01 + 02 + 04 |
|---|---|---|
| `simple` (`SimpleDatasClass` → `List<SimpleDataClass>`) | 5 + 1 per record: @1 6, @25 30, @500 505 | 5 at any size |
| `complex` (`Clients` → `List<Client>`, `BM/internal/Clients.kt`) | 5 + ≈17.75 per client (see below): @1 ≈23, @15 ≈271, @200 ≈3,555 | ≈10 at any size |
| `lists` (1 wrapper × E `StatsEntry` × 100 longs) | 8 + 3 per entry: @100 308, @10,000 30,008 | 8 |

The ≈17.75 objects per `complex` client:
- 1 record decoder;
- 8 block decoders, for `emails`, `phones`, `tags` and `map`, two each;
- 6 kotlinx copies;
- 2.75 for `partner`: GoodPartner/BadPartner (1/2) cost 4 objects, Stranger (1/4) 3, null (1/4) 0.

Estimated EA-off read savings:
- `simple`: −48 B per record and −48 B per call.
- `complex`: ≈ −840 B per client: ~510 from M5-01, ~240 from M5-02, ~90 from M5-04.
- `lists`: −144 B per entry, about −5%, because 100 boxed `Long`s per entry dominate.

### 2.2 Encode, per value

| Allocated | Where | Size (est.) |
|---|---|---:|
| Record encoder per record | `E/direct/AbstractAvroDirectEncoder.kt:29-31` → `E/direct/RecordDirectEncoder.kt:22-25` | 32 B |
| `ArrayDirectEncoder`/`MapDirectEncoder` per collection | `:37-49` | 32 B |
| `PolymorphicDirectEncoder` + `AvroValueDirectEncoder` per polymorphic value | `:33-35`, `:131` | 24 + 32 B |
| UTF-8 array per non-empty string | `K/KotlinxIoEncoder.kt:64` | 16 + length B |
| Reordered record: `ReorderingCompositeEncoder` + `Array` + `RecordNonContiguousEncoder` + lambda, then per field a `BufferedCall` + lambda, per inline element a `BufferingInlineEncoder` | `RecordDirectEncoder.kt:27-38`; `E/ReorderingCompositeEncoder.kt:34,44-57,182-197` | 4 + 2n objects |

Check, which matches to the byte: `simple` write EA off is 224 B @1 and 2,144 B @25 (`README:1229-1230`). That is 144 B fixed + 80 B per record, the 80 B being a 32 B record encoder and a 48 B UTF-8 array for the 25-character string.

| Workload | Per value today | After M5-05 + 07 | Predicted EA-off B/op |
|---|---|---|---|
| `simple` | 1 record encoder + 1 UTF-8 array per record | 0 | @25: 2,144 → ≈176 |
| `complex` | per client: 1 record + 4 collection encoders + ~2 polymorphic + ~37 UTF-8 arrays | 0 | ≈ −1.4 KB per client (≈ −80%; the string part is an estimate) |
| `lists` | per entry: 1 record + 1 array encoder | 0 | @100: 9,841 → ≈3,400 |

## 3. C1: is a frame stack still the right design?

**No. Per-level reuse is.** The codec being avro4k's own changes nothing here. What matters is where the per-structure state lives:
- a record decoder's position in its workflow (`D/RecordDirectDecoder.kt:25-29`);
- a block decoder's item count and index (`D/CollectionsDirectDecoder.kt:25-36`);
- a record encoder's writer schema (`E/direct/RecordDirectEncoder.kt:94-108`).

This state must survive every nested child. Avro, unlike JSON, cannot read it back from the input.

### The options compared

| | Single instance + frame stack (plan) | **Per-level reuse (recommended)** |
|---|---|---|
| Per-value allocation | 0 | 0 |
| Per-call allocation | 1 decoder + k stack arrays (more than today on @1 rows) | about 5–10 small objects (O(depth × kinds)) |
| Hot methods | every `decodeX`/`beginElement`/`decodeElementIndex` branches on the frame kind; state read through array loads | unchanged small specialised classes |
| JIT | one receiver class (monomorphic sites in generated serializers), but bigger methods: the M4 varint split showed what crossing `DesiredMethodLimit` costs | call shapes as today |
| Risk | rewrite of 6 classes and the public-internal `AbstractPolymorphicDecoder`/`AbstractAvroEncoder` contracts | reset bugs, contained by a busy guard and tests |

### Design, common to decoders and encoders

1. **Child slots.** Each `AbstractAvroDirectDecoder`/`AbstractAvroDirectEncoder` has lazily created child slots, one per reusable kind:
   - decoders: exact record (M5-03), general record, array block, map block, polymorphic (M5-04);
   - encoders: exact record, skipping record, array, map, polymorphic, reordering (M5-10).

   `beginStructure`/`beginCollection` takes the slot's instance and calls `reset(...)` on it. A level only ever has one active child: kotlinx serializes depth-first.
2. **Busy guard.** `reset` sets `inUse = true` and `endStructure` clears it. A slot found busy gets a **fresh** instance, not stored in the slot. That happens when a serializer begins a second structure on the same parent before ending the first, never calls `endStructure`, or catches an exception and continues. Correctness never depends on the protocol being followed; only speed does. Today `endStructure` is a no-op on decoders (`D/AbstractInterceptingDecoder.kt:20-21`), so a serializer that forgets it must keep working.
3. **Reset is complete.** Every constructor-time field becomes a `var` set by `reset`: indexes to 0, `lateinit` schemas reassigned. Encoders also clear `selectedUnionIndex` (`E/AbstractAvroEncoder.kt:45`).
4. **Workflow memo per slot.** `if (schema === lastSchema && descriptor === lastDescriptor) reuse workflow else resolveFields(...)`. A homogeneous list never reaches `RecordResolver`. This is a per-call memo, allowed by the shared-state rule, and it replaces the deferred C2 variant.
5. **What stays allocated per call:**
   - the root decoder/encoder and the codec;
   - `RecordAsMapDirectDecoder` (rare);
   - `JsonDefaultDecoder` for defaults (not benchmarked);
   - the fresh instance of a busy slot.

### When to revisit the frame stack

Only if, after M5-02/03/05, `-XX:+PrintInlining` on `complex` read @1 shows megamorphic `beginStructure`/`decodeSerializableValue` sites in generated serializers costing measurably. Single-class dispatch is the only thing a frame stack would add. Recorded as Q5.

### C1 split into reviewable, measurable steps

| Step | Unit | Measured by |
|---|---|---|
| a | M5-01 one merge per single-block collection | AB-R (B/op exact) |
| b | M5-02 decoder reuse | AB-R |
| c | M5-03 exact records in order | AB-R |
| d | M5-04 polymorphic decode (C6, depends on b) | AB-R |
| e | M5-05 encoder reuse | AB-W |

## 4. Units

### M5-00 · Multiplatform common benchmarks

Only if Q2 = yes.

**Goal.** Locked decision 4, and the only way to measure off the JVM what M5 mostly claims there.

**Files:**
- new module `benchmark-common/`: `build.gradle.kts`, Kotlin multiplatform with `jvm`, `js(IR) { nodejs() }` and `macosArm64`, plugins `org.jetbrains.kotlinx.benchmark` 0.5.0, `allopen` for `@State` and `kotlin("plugin.serialization")`;
- `settings.gradle.kts`.

Not published, and not in `bom` or `actionsBeforeCommit`.

**Design:**
- `commonMain` holds copies of `SimpleDataClass`/`SimpleDatasClass` and `ListWrapper*`, which are all common types.
- A common `Client` replaces `BigDecimal` with `String` (stringable, as today), `LocalDate` with an `Int` day and `java.time.Instant` with `kotlin.time.Instant` (`KotlinInstantSerializer`). The `Partner` hierarchy is kept.
- Data comes from a seeded `kotlin.random.Random` in `@Setup`.
- `read` from a `ByteArray`; `write` to a `kotlinx.io.Buffer` cleared after each operation.
- Rows: `simple` @1/@25, `complex` @1/@15, `lists` @100, read and write.

**Acceptance.** `ACC` + `./gradlew :benchmark-common:compileKotlinJvm :benchmark-common:compileKotlinJs :benchmark-common:compileKotlinMacosArm64`. The orchestrator does a 1-iteration smoke run.

**Judged by:** nothing (it is an instrument). Committed first, so it is the base commit of every A/B.

**Risk:** native release link time; JS timer resolution.

**Parallel with:** M5-01.

### M5-01 · One merge per single-block collection (new)

**Design.** `decodeCollectionBlocks` (`D/AbstractAvroDirectDecoder.kt:86-93`) reads the next block count itself, between merges:

```kotlin
var result = deserializer.merge(this, null)          // beginStructure reads readArrayStart()/readMapStart()
while (decodedCollectionSize > 0) {                  // -1 for a record read as a map, 0 for an empty first block
    val next = checkBlockSize(if (collectionIsMap) binaryDecoder.mapNext() else binaryDecoder.arrayNext(), firstBlock = false)
    if (next == 0) break
    pendingBlockSize = next                          // beginStructure uses it instead of reading a count
    result = deserializer.merge(this, result)
}
```

- `collectionIsMap` and `pendingBlockSize` are new private fields, set in `beginStructure` (`:97-114`).
- The codec call sequence is unchanged (`readArrayStart`, items, `arrayNext`…), so `ValidatingAvroBinaryDecoder` and the hardening bounds (`checkBlockSize`, `:144-156`) see exactly what they see today.

**Files:** `D/AbstractAvroDirectDecoder.kt`. Tests in `commonTest`:
- arrays, maps, `LongArray`, `Array<String>` and nested collections written in 2–3 blocks from hand-built bytes, including negative counts with byte sizes;
- an empty array and map;
- `validateSerialization` on;
- the `jvmTest` recording decoder (`ValidatingCodecDifferentialTest.kt:679`) asserting an unchanged call sequence.

**Acceptance:** `ACC`.

**Judged by:**
- AB-R: `complex`, `lists` and `simple` read; `CollectionMicroBenchmark.readLongs/readRecords/readTwoCollections`;
- EA-off.

Predicted: −48 B per non-empty collection, plus the copies for `Array`/`LongArray` (≈ −510 B per `complex` client).

**Risk:** low.

**Parallel with:** M5-00, M5-07.

### M5-02 · Decoders reused per nesting level (C1, decode)

**Design.** §3 applied to records, array blocks and map blocks.
- `RecordDirectDecoder` gets `reset(writerSchema, descriptor)`, with the slot memo and `nextDecodingStepIndex = 0`. Its workflow is no longer resolved in the constructor (`D/RecordDirectDecoder.kt:25`).
- `ArrayBlockDirectDecoder.reset(arraySchema, blockSize)` resets `nextItemIndex` and caches `elementType` once. `beginElement` still reassigns `currentAvroSchema` per item, because resolving a union mutates it (`D/CollectionsDirectDecoder.kt:38-44`).
- `MapBlockDirectDecoder.reset(mapSchema, blockSize)` resets `decodedEntries` and `pendingValueIndex`.
- The polymorphic path is untouched; that is M5-04.

**Files:** `D/AbstractAvroDirectDecoder.kt`, `D/RecordDirectDecoder.kt`, `D/CollectionsDirectDecoder.kt`. `commonTest`s:
- siblings of every kind at several depths;
- a recursive type 6 deep;
- two record types alternating as fields of one parent;
- a hand-written serializer beginning two structures on one decoder (busy fallback);
- one that never calls `endStructure`;
- one that catches a decoding error inside a structure and continues;
- mutation checks of the memo guard (each `===` half) and of the busy guard.

**Acceptance:** `ACC`.

**Judged by:**
- AB-R: every avro4k read row, `NestedRecord.read` depth 1/3/8, `Collection.readRecords`;
- EA-off.

Predicted: −48 B per `simple` record exactly; −240 B per `complex` client; −96 B per `lists` entry. Throughput is likely within noise on the JVM (A8).

**Risk:** medium (stale state). It is contained by the guard and the tests, and the classes stay the same.

**Parallel with:** M5-07 or M5-05.

### M5-03 · Records that match the writer schema exactly, decoded in order (new)

**Design.**
- `computeDecodingSteps` (`C/internal/RecordResolver.kt:180-233`) also computes `exactFieldSchemas: Array<AvroSchema>?`. It is non-null only when there are as many steps as elements and as writer fields, and step i is `DeserializeWriterField(elementIndex = i, writerFieldIndex = i)`. It is stored as an `internal` field of `SerializationWorkflow` (`:357-376`), invisible to `apache-interop` and to the dump.
- A new `ExactRecordDirectDecoder` in `D/RecordDirectDecoder.kt`:
  - answers `decodeSequentially() = true`;
  - in `beginElement`, does `currentAvroSchema = exactFieldSchemas[index]`;
  - has no default-value overrides;
  - still answers `decodeElementIndex` (0..n−1, then `DECODE_DONE`), because callers may ignore `decodeSequentially`. Confluent's `GenericRecord` deserializer does (`CF/Serializers.kt:235-244`).
- `beginStructure` picks the exact or the general slot from the workflow.

**Why:**
- The generated deserializer already has the sequential branch (`javap` of `SimpleDataClass$$serializer.deserialize`: `decodeSequentially` at offset 53, the field reads up to 240, the loop from 268), but nothing ever answers `true` (`AbstractInterceptingDecoder` keeps `CompositeDecoder`'s default).
- Encode already has the twin, `RecordContiguousExactEncoder` (`E/direct/RecordDirectEncoder.kt:94-108`).

**Files:** `C/internal/RecordResolver.kt`, `D/RecordDirectDecoder.kt`, `D/AbstractAvroDirectDecoder.kt`. Tests:
- a hand-written `KSerializer` that probes `decodeSequentially()` for exact versus reordered, extra-writer-field, missing-with-default, missing-optional and alias-matched schemas;
- a hand-written serializer using `decodeElementIndex` on an exact record;
- Confluent's suite.

**Acceptance:** `ACC`.

**Judged by:**
- AB-R: `simple` and `complex` read (every record of both is exact);
- `FieldOrder.read` matching, against swapped/reversed (the control: general path);
- `NestedRecord.read`, `StringField.read*`;
- `complex` read @1 at 9 forks.

Predicted: B/op unchanged; throughput up on record-heavy reads (a guess, not measured).

**Risk:** medium. The JIT compiles a different branch of every generated deserializer. Semantic risk is low, thanks to the tests.

**Parallel with:** M5-05.

### M5-04 · Polymorphic values decoded without per-value allocation (C6)

**Design.**
1. `AbstractPolymorphicDecoder` (public `@InternalAvro4kApi`):
   - `tryFindSerialName`/`tryFindSerialNameForUnion` return `String?` and record the branch in a `protected var chosenSchema`, so there is no `Pair`.
   - `descriptor`/`schema` become `private var` with an `internal reset(descriptor, schema)`.
   - The overrides in `C/internal/decoder/JsonDefaultDecoder.kt:453-468` and `AI/internal/decoder/generic/PolymorphicGenericDecoder.kt:15-25` are adapted. In `firstNotNullOfOrNull` the first match still wins, because `chosenSchema` is only set on a match.
2. The direct `PolymorphicDecoder` (`D/AbstractAvroDirectDecoder.kt:333-352`) becomes a slot with the busy guard. It keeps one reusable `AvroValueDirectDecoder` (`reset(schema)`), whose own slots then reuse the subtype's record decoder.
3. A small memo on the direct `PolymorphicDecoder`: up to 4 entries of (descriptor `===`, branch schema `===`) → serial name. It skips the `PolymorphicResolver` lookup and the name maps on a repeat.

**Files:**
- `C/internal/decoder/AbstractPolymorphicDecoder.kt`
- `D/AbstractAvroDirectDecoder.kt`
- `D/AvroValueDirectDecoder.kt`
- `C/internal/decoder/JsonDefaultDecoder.kt`
- `AI/internal/decoder/generic/PolymorphicGenericDecoder.kt`

Tests:
- records, objects and enums alternating in one list;
- a nullable polymorphic field;
- a non-nullable polymorphic field on a union writer schema (the `tryFindSerialNameForUnion` path);
- the unknown-name error message unchanged;
- `apache-interop`'s generic polymorphic tests;
- polymorphic defaults.

**Acceptance:** `ACC`. `apiCheck` must show no diff in either module, klib included.

**Judged by:**
- AB-R: `Polymorphic.readPolymorphic` @10/@1000 (EA-off at @1000: at @10 escape analysis hides it, M3-06) against `readConcrete` (control); `complex` read.

Predicted: −136 B per record-subtype value (−88 B for an enum subtype).

**Risk:** low–medium (crosses into `apache-interop`).

**Parallel with:** M5-06.

### M5-05 · Encoders reused per nesting level (C1, encode)

**Design.** §3 applied to `AbstractAvroDirectEncoder` (internal, sealed):
- slots for exact record, skipping record, array, map, and polymorphic plus its value encoder;
- `RecordDirectEncoder(...)` (`E/direct/RecordDirectEncoder.kt:14-42`) becomes a method choosing the slot from the workflow (memoized per slot);
- `ArrayDirectEncoder`/`MapDirectEncoder.reset(schema, size)` writes `writeArrayStart`/`writeMapStart`, which today is in `init` (`E/direct/CollectionsEncoder.kt:12-14,48-50`);
- `PolymorphicDirectEncoder` overrides `endStructure` to clear its busy flag;
- `AbstractAvroEncoder` gets one `internal` member clearing `selectedUnionIndex` (`E/AbstractAvroEncoder.kt:45`). It is not visible to `apache-interop` and not in the dump;
- `NonContiguous` keeps allocating until M5-10.

**Files:** `E/direct/AbstractAvroDirectEncoder.kt`, `E/direct/RecordDirectEncoder.kt`, `E/direct/CollectionsEncoder.kt`, `E/AbstractAvroEncoder.kt` (that member only). Tests:
- M5-02's set, for encoding;
- a value selecting union index 1, then a sibling selecting 0, on reused encoders (no "Already selected union index");
- bytes guarded by the oracle and the golden fixtures.

**Acceptance:** `ACC`.

**Judged by:**
- AB-W: every avro4k write row; `simple` write @1/@25 at 9 forks; `NestedRecord.write`; `Collection.writeRecords/writeLongs`; `Polymorphic.write*`;
- EA-off.

Predicted: `simple` −32 B per record; `lists` −64 B per entry; `complex` ≈ −220 B per client.

**Risk:** medium (reset of state; JIT compile order of the `simple` write rows).

**Parallel with:** M5-02 or M5-03.

### M5-06 · Union branch tables and small per-value encode methods (C4)

**Design.**
1. `UnionSchema` (`C/AvroSchema.kt:209-220`) gets an `internal val firstIndexByType: IntArray`, indexed by `Type.ordinal` with −1 when absent and built in `init`. `getIndexTyped` (`C/internal/schema/AvroSchemaHelpers.kt:187-193`) reads it: same answer, the first branch of that type. A non-constructor property is not part of `equals`/`hashCode`/`copy`.
2. Every `encodeX` of `AbstractAvroEncoder` keeps only the natural case inline:

   ```kotlin
   if (currentAvroSchema.isUnion) selectBranchForString(value)
   if (currentAvroSchema.type === STRING) encodeStringUnchecked(value) else encodeStringConverted(value)
   ```

   The selection chain, the conversions (`convertForEncoding` with its inline `try`) and the `unsupportedWriterTypeError` vararg arrays move into private out-of-line functions, with identical messages.
3. The fixed-size and enum-symbol scans stay. They are reached only when the union has no string/bytes branch.

**Files:** `C/AvroSchema.kt`, `C/internal/schema/AvroSchemaHelpers.kt`, `C/AvroEncoder.kt`, `E/AbstractAvroEncoder.kt`. `apache-interop`'s generic encoder inherits the base: its tests must pass unchanged. The decode-side twin (`decodeString`, 247 B) is out of scope: its file is busy in this batch, and it is only to be done if M5-11's `PrintInlining` shows a miss. Tests:
- common: every primitive into every compatible union shape, first-branch-of-type semantics, two or three error messages pinned;
- `javap` sizes in the note. Target: each `encodeX` well under 325 B.

**Acceptance:** `ACC`.

**Judged by:**
- AB-W: `StringField.writeNullable` against `writeNonNull` (control); `simple` write @1/@25 at 9 forks; `complex` write; `Polymorphic.write*`.

Predicted: no change in B/op.

**Risk:** low–medium (JIT shapes).

**Parallel with:** M5-04.

### M5-07 · Strings written without an intermediate array (C5)

**Design.**
- A common `internal fun String.utf8Length(): Int`: the counting loop now inside `Platform.nonJvm.kt:32-55`, with an unpaired surrogate counted as 1 byte (`?`).
- `KotlinxIoEncoder.writeString` becomes: an empty string writes `0`; otherwise `writeInt(utf8Length())` then `sink.writeString(value)` (kx-io `Utf8.kt:170-174`, `?` for unpaired surrogates as avro4k requires).
- The non-JVM `encodeToUtf8` reuses the helper.
- If AB-W shows a JVM loss on long ASCII strings (the JDK's `getBytes` is intrinsic for Latin-1 compact strings), a JVM actual keeps the array path above a length threshold.
- A variant for a second A/B side, optional: up to 21 characters, the length prefix is one byte whatever the content. Reserve it, encode, then backfill it, in one pass.

**Files:**
- `K/KotlinxIoEncoder.kt`
- `C/internal/Platform.kt`
- `core/src/nonJvmMain/.../internal/Platform.nonJvm.kt`
- `core/src/jvmMain/.../internal/Platform.jvm.kt` (if needed)

`commonTest`: bytes identical to `encodeToUtf8()` for ASCII, Latin-1, CJK, 4-byte characters, unpaired high/low surrogates at start/middle/end, lengths at the varint boundaries (63/64, 8191/8192 bytes), and a string longer than one segment.

**Acceptance:** `ACC+KMP`.

**Judged by:**
- AB-W: `StringField.write*`; `simple` and `complex` write;
- EA-off.

Predicted: −48 B per `simple` record; ≈ −1.2 KB per `complex` client (estimate).

**Risk:** low on bytes (pinned); a possible JVM CPU cost on long strings.

**Parallel with:** anything.

### M5-08 · String read (C5)

Only if Q1 = threshold. If Q1 = keep, there is no code: the orchestrator adds a known-limit line and closes the ledger row.

**Design.** `ByteArrayDecoder.readString` (`K/ByteArrayDecoder.kt:94-98`) and `KotlinxIoDecoder.readString` (`K/KotlinxIoDecoder.kt:126-134`) decode below N bytes with a common Kotlin UTF-8 decoder of avro4k's own (kotlinx-io's `commonToUtf8String` is internal), and above N with `decodeToString`. Candidate sides: N = 0 (today), 16, 32.

**Files:** those two, plus a new common decoder file, plus tests (malformed sequences decoded as the JVM does, split across segments).

**Acceptance:** `ACC+KMP`.

**Judged by:** AB-S: `StringDecodeMicroBenchmark` (6 rows), `StringField.read*`, `simple`/`complex` read.

**Parallel with:** M5-10. Conflicts with M5-09 (codec files).

### M5-09 · `fixed`/`bytes` logical types without the intermediate array (C5 part A)

Only if Q3 says so. Two commits.

**1. Benchmark rows alone first** (precedent `8920310f`): `BM/micro/LogicalTypesMicroBenchmark.kt`, read and write of `uuid` fixed(16), `duration` fixed(12), JVM `decimal` bytes/fixed and `big-decimal`.

**2. Design.**
- `AvroBinaryDecoder` gets a non-abstract `open fun <R> readFixed(size: Int, reader: FixedReader<R>): R`, defaulting to `reader.read(readFixed(size), 0)`.
- `FixedReader` is an internal fun interface implemented by stateless objects, so nothing is allocated per call. For decimal, the reader returns the `BigInteger(bytes, off, len)` and the serializer applies the scale.
- `ByteArrayDecoder` and `KotlinxIoDecoder` (head segment) override it to pass their own array and offset.
- `ValidatingAvroBinaryDecoder` overrides it to validate, then delegate.
- `ByteBufferDecoder` and the Apache adapter keep the default.
- The serializers take the fast path only when the decoder is avro4k's binary direct decoder and the writer schema is the expected fixed/bytes; otherwise they call `decodeFixed()`/`decodeBytes()` (defaults, generic trees, third-party decoders).

**Files:**
- `K/{AvroBinaryDecoder,ByteArrayDecoder,KotlinxIoDecoder,ValidatingAvroBinaryDecoder}.kt`
- `D/AbstractAvroDirectDecoder.kt` (an internal accessor)
- `C/serializer/{KotlinStdLibrarySerializers,AvroDuration}.kt`
- `J/serializer/JavaStdLibSerializers.kt`

**Acceptance:** `ACC+KMP`.

**Judged by:** AB-L only. No end-to-end row decodes these types: `complex`'s `BigDecimal` is stringable.

**Risk:** medium, for a small expected gain (8–16 B arrays beside the `BigInteger`/`BigDecimal` built anyway).

**When:** after the decoder chain. Part B (public scoped API) is Q3.

### M5-10 · Reordering encoder without closures (C8)

**Design.**
- Values are buffered by writer position in arrays owned by the encoder:
  - `kinds: ByteArray`;
  - `primitives: LongArray` (boolean/byte/short/int/long/char widened; float/double as raw bits);
  - `refs: Array<Any?>` (strings; serializer and value in two slots);
  - `elementIndexes: IntArray` (the original element index the replay needs).
- The mapping is read from the workflow's `descriptorToWriterFieldIndex` (`C/internal/RecordResolver.kt:392-394`), so no lambda.
- One reusable inline-element recorder per encoder replaces the per-element `BufferingInlineEncoder`; it records the not-null mark.
- `endStructure` replays in writer order through `when (kind)`.
- With M5-05's slot, the encoder and its arrays (grown to the largest field count seen) are reused per level.
- Semantics unchanged: everything is still encoded at `endStructure`, a skipped element (`SKIP_ELEMENT_INDEX`) is dropped, and the same index encoded twice keeps the last value.

**Files:** `E/ReorderingCompositeEncoder.kt`, `E/direct/RecordDirectEncoder.kt`, `E/direct/AbstractAvroDirectEncoder.kt` (slot). Tests: every element kind, inline value classes nullable and not, nested records, nullable serializable elements, skipped elements, sibling reordered records of different types.

**Acceptance:** `ACC`.

**Judged by:**
- AB-W, its own side: `FieldOrder.write` swapped/reversed against matching (control);
- EA-off.

Predicted: per reordered record, 4 + 2n objects → 0.

**Risk:** low–medium.

**Parallel with:** M5-08.

### M5-11 · Re-baseline, review, known limits

Run by the orchestrator.
- Benchmarks: the M3-17 protocol, from the M5 base to the M5 end; see §6.
- Review: a `feature-dev:code-reviewer` pass over the whole M5 diff.
- README: "Known limits" in `benchmark/README.md`, from the plan's list plus §0 item 14 and Q1's outcome.
- Ledger rows.
- If the review finds `PrintInlining` misses on decode methods, the decode-side split of M5-06 becomes a follow-up.

## 5. Order, batches, parallelism (max 2 agents, api units alone — none expected)

| Step | Units | Disjoint because | Then |
|---|---|---|---|
| 0 | user answers Q1–Q8 | | |
| Batch 1 | M5-00 ∥ M5-01 | new module vs `AbstractAvroDirectDecoder.kt` | merge M5-00 first (A/B base) |
| Batch 2 | M5-07 ∥ M5-02 | codec encoder + `Platform*` vs decoder files | |
| Batch 3 | M5-05 ∥ M5-03 | encoder files vs `RecordResolver`/decoder files | **AC-1** |
| Batch 4 | M5-06 ∥ M5-04 | `AvroSchema`/`AvroEncoder`/`AbstractAvroEncoder` vs polymorphic decoders + `apache-interop` | **AB-R** |
| Batch 5 | M5-10 (∥ M5-08 if Q1 = threshold) | reordering + direct encoders vs codec decoders | **AB-W** (+ AB-S) |
| 6 | M5-09 if Q3 | benchmark-rows commit alone, then the unit | AB-L |
| 7 | M5-11 | | |

Notes on the chains:
- The decoder chain 01→02→03→04 and the encoder chain 05→06→10 are each serial (same files). The two chains are mutually disjoint.
- The read A/B's sides are commits within the branch. They also contain encoder units, which no read row executes; the write A/B is the mirror image. The benchmark `@Setup` encodes with avro4k, but the bytes are pinned by the golden tests.

## 6. Measurement

**Protocol, as M4.**
- The orchestrator runs everything, under one `caffeinate -dimsu`, checking AC power and Low Power Mode before every invocation, and starting only when the 1-minute load average is below 3.
- Rounds alternate the order of the sides. "sep" = every invocation of one side above (or below) every invocation of the other.
- Escape analysis is on for throughput. For allocation: `benchmarkAlloc -PallocJmhArgs='-jvmArgsAppend -XX:-DoEscapeAnalysis'`, once per side.
- Cost basis: ≈26 s per end-to-end row-run (16 rows × 5 rounds × 2 sides took ~70 min) and ≈13 s per micro row-run (5 × 1 s + 5 × 1 s + fork).

| Checkpoint | When | Sides | Rows | Est. time |
|---|---|---|---|---|
| **AC-1**, allocation only | after batch 3 | M5 base, head | EA-off, 1 fork: the 16 avro4k read and write rows + `NestedRecord`, `Collection`, `Polymorphic`, `StringField` (~15 rows). Deterministic: checks §2's predictions before batch 4 builds on them | ~20 min |
| **AB-R**, reads | after batch 4 | base, +M5-02 (incl. 01), +M5-03, +M5-04 | 8 `read` rows × 5 rounds; `complex` read @1 at 9 forks per side; 21 read micros (`NestedRecord.read` ×3, `Collection.readLongs/readRecords/readTwoCollections` ×9, `Polymorphic.read*` ×4, `FieldOrder.read` ×3, `StringField.read*` ×2) × 3 rounds × 1 fork; EA-off once per side; `readFromSource` not needed (same code above the codec) | ~2.8 h (+~40 min with M5-00's js/native rows) |
| **AB-W**, writes | after batch 5 | base, +M5-07, +M5-05, +M5-06; plus +M5-10 on the `FieldOrder.write` rows only | 8 `write` rows × 5 rounds; `simple` write @1/@25 at 9 forks per side; 18 write micros × 3 rounds; EA-off once per side | ~3 h (+~40 min) |
| AB-S | if Q1 = threshold | N = 0 / 16 / 32 | `StringDecode` ×6 + `StringField.read*` ×2, 3 rounds × 2 forks; `simple`/`complex` read × 5 rounds | ~1 h |
| AB-L | if M5-09 | before/after | the new logical-type micros 3 rounds × 2 forks, EA-off | ~20 min |
| **Final** (M5-11) | end | M5 base, M5 end | M3-17 protocol: six per-workload tasks × 3 invocations per side (Apache/Jackson as the noise reference: same Apache version on both sides); the 8 `readFromSource` rows; EA-off; the two JIT-sensitive rows at 9 forks; micro suite once per side; common rows | ~4.5 h |

**Not measured, and why:**
- M5-00: an instrument.
- M5-08 when Q1 = keep: no code.
- `JsonDefaultDecoder` and `RecordAsMap` paths: no benchmark covers them, and each note says so.

## 7. Open questions for the user (each with a recommendation)

1. **String decoding** (open since M4, `notes/m4-strings-and-bytebuffer.md`).

   *Recommendation:* **keep the JDK decoder and close the question.**
   - With the plain cursor, 16-character ASCII (4.41M vs 3.88M ops/s) and CJK (2.47M vs 2.39M) are above the pre-varint base. Only Latin-1 16 is below (1.80M vs 3.16M, measured on different days).
   - Long strings gain 31–159%, and every end-to-end read row is faster.
   - Document the Latin-1 short-string cost as a known limit. Document the malformed-surrogate divergence (1 replacement character on the JVM, 3 on JS/native; invalid UTF-8 only) in the migration guide.
   - M5-08 is then not scheduled.
2. **Multiplatform common benchmarks.** Locked decision 4 requires them, none exist, and M5's allocation claims are mostly about JS/native.

   *Recommendation:* **yes, a minimal M5-00** (jvm + jsNode + macosArm64; `simple`, `lists` and a common `complex`), run in AB-R, AB-W and the final re-baseline, recorded but not gated.
3. **C5 `fixed`/`bytes`.**

   *Recommendation:*
   - **Part B**, the public scoped `decodeFixed { source, size -> … }`: **v3.1**. It is new public API; as an interface member it breaks third-party `AvroDecoder` implementations, as `val avro` did; and no user has asked beyond the note.
   - **Part A** (M5-09): **v3.1 too**, unless you want it in v3.0. No benchmark covers it, the arrays are 8–16 B beside the `BigInteger`/`BigDecimal` built anyway, and it adds a method to the codec contract and to four decoders.
4. **Two units not in the plan.** M5-01 (one merge per single-block collection) and M5-03 (exact-match records decoded in order).

   *Recommendation:* **accept both.** They are small and measurable, and they target the read gap directly.
5. **C1 design.** Per-level reuse with a busy guard, instead of a single instance with a frame stack (§3).

   *Recommendation:* **accept.** Revisit only on the `PrintInlining` evidence §3 names.
6. **Reuse across calls**: pooling decoders/encoders per thread or per `Avro` instance, to remove the per-call fixed cost on @1 rows (≈150–250 B).

   *Recommendation:* **not in M5.** It conflicts with the shared-state rule, needs reentrancy handling, and threading differs on native and JS. Revisit only if M5-00 shows the fixed cost dominating small-record throughput off the JVM.
7. **Numbers for batched units.** The README rule says "same commit as the change".

   *Recommendation:* **the commit that merges the batch carries the A/B section** (the stream-cursor precedent), and each unit's note says "measured in AB-R/AB-W" with its predictions.
8. **C8 has no end-to-end row** (no workload reorders fields).

   *Recommendation:* **keep it in M5 as the last encoder unit.** It is small after M5-05 and removes 4 + 2n objects per reordered record. Drop it to v3.1 if M5 must be cut.

## Critical files for implementation

- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/decoder/direct/AbstractAvroDirectDecoder.kt`
- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/decoder/direct/RecordDirectDecoder.kt`
- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/encoder/direct/AbstractAvroDirectEncoder.kt`
- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/encoder/AbstractAvroEncoder.kt`
- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/RecordResolver.kt`
- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/codec/KotlinxIoEncoder.kt`
- `/Users/antoine.michaud/Documents/projets-perso/avro4k/core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/decoder/AbstractPolymorphicDecoder.kt`
