# M4 — JVM streams in core, and the benchmarks of the next steps

**Status:** planned 2026-10-25; IO-5 done (`8ff89859`), IO-6 done (merge `19f3e5d8`, rename `dc9cf462`); IO-7 done (`9122e71e`, bench rows `64052105`), findings in §6; IO-3 done (`0a19d72e`); IO-7b done (`0ad413d3`, branch `v3/jvm-streams-b`), findings in §7. **Decision (user, 2026-10-25):** "it still needs a way to encode/decode
with java streams, it'll be an important need for JVM users": option **(b)** of `m4-stream-options.md` (§6 Q1 of
`m4-no-stream-library.md`). `InputStream`/`OutputStream` functions in core's JVM source set, no stream library; kotlinx-io
still leaves core (IO-3). Option (c), an `avro4k-kotlinx-io` module, is not in v3.0 and stays possible later without
breaking anything (`backlog-after-v3.0.md` item 8).

**Decided with it (user, 2026-10-25):** the shared decoder base is merged (IO-6) and `decodeFromStream` reads the stream
as it decodes, with **kotlinx-serialization-json's contract: one value per stream**, leftover bytes an error, no
rewinding. The codec classes stay `@InternalAvro4kApi` in v3.0; making them public as experimental is decided after M5
(`backlog-after-v3.0.md` item 10).

How kotlinx-serialization-json does it (1.11.0 sources, `jvmMain/JvmStreams.kt`), the model followed here:
`Json.encodeToStream`/`decodeFromStream`/`decodeToSequence(InputStream)` in the JSON artifact itself, JVM only,
`@ExperimentalSerializationApi`; kotlinx-io and okio in separate artifacts (`kotlinx-serialization-json-io`, `-okio`).
Writing goes through a pooled 512-byte array written to the stream whenever full and once at the end, the stream never
flushed nor closed. Reading goes through a pooled 8 KiB buffer read ahead, never rewound, then `expectEof()`: "exactly
one object would be present in the stream" and dangling bytes throw. Several values: `decodeToSequence`.

The rules of `m4-no-stream-library.md` §3 apply. **The approved API change grows by the eight stream functions of
IO-7** (v2's names and shapes, `AvroSchema` instead of Apache's `Schema`); any other API change stops the unit.

## 1. What v2 did, and what this must not repeat

v2's `encodeToStream`/`decodeFromStream` (deprecated) were `encodeToSink(…, stream.asSink().buffered())` and
`decodeFromSource(…, stream.asSource().buffered())`. Reproduced on `main` on 2026-10-25 (`backlog-pre-existing-bugs.md`
#13 and #14):
- **`encodeToStream` writes nothing for a small value** (7-byte record: 0 bytes in the stream; same for
  `AvroSingleObject.encodeToStream`): the buffered sink is never flushed, so everything after the last complete 8 KiB
  segment stays in it.
- **A second `decodeFromStream` on the same stream fails** (`IllegalStateException: Unreachable`): each call reads
  ahead into its own buffered source and drops what it did not use.

v3's functions are new code. The first behaviour is an acceptance test of IO-7; the second is replaced by a contract:
one value per stream, and a stream holding more fails on the first call instead of silently losing the rest.

## 2. Units, in this order

Each commit compiles and passes `ACC`: the stream functions arrive (IO-7) before kotlinx-io leaves (IO-3), so the
stream benchmark rows switch from the kotlinx-io adapters to the new functions without a gap.

| Order | Unit | What | Runs alone | Benchmark |
|---|---|---|---|---|
| 1 | IO-5 | stream benchmark rows, through today's kotlinx-io API | — | smoke only |
| 2 | IO-6 | merge `v3/one-cursor-decoder`, base class renamed `BufferedDecoder` | — | none (measured by IO-8) |
| 3 | IO-7 | `encodeToStream`/`decodeFromStream` in `jvmMain`, one value per stream, candidate A | yes (`apiDump`) | none (IO-8) |
| 4 | IO-3 | kotlinx-io and its API removed (`m4-no-stream-library.md`, amended in §3 below) | yes (`apiDump`) | none (IO-8) |
| 5 | IO-7b | candidate B: one commit on a branch off IO-3 | — | none (IO-8) |
| 6 | IO-8 | A/B: base IO-5, A = IO-3's commit, B = IO-7b | machine | **~5¼ h, overnight** |
| 7 | IO-9 | v2 → v3: M0 against the head after IO-8 | machine | **~2¾ h** |

Then M5's batch 1 (`m5-decomposition.md`), whose A/B also carries the encoder's open points (§5).

### IO-5 · Stream benchmark rows (benchmark only, committed alone)

So that IO-8's base measures what a JVM user does today with a stream, and the candidates the same task through the new
functions.
- avro4k, the three workloads (`Avro4kSimpleBenchmark`, `Avro4kBenchmark`, `Avro4kListsBenchmark`):
  - `readFromStream()`: decodes `ByteArrayInputStream(data)`, created per call. At IO-5 through
    `Avro.decodeFromSource(schema, input.asSource().buffered())`; IO-7 switches the body to `Avro.decodeFromStream`.
  - `writeToStream()`: encodes into `OutputStream.nullOutputStream()` (created once). At IO-5 through
    `Avro.encodeToSink(schema, value, output.asSink().buffered())` then `flush()`, the sink created per call as a caller
    with a stream does; IO-7 switches it to `Avro.encodeToStream`.
- Apache reflect, the three workloads: `readFromStream()` with `DecoderFactory.get().binaryDecoder(ByteArrayInputStream(data), decoder)`,
  the decoder reused as Apache users do. Apache's `write` row already writes to a stream (a direct encoder over a
  discarding stream) and is the write reference.
- `readFromSource` stays until IO-3 (its history ends there; `readFromStream` at IO-5 is the same code path, so the two
  rows continue each other).
- A JMH configuration `streamab` with the rows of IO-8 (below), so every side runs the same set.
- Acceptance: `./gradlew :benchmark:compileKotlin`; the orchestrator runs one smoke iteration of the new rows.

### IO-6 · One array-reading decoder (merge)

Merge `v3/one-cursor-decoder` (`d4aae3bf`; `v3/m2-foundation` has 8 commits since their common base): `ArrayCursorDecoder` holds every read,
`ByteArrayDecoder` and `KotlinxIoDecoder` only say what follows the array. Rename `ArrayCursorDecoder` to
`BufferedDecoder` (user, 2026-10-24). Conflicts are expected in the decoders touched since (none of the encoder work
touched them). Its own A/B, dropped on 2026-10-24, is folded into IO-8: `read` must not move. Acceptance: `ACC`, test
counts unchanged.

### IO-7 · `encodeToStream`/`decodeFromStream` in core's JVM source set

**API** (`apiDump` alone; the diff shows exactly these):
- `Avro.encodeToStream(writerSchema, serializer, value, output)`, reified `encodeToStream(value, output)` and
  `encodeToStream(writerSchema, value, output)`;
- `Avro.decodeFromStream(writerSchema, deserializer, input)`, reified `decodeFromStream(input)` and
  `decodeFromStream(writerSchema, input)`;
- `AvroSingleObject.encodeToStream(writerSchema, serializer, value, output)` and `decodeFromStream(deserializer, input)`,
  as JVM extensions (the class is in `commonMain`).
- Same opt-in annotations as today's `encodeToSink`/`decodeFromSource`.

**Contract** (KDoc and README):
- `encodeToStream` hands every byte to the stream before returning; it neither flushes nor closes the stream.
- `decodeFromStream` reads **exactly one value per stream**, as `Json.decodeFromStream`: it reads the stream as it
  decodes (an array refilled, never the whole input at once), then requires the stream to end. Bytes left in the array,
  or `read()` not returning -1, throw `AvroMalformedDataException("Not all bytes were consumed during deserialization")`,
  as `decodeFromByteArray` does. The stream is neither rewound nor closed. End of input inside a value:
  `AvroMalformedDataException` (through `decodeWithValidation`, cause `java.io.EOFException`); an `IOException` of the
  stream propagates unchanged. KDoc: several values written back to back are read with `decodeFromByteBuffer` (a
  `ByteBuffer` keeps its position after each value); a `decodeToSequence(InputStream)` may come later
  (`backlog-after-v3.0.md` item 9).
- Behaviour change from v2, a migration item: v2 returned the first value of a stream holding several and dropped the
  rest (#14); v3 throws on that stream.

**Code:** `internal/codec/InputStreamDecoder.kt`, a `BufferedDecoder` that refills from the stream, with `skipAcross`
reading or skipping past the array (`skipExactly`, without Java 12's `skipNBytes`) and an `ensureEnd()` for the
contract above; the entry points in `AvroJvmStreams.kt`; `ByteArrayEncoder.writeTo(output)`. The sketch is
`m4-stream-options.md` (b) without its `mark`/`reset` part (`rewindable`, `release`).

**Two candidates, pre-decided, the A/B picks** (read rows and write rows are separate, so each side pairs one decoder
policy with one encoder policy without mixing their effects):

| | decoder's array | encoder's output |
|---|---|---|
| **A** (IO-7, main line) | 8 KiB, allocated per call | `ByteArrayEncoder`, then its chunks written to the stream |
| **B** (IO-7b, branch) | sized from `input.available()`, clamped to 512 B – 8 KiB, doubled on each refill up to 8 KiB | the same encoder writing each full chunk to the stream and reusing one array, so memory stays bounded by the chunk size whatever the value's size |

Why these: a `simple` @1 record is about 60 bytes, and zeroing an 8 KiB array per call may cost as much as decoding it
(B should win small values); a 9.7 MB `lists` @10000 value is held whole by A before being written (B should win large
values, and allocates less). If B wins only one family, IO-8 keeps the winner of each.

**Tests** (jvmTest; counts before/after):
- the v2 behaviours of §1: a 7-byte value written whole; a stream holding two values throws on the first call (leftover
  bytes in the array, and leftover bytes only reached by `read()` after a value ending exactly at a refill);
- a stream returning one byte per `read`; values crossing a refill (varint, string, fixed, skipped writer-only field);
  a value larger than the array;
- end of input mid-value; empty stream; the stream is not closed;
- single object both ways, and its header split across a refill;
- byte identity of `encodeToStream` with `encodeToByteArray` on the Apache oracle cases.

**Acceptance:** `ACC`, `apiDump` diff = the eight functions.

### IO-7b · Candidate B

One commit on branch `v3/jvm-streams-b`, off IO-3's commit: the two policies of the table, nothing else (same tests).
`ACC`. Deleted if A wins both families.

### IO-8 · A/B and merge (orchestrator, overnight)

- **Sides:** base = IO-5's commit (streams through kotlinx-io, today's decoder); **A** = IO-3's commit (one-cursor
  decoder, JVM streams A, no kotlinx-io); **B** = IO-7b.
- **Rows** (`streamab`), every workload and parameter:
  - avro4k `readFromStream`, `writeToStream`: **the decision** (base → A → B);
  - avro4k `read`, `writeToByteArray`: **regression check** of IO-6 and IO-3 (expected flat; they are not controls,
    the code under them changed);
  - Apache reflect `read`, `readFromStream`, `write`: **noise reference**, the same code and versions on every side.
- **Method** (as IO-4): 5 rounds × 3 sides, side order rotated each round, 1 fork × 5 × 1 s warmup × 5 × 3 s,
  escape analysis on. Then `benchmarkAlloc` once per side on the four avro4k methods, `-XX:-DoEscapeAnalysis`. AC
  power, Low Power Mode off, load below 3 at each start, one `caffeinate -dimsu` over the whole run, `pmset -g log`
  checked on lines starting with a date. 56 rows per invocation (21 methods; counted at IO-5, the first estimate said 48): ~5¼ h in all.
- **Verdict rules:**
  - read and write families decided separately: the candidate above the other on the most separated rows; a tie keeps
    A (simpler);
  - `read` or `writeToByteArray` below base by more than 3% and separated on any row: **stop before merging**, and
    run base vs IO-6 alone on `read` to tell the decoder merge from the removal;
  - expected, to check: stream reads at least as fast as base everywhere; stream writes above base (no `Buffer`).
- Numbers into `benchmark/README.md` ("Results — JVM streams without kotlinx-io") in the commit that merges the kept
  candidate (or in IO-3's line if A is kept, with a docs commit).

### IO-9 · v2 → v3: M0 against the head (orchestrator)

Answers "how much faster is v3 than v2 on the JVM", for the release notes and the README. The last such run stopped at
the end of M3 (`benchmark/README.md`, "M3 complete"); everything since (the decoders on a plain array, the
`ByteArrayEncoder`, the JVM streams) was measured only against its own base.
- **Sides:** **v2** = `4508a13` (M0) plus one benchmark-only commit on branch `bench/v2-rows`, adding the rows below
  with v2's API; **v3** = the head after IO-8.
- **Rows**, the three workloads, every parameter:
  - avro4k `read` (`decodeFromByteArray` on both);
  - avro4k `readFromStream`: v2's `decodeFromStream(schema, serializer, ByteArrayInputStream(data))` (one value per
    stream, which v2 reads correctly) / v3's `decodeFromStream`;
  - avro4k `writeToStream`: v2's `encodeToStream` writes nothing (§1), so its row is
    `encodeToSink(schema, value, output.asSink().buffered())` then `flush()`, what a working v2 caller wrote / v3's
    `encodeToStream`;
  - avro4k `writeToByteArray`: `encodeToByteArray` on both;
  - Apache `SpecificData` `read` and `write`: context for the "÷ Apache" columns only. Apache is 1.12.1 on v2 and 1.12.2
    on v3 and moved by −32% to +18% between them, so it is **not** a noise reference: verdicts rest on separation.
  - The old `write` row (to a kotlinx-io `Sink`) is not run: v3 has no `Sink`; `writeToStream` replaces it.
- **Method:** the "M3 complete" protocol: 5 invocations per side alternated (1 fork × 5 × 1 s + 5 × 3 s, escape
  analysis on), then `benchmarkAlloc` once per side on the avro4k rows, EA off. Same machine conditions as IO-8. ~2¾ h.
- **Report:** `benchmark/README.md`, "Results — v2 against v3 on the JVM (4508a13 vs …)": per row v2, v3, Δ, separated,
  verdict, ÷ Apache; B/op v2 → v3; the Kotlin, Apache and Jackson versions of each side. It supersedes "M3 complete" as
  the current state. `PROGRESS.md` "Measured numbers" updated.

## 3. What changes in IO-3 (`m4-no-stream-library.md` §4)

- Runs after IO-7, not before: the stream rows already call the new functions.
- **Benchmark:** `readFromSource` and the avro4k `write` rows (kotlinx-io `Sink`) are removed; `writeToStream` and
  `readFromStream` continue them.
- **Docs:** README "JVM stream encoding" stays, rewritten with `encodeToStream`/`decodeFromStream` (one value per
  stream); "Encode many objects in the same stream" writes the values one after another, then reads them back with
  `decodeFromByteBuffer` on the JVM (the buffer's position moves past each value); off the JVM, `ByteArray` only.
- **Migration items** (`b8-migration-items.md`):
  - users of `encodeToSink`/`decodeFromSource` → on the JVM, `encodeToStream(…, sink.asOutputStream())` /
    `decodeFromStream(…, source.asInputStream())` (kotlinx-io's own adapters), one value per source; several values
    from one source: its bytes read into a `ByteBuffer`, then `decodeFromByteBuffer` value by value; off the JVM,
    `ByteArray`;
  - users of v2's deprecated `encodeToStream`/`decodeFromStream`: same names, with `AvroSchema`; a stream holding more
    than one value now throws instead of returning the first one.
- `jvmMain` keeps `java.io.EOFException` directly; the `expect` class of §4.3 is for common code.

## 4. Effect on the rest of the plan

- `m4-no-stream-library.md` §5's "branch not merged" no longer holds: IO-6 merges it.
- README "Encode many objects in the same stream" (IO-3's docs): on the JVM, `decodeFromByteBuffer` value by value;
  `decodeFromStream` is one value per stream.
- `backlog-after-v3.0.md`: item 8 (JVM streams done in v3.0, a kotlinx-io module possible later), item 9
  (`decodeToSequence(InputStream)`), item 10 (public experimental codec classes, after M5).
- `backlog-after-v3.0.md` item 5 (benchmark setup decoding through the measured path) applies to `readFromStream` too,
  if its large rows show the bimorphic calls `readFromSource` did.

## 5. Benchmarks after these units (already planned elsewhere)

- **M5's batch 1 A/B** carries `m4-bytearray-encoder.md`'s open points as extra candidates in the same run: one shared
  empty `IntArray` (expected to close part of `simple` @1's gap to one array) and a first chunk of 64 B against 256 B.
- **M5-00, multiplatform benchmarks:** `read`, `writeToByteArray` on JS and native, where the removed kotlinx-io
  allocated an 8 KiB segment per `encodeToByteArray` call (the native gain still to measure). Streams are JVM only.

## 6. IO-7 findings (2026-10-25)

- **Three choices the plan left open, made toward v2:**
  - The `Avro` and `AvroSingleObject` stream functions are in `AvroJVMExtensions.kt`, not a new `AvroJvmStreams.kt`: the
    JVM facade class stays v2's `AvroJVMExtensionsKt`, so Java callers keep the class they called (the plan's file name
    was a sketch).
  - Parameters keep v2's names, `outputStream`/`inputStream` (the plan wrote `output`/`input`): named arguments of v2
    callers still compile, and no public name changes without the user's approval.
  - `AvroSingleObject.encodeToStream`/`decodeFromStream` carry `@ExperimentalAvro4kApi`, as v2's members did through
    their class. `Avro`'s carry no opt-in, as `encodeToSink`/`decodeFromSource` (v2's were experimental and deprecated).
- **Not in the plan, hardening:** `readAcross` allocates the value's array before reading only up to 1 MiB past the
  refilled array; beyond, `InputStream.readNBytes(int)` (Java 11) reads first, growing as the stream delivers, so a
  corrupt length allocates no more than the stream holds plus 1 MiB. kotlinx-io's `readByteArray(n)` behaved the same
  way (`require(n)` before allocating), so this keeps v2's behaviour rather than adding a divergence. Test: a 2 GB length
  followed by 3 bytes fails with an EOF cause.
- **Single object:** `decodeWithHeader` reads the 10 header bytes with `readFixed(10)` on any `AvroBinaryDecoder`, so the
  header crosses refills like any value; the `ByteArray` path keeps its own header reading (unchanged).
- **A trap for tests:** `ByteArrayInputStream.readNBytes(b, off, len)` reads once, relying on its own `read` returning
  every byte asked for. A subclass capping `read` (to simulate a socket) makes `readNBytes` fall short and looks like an
  end of input. The test streams wrap a `ByteArrayInputStream` instead of extending it.
- **The smoke runs of IO-5 and IO-7 were made on battery with `-PallowReducedPower`, against the rule that every
  benchmark runs on AC power with Low Power Mode off (the user caught it).** Their numbers are discarded and not cited
  anywhere; they only showed that the rows run. IO-8, on AC power, gives the first numbers of these rows.

## 7. IO-7b findings (2026-10-25)

- **Decoder: the array doubles on a refill only when the previous read filled it.** The decoder starts empty, so the
  first read is itself a refill: doubling on every refill would have thrown away the array sized from `available()`
  unread. And a stream returning less than asked (a socket, a pipe) gains nothing from a larger array. A stream that
  reports nothing (`available()` = 0, the default of `InputStream`) and fills every read starts at 512 B and reaches
  8 KiB after 4 refills; a `ByteArrayInputStream` larger than 8 KiB starts at 8 KiB, as A.
- **Encoder: `ByteArrayEncoder` takes an optional `writeFullChunk`** (common code, one class, so the encoder's calls stay
  monomorphic when a program writes both arrays and streams). Chunks still grow from 256 B to 64 KiB, each full one
  handed to the stream; from 64 KiB the array is reused. A single `bytes`/`fixed` larger than the chunk gets a chunk of
  its size, as in A. `toByteArray` is not used in that mode.
- **Two behaviour differences from A, to weigh with the numbers at IO-8's verdict:**
  1. **A failed encode leaves bytes in the stream.** A writes nothing until the whole value is encoded, so a value
     failing mid-way (an unsupported value, a schema mismatch) leaves the stream untouched; B has already written the
     chunks filled before the failure (any value past 256 B). kotlinx-serialization-json's `encodeToStream` (512-byte
     buffer written when full) and v2's `encodeToSink` behave as B. The KDoc says nothing of a failure either way; if B
     is kept, it should say the stream may hold part of the value.
  2. **No size limit on a value written to a stream.** A holds the value in chunks and throws past `MAX_BINARY_LENGTH`
     (it must fit `toByteArray`'s array); B never holds it, so the check is skipped and a value over 2 GiB streams out.
     v2 wrote to a `Sink` without limit either.
- `writeToByteArray` runs the same code on both sides apart from one null check on the chunk-growth path (out of line):
  IO-8's regression rows should show it flat.

