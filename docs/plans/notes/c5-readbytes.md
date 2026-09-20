# c5-readbytes — `readBytes()` returns the wrong bytes, and aliases memory it does not own

**Outcome:** done (correctness fix only — the rest of C5 is untouched)
**Commit:** `fix(core): copy the decoded bytes out of the decoder's buffer`

## What changed

Two source files, one new test file.

### 1. `AbstractAvroDirectDecoder.kt` — the `Decoder.readBytes()` helper

The `if (buffer.hasArray()) return buffer.array()` fast path is **deleted**. The helper now always copies
`remaining()` bytes out of the buffer. Both bugs described in the plan turned out to be real, and *reachable from
a public entry point* — not latent.

**Bug 1 (bounds) — real and reachable.** `Avro.decodeFromByteBuffer` builds
`DecoderFactory.directBinaryDecoder(ByteBufferInputStream(listOf(input)), null)`. Because the stream is a
`ByteBufferInputStream`, `DirectBinaryDecoder` installs its `ReuseByteReader`, which for `old == null` calls
`ByteBufferInputStream.readBuffer(length)`. That method's documented purpose is "read a buffer from the input
*without copying*": when `buffer.remaining() == length` it returns **the caller's own `ByteBuffer` as-is**, with
`position()` sitting after the fields already consumed. `array()` therefore returns the whole record.

Observed, before the fix, on a record `{header: "header", payload: [11,22,33,44,55]}` where the bytes field is last:

```
expected:<[11, 22, 33, 44, 55]> but was:<[12, 104, 101, 97, 100, 101, 114, 10, 11, 22, 33, 44, 55]>
```

— i.e. the decoded `ByteArray` contained the record's own header bytes (`12` = zig-zag length of "header", then
`header`, then `10` = length of the payload). The same shape was observed on all three call sites
(`decodeBytes()`, `decodeString()`'s `.decodeToString()`, and `decodeFixed()`'s `GenericData.Fixed`), and on a
`ByteBuffer.slice()` with `arrayOffset() == 5`, where the five prefix bytes leaked in as well.

**Bug 2 (aliasing) — real, and the guard proposed in the brief is provably insufficient.** The proposed guard
(`hasArray() && arrayOffset() == 0 && position() == 0 && remaining() == array().size`) is satisfied by a buffer
the decoder does not own: give `ByteBufferInputStream` two buffers, the second holding exactly the bytes value,
and `readBuffer` returns that second buffer untouched — position 0, arrayOffset 0, exact size, and still the
caller's array. The test `does not alias the caller's buffer even when its bounds exactly match the BYTES field`
overwrites the caller's array after decoding and, before the fix, the already-decoded value turned into
`[0, 0, 0, 0, 0]`. There is no property of the returned `ByteBuffer` that establishes exclusive ownership, so the
no-copy fast path cannot be made safe for an arbitrary `org.apache.avro.io.Decoder`. It is deleted rather than
guarded.

For completeness: Apache's buffering `BinaryDecoder.readBytes(null)` *does* allocate an exactly-sized buffer
(`ByteBuffer.allocate(length)`, `limit(length)`), so the object-container / `DataFileStream` path was never
affected. The bug is specific to decoders that hand back a view, which is exactly what avro4k's own
`decodeFromByteBuffer` produces.

### 2. `KotlinxIoDecoder.kt` — `readBytes(old)` (file outside this unit's declared ownership; see Follow-ups)

Removing the fast path immediately broke 20 existing `:core:test` cases with empty `ByteArray`s, which exposed a
third bug. `KotlinxIoDecoder.readBytes` violates the `Decoder.readBytes` contract in two ways on its
copying branch:

- it returns the buffer **un-flipped** (`position == limit`, so `remaining() == 0`). The old helper never noticed,
  because for that branch `hasArray()` was true and it read `array()` instead of the bounds. This is *why* the old
  code was accidentally safe: the two `KotlinxIoDecoder` branches use two different, mutually incompatible
  conventions, and the old helper happened to pick the right one for each.
- it filled the buffer with a single `source.readAtMostTo(buffer)`. `kotlinx.io`'s
  `Buffer.readAtMostTo(ByteBuffer)` copies **from the head segment only**, so a bytes value larger than the head
  segment (8 KiB) was silently truncated and zero-padded. Reachable from `Avro.decodeFromSource` /
  `decodeFromStream` over a real stream; not reachable from `decodeFromByteArray`, whose `Buffer(bytes)` helper
  (`UnsafeBufferOperations.moveToTail`) puts the whole array in one segment. Pinned by the new test
  `decodes a BYTES field spanning multiple source segments` (20 000-byte payload), which fails on pristine `main`.

Both branches now use a private `Source.readFully(ByteBuffer)` loop and `flip()` the buffer before returning it, so
the decoder honours the same convention as every Apache implementation.

### 3. `core/src/test/kotlin/com/github/avrokotlin/avro4k/ReadBytesBufferBoundsTest.kt` (new, 6 cases)

All three call sites, plus the offset/aliasing/segment cases. Every case has the bytes field **last** in the
record, so the buffer position is genuinely advanced before the value is read.

## What was measured

Nothing — no benchmark was run (the orchestrator owns the benchmark machine). This is a correctness fix; the
expected cost is stated rather than measured:

- **Small bytes values on the kotlinx-io path (the common case): unchanged.** The `length < segment.size` fast path
  already returned a read-only wrapper, so the helper already copied.
- **Bytes values ≥ the head segment size on the kotlinx-io path: one extra `ByteArray` allocation + copy** (the
  decoder allocates, then the helper copies out). Previously one allocation — but previously it was also returning
  truncated data whenever the value spanned segments, so the comparison is not meaningful.
- **`decodeFromByteBuffer` / any `ByteBufferInputStream`-backed decoder: one allocation + copy where there was
  zero.** That is the price of not handing user code a live view of a buffer it also owns.
- `FIXED` and `STRING` decoding are untouched: `readFixedBytes` and `readString` never used this helper.

Verification run (all green):

```
./gradlew :core:test :confluent-kafka-serializer:test :kotlin-generator:test
./gradlew spotlessApply
./gradlew actionsBeforeCommit      # includes apiDump — core/api/core.api unchanged
```

Before/after evidence: with both main-source files reverted to `HEAD` and only the new test present, all 6 new
cases fail; with the fix, all 6 pass and the previously-passing 684 `:core:test` cases still pass.

## What was rejected, and why

- **Keeping a guarded no-copy fast path.** Rejected: the two-buffer `ByteBufferInputStream` case satisfies every
  property the guard can check (`hasArray`, `arrayOffset() == 0`, `position() == 0`,
  `remaining() == array().size`) while the array belongs to the caller. Exclusive ownership is not observable from
  the returned `ByteBuffer`, and `readBytes`'s javadoc promises nothing about it.
- **Making the helper tolerant of un-flipped buffers** (e.g. "if `remaining() == 0 && position() > 0`, copy
  `0..position`") instead of fixing `KotlinxIoDecoder`. Rejected: it institutionalises a contract violation inside
  a helper that accepts *any* `org.apache.avro.io.Decoder`, and it would have left the multi-segment truncation
  bug in place.
- **Special-casing `KotlinxIoDecoder` in the helper** (`if (this is KotlinxIoDecoder) …`) to keep zero copies.
  Rejected: that is precisely the deferred C5 bytes fast path, and this unit is explicitly not it.

## Follow-ups

- **The rest of C5 — the string/bytes fast paths — remains open and is deferred to M5.** This unit is only the
  correctness fix from the plan's "Correctness note found while reading". Do **not** read the C5 ledger row as
  done. When M5 does take it on, the constraint established here is: a fast path may only avoid the copy if the
  *decoder implementation is known* (e.g. a type check on `KotlinxIoDecoder` plus a bytes-reading method that
  returns an exclusively-owned array), never by inspecting the returned `ByteBuffer`.
- `KotlinxIoDecoder.kt` was not in this unit's declared file ownership. The change there is small and
  self-contained (two `readAtMostTo` → `readFully` + `flip`, plus one private extension at the bottom of the
  file), but a concurrent unit editing `readBytes` will conflict.
- `KotlinxIoDecoder.readBytes`'s `old != null` branch is now contract-correct but is still dead code in avro4k —
  the only caller passes `null`. Worth deleting if nothing starts using it.
- The `length >= segment.size` branch allocates a `ByteBuffer` that the helper then copies again. If M5 adds a
  `readBytesExactly(): ByteArray` to `KotlinxIoDecoder`, both the intermediate `ByteBuffer` and the second copy
  disappear.
