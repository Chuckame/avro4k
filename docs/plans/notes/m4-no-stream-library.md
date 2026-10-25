# M4 — No stream library in core: kotlinx-io removed (plan, not started)

**Status (2026-10-25):** IO-1, IO-2 and IO-4 done (chunks kept, `m4-bytearray-encoder.md`); §6 answered: Q1 = (b),
JVM `InputStream`/`OutputStream` in core (user, 2026-10-25), Q2 = an internal avro4k `EOFException`. The stream units
(IO-5 … IO-9), the new order and IO-3's amendments are in `m4-jvm-streams.md`.
Planned 2026-10-24. Decision: user, 2026-10-24 (`PROGRESS.md` decision
log). Open questions for the user are in §6; the plan below assumes the recommended answers and says what changes
otherwise.

## 1. Why

- **Speed is not the reason to keep a stream library.** Reading through a `Source` costs its per-read bookkeeping
  (`require`, a peek at the head segment, `skip` writing `Buffer.size` and `Segment.pos` on every value): `ByteArray`
  reads +7% to +32% and stream reads +12% to +48% once avro4k reads an array with a position instead
  (`benchmark/README.md`, "Results — a ByteArray read with a plain position" and "… a stream read from its head
  segment's array"). okio has the same segment design (kotlinx-io derives from it), so switching would change nothing.
- **kotlinx-io is a 0.x dependency in avro4k's public API** (`api(libs.kotlinx.io)` in `core/build.gradle.kts`,
  `Source`/`Sink` in signatures), and the fast paths need its `@UnsafeIoApi` and `@InternalIoApi` (`source.buffer`). A
  breaking kotlinx-io release forces an avro4k release.
- **Removing it is breaking, so it belongs in v3.0.** Adding a stream entry point back later (an adapter module, JVM
  streams) is not breaking and can come in a v3.x release.
- Precedent: kotlinx-serialization's ProtoBuf and CBOR formats are `ByteArray`-only, and its JSON core has no I/O
  dependency (`json-io` and `json-okio` are separate artifacts).

## 2. What uses kotlinx-io today (at `07a5201b`)

**Public API** (`core/api/core.api`, `core.klib.api`):
- `Avro.encodeToSink` ×3 and `Avro.decodeFromSource` ×3 (`AvroKotlinxIoExtensions.kt`);
- `AvroSingleObject.encodeToSink` and `decodeFromSource`.

`main` (v2) already has `encodeToSink`/`decodeFromSource` (4 references in its `core.api`): **v2 users of them are
affected**, on top of the stream/okio overloads v3 already removed (`Migrating-from-v2.md`, "Kotlin streams and
kotlinx-io").

**Internal, `commonMain`:**

| File | Use | Becomes |
|---|---|---|
| `Avro.kt:138` `encodeToByteArray` | `Buffer()` + `encodeToSink` + `readByteArray()` | the new `ByteArrayEncoder` |
| `AvroSingleObject.kt` | `encodeToSink`/`decodeFromSource`, `Buffer` in `encodeToByteArray`, `EOFException` | header + payload written by `ByteArrayEncoder`; stream functions removed |
| `internal/codec/KotlinxIoEncoder.kt` | the only encoder | replaced by `ByteArrayEncoder` |
| `internal/codec/KotlinxIoDecoder.kt` | stream decoder | deleted |
| `internal/codec/ByteArrayDecoder.kt` | `EOFException` | avro4k's own `EOFException` (§4.3) |
| `internal/AvroCodecEntryPoints.kt` | catches `EOFException` | same, avro4k's own |
| `internal/exceptions.kt` | `endOfInputError(cause: EOFException)` | same, avro4k's own |
| `internal/helpers.kt:116` | `Buffer(bytes)` (`moveToTail`) | deleted |
| `serializer/AvroDuration.kt:213` | `Buffer(bytes).readIntLe()` ×3 | a local `getIntLe(offset)`, the mirror of its `putIntLe` |
| `internal/codec/Utf8.kt`, `internal/SchemaFingerprint.kt` | KDoc only | KDoc updated |

**Internal, `jvmMain`:** `JavaStdLibSerializers.kt:425` (big-decimal bytes written through `KotlinxIoEncoder(Buffer())`;
`EOFException`), `ByteBufferDecoder.kt` and `AvroJVMExtensions.kt` (`EOFException`, KDoc).

**Tests** (count of kotlinx-io references): `KotlinxIoCodecTest` 28, `VarintTest` 27, `ReadBytesBufferBoundsTest` 23,
`AvroSerializationExceptionTest` 8, `ValidatingCodecDifferentialTest` 7, `RecordEncodingTest` 6,
`ValidatingAvroBinaryCodecTest` 6, `ByteArrayDecoderTest` 6, `KotlinxIoEncoderTest` 5, `AvroSchemaSkipTest` 5,
`AvroSchemaHelpersDifferentialTest` 4, `JvmAvroSerializationExceptionTest` 3, `CollectionBlockGuardsTest` 3,
`FreshWriterSchemaCacheTest` 2.

**Benchmark:** every avro4k end-to-end class writes with `encodeToSink` into
`OutputStream.nullOutputStream().asSink().buffered()` and has a `readFromSource` row; six micro classes use
kotlinx-io. **Not used** by `apache-interop`, `confluent-kafka-serializer` or `kotlin-generator`.

## 3. Rules

The rules of `m5-decomposition.md` §1 apply (no step codes in source, pre-existing bugs not fixed, units never run
benchmarks, `ACC`, test counts before/after, `javap -c -p` sizes of changed hot methods), **except "no new public
API / no API change": the removal of the stream API is approved by the user** (2026-10-24). No other public API
change; any other one stops the unit. The `apiDump` unit runs alone.

## 4. Units

### IO-1 · Write-to-array benchmark rows (benchmark only, committed alone)

So that both sides of the A/B measure the same thing: today avro4k's `write` streams into a null sink and has no
row for `encodeToByteArray`, which is the only write left after the removal.

- In `Avro4kSimpleBenchmark`, `Avro4kBenchmark` (complex) and `Avro4kListsBenchmark`: add `writeToByteArray()`
  returning `Avro.encodeToByteArray(schema, value)`.
- In the Apache reflect class of each workload: add `writeToByteArray()` (a `ByteArrayOutputStream` reset per call, a
  reused `binaryEncoder`, `flush()`, `toByteArray()`), so avro4k keeps a like-for-like reference.
- Nothing else changes. Acceptance: `./gradlew :benchmark:compileKotlin`; the orchestrator runs one smoke iteration.

### IO-2 · `ByteArrayEncoder` replaces the kotlinx-io encoder (internal, no API change)

**Design.** `internal class ByteArrayEncoder : AvroBinaryEncoder()` in `internal/codec`, the mirror of
`ByteArrayDecoder`: an array, a position, and `toByteArray()`.
- `writeInt`/`writeLong`: today's shape (`KotlinxIoEncoder.kt:28-46`), measured good for writes: the one-byte case
  inline (`room >= 1`), the rest out of line, which ensures `MAX_VARINT_*_BYTES` of room then calls
  `encodeZigZagInt`/`encodeZigZagLong` (`Varint.kt`, unchanged).
- `writeFloat`/`writeDouble`: little-endian into the array. `writeString`: unchanged (`encodeToUtf8()` then the bytes);
  writing without the intermediate array stays M5-07's job, re-scoped onto this encoder (§5).
- **Storage, two candidates in the same A/B** (pre-decided, the A/B picks):
  - **A, one growable array:** 256 B to start, doubled when full, `copyOf(position)` at the end.
  - **B, a list of chunks:** first chunk 256 B, each next one twice the previous up to 64 KiB, no copy when growing,
    one concatenation at the end. Only worth its extra code if A loses on `lists` @10000 (9.7 MB, about 16 regrowths).
- Callers: `Avro.encodeToByteArray`, `AvroSingleObject.encodeToByteArray` (writes the marker, version and fingerprint
  first, then the value, into one encoder), `JavaStdLibSerializers.toBigDecimalBytes`.
- `encodeToSink` keeps `KotlinxIoEncoder` until IO-3, so this unit has no API change and is measured on its own.

**Tests.** `KotlinxIoEncoderTest` and the encoder half of `VarintTest`/`KotlinxIoCodecTest` ported to the new
encoder: varint boundaries for int and long (every length 1–5 / 1–10, `Int.MIN_VALUE`, `Long.MIN_VALUE`), growth
across the initial capacity and across several doublings (or chunks), float/double bit patterns, empty
string/bytes/collections. Byte identity is already guarded by the Apache oracle and the golden fixtures.

**Predicted** (to check, not gated): JVM, small records: fewer objects (no `Buffer`, no pooled segment; one array
and its final copy); large payloads: the regrowth copies (candidate A) against kotlinx-io's segments. Native: kotlinx-io
does not pool segments there, so every `encodeToByteArray` allocated an 8 KiB segment; a clear gain expected, measured
once the multiplatform benchmarks exist (M5-00).

**Acceptance:** `ACC`.

### IO-3 · The stream API and the dependency removed (API change, runs alone)

- Delete `AvroKotlinxIoExtensions.kt`, `AvroSingleObject.encodeToSink`/`decodeFromSource`, `KotlinxIoEncoder`,
  `KotlinxIoDecoder`, `helpers.kt`'s `Buffer(bytes)`.
- `AvroDuration` reads its three ints by hand (`getIntLe`, beside `putIntLe`).
- **End of input** (§4.3 below): avro4k's own `EOFException`.
- `core/build.gradle.kts`: drop `api(libs.kotlinx.io)`; `gradle/libs.versions.toml`: drop the entry if nothing else
  uses it.
- **Tests:** tests of the stream path are deleted (segment splits, `release()`, consecutive values from one `Source`);
  every other test using a `Buffer` as plain input or output is ported to `ByteArray` (`ByteArrayDecoder` /
  `ByteArrayEncoder` / `encodeToByteArray`). The unit lists both sets, with the counts before/after.
- **Benchmark:** the avro4k `write` rows become `encodeToByteArray` (the IO-1 rows; `write` removed or kept as an alias,
  the unit says which), the `readFromSource` rows are removed, the six micro classes use `ByteArray`.
- `./gradlew apiDump`, alone; the diff must show only the eight removed functions.
- **Docs:** `README.md` ("JVM stream encoding", line 66, and "Encode many objects in the same stream", line 112:
  replaced by `ByteArray` usage and, on the JVM, `decodeFromByteBuffer` for several values back to back); `Migrating-from-v2.md` lines 32, 133, 139, 211–228, 274–275,
  382–383; an item in `b8-migration-items.md`: *what changed* (no stream API), *who* (users of `encodeToSink`/
  `decodeFromSource`, v2 included), *what to do* (`encodeToByteArray` then write the bytes; read the bytes then
  `decodeFromByteArray`; several datums back to back on the JVM: `decodeFromByteBuffer` leaves the buffer's position
  right after each value).
- KDoc mentioning kotlinx-io updated (`Utf8.kt`, `ByteArrayDecoder.kt`, `ByteBufferDecoder.kt`,
  `SchemaFingerprint.kt`, `AvroSingleObject`'s fingerprint cache).

**Acceptance:** `ACC+KMP` (the dependency leaves every platform's source set), then `grep -rn "kotlinx.io" core/src`
returns nothing.

### 4.3 End of input without kotlinx-io

`ByteArrayDecoder`, `ByteBufferDecoder` and the single-object header throw an `EOFException`, turned into
`AvroMalformedDataException` by `decodeWithValidation` (M3-22's decision 5). Replace `kotlinx.io.EOFException` by
`internal expect open class EOFException(message: String?)` in core: on the JVM `actual typealias` to
`java.io.EOFException`, which keeps the JVM contract unchanged and keeps apache-interop working (its Apache decoder
throws `java.io.EOFException`, caught by the same entry point); on JS and native a plain subclass of `Exception`.
Only the cause's class changes, on JS and native (a migration item).

### IO-4 · A/B and merge (orchestrator)

- Sides: base = IO-1; **A** = IO-2 candidate A; **B** = IO-2 candidate B. IO-3 removes code without changing a measured
  path and is not an A/B side; its own check is that the `read` and `writeToByteArray` rows did not move (one run).
- Rows: the three workloads' `writeToByteArray` (avro4k and the Apache reference) and `read` (control, must not move);
  `benchmarkAlloc` EA-off once per side for B/op. About 2 h; the usual conditions (AC, Low Power Mode off, load < 3,
  `caffeinate`).
- Numbers into `benchmark/README.md` in the commit that merges the kept candidate.

## 5. Effect on the rest of the plan

- **Order:** before M5's batch 1, since M5-00 and M5-07 are written against the encoder this replaces.
- **M5-00:** "`write` to a `kotlinx.io.Buffer` cleared after each operation" becomes `encodeToByteArray`.
- **M5-07** (strings written without an intermediate array) relied on kotlinx-io's `sink.writeString`. Re-scoped: the
  string is encoded straight into `ByteArrayEncoder`'s array (`utf8Length()` to reserve the prefix, then the UTF-8
  loop writing in place; on the JVM, the threshold question stays).
- **Superseded on 2026-10-25 (Q1 = b): IO-6 merges the branch** (`m4-jvm-streams.md`).
  **The branch `v3/one-cursor-decoder`** (`d4aae3bf`, a shared base for the `ByteArray` and stream decoders): with no
  stream input left, `ByteArrayDecoder` is the only array decoder, so the branch is **not merged**; it stays as the
  starting point if a stream input comes back, with the base class then named **`BufferedDecoder`** (user, 2026-10-24).
  Its pending A/B is dropped.
- **`backlog-after-v3.0.md`:** item 4 (strings decoded by kotlinx-io again) and item 5 (`readFromSource` benchmark
  setup) become obsolete; new item: a stream entry point again (adapter module or JVM streams), from that branch.

## 6. Open questions for the user

Both answered on 2026-10-25: Q1 = **(b)** ("it still needs a way to encode/decode with java streams, it'll be an
important need for JVM users"; plan in `m4-jvm-streams.md`), Q2 = the recommended internal class.

1. **What replaces reading from a stream?** An Avro datum carries no length, so reading several datums back to back
   needs a decoder that knows where each one ends. The README documents it ("Encode many objects in the same
   stream", a `Buffer` written then read value by value).
   - **(a) Nothing in v3.0** (recommended): `ByteArray` everywhere; on the JVM, `decodeFromByteBuffer` already reads
     datums back to back. Off the JVM, back-to-back reading is then not possible until a stream entry point comes
     back (writing is: concatenate the arrays). Adding one later is not breaking.
   - (b) `InputStream`/`OutputStream` overloads in core's JVM source set (no library). A buffered read ahead of the
     value, as kotlinx-io's, or one byte at a time, as Apache's `DirectBinaryDecoder`.
   - (c) An `avro4k-kotlinx-io` module with today's functions, same names and package: its users only add a
     dependency.

   With (b) or (c), `v3/one-cursor-decoder` is merged instead, its base class renamed `BufferedDecoder`, and the stream
   decoder lives in the JVM source set or the new module.
2. **End-of-input cause on JS and native:** an internal avro4k class (recommended, §4.3), so callers there can no
   longer test the cause's class; or none at all. On the JVM it stays `java.io.EOFException`.
