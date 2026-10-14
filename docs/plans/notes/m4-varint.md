# M4 — Varint reads (and writes) small enough to inline

**Outcome:** done. Internal code only: no API change (`core/api`, `apache-interop/api` clean). Approved by the user on
2026-10-14 as a platform-neutral code-structure fix (a small hot path, the cold path out of line), plain common Kotlin:
no `expect`/`actual`, no JVM-only API, no annotation or JIT flag.

**Branch** `worktree-agent-a2b38af7b499e86c9`, base `3e5a3195`, one commit
`perf(core): keep varint reads small enough to inline` (code, tests, this note).

## Problem

`KotlinxIoDecoderKt.readVarLong` was 951 bytes of bytecode, `readVarInt` 486. HotSpot never inlines a method above
`FreqInlineSize` (325 bytes), even hot, so every int, long, string/bytes length, union index, enum index and block count
read was a call into a large method. The source copied Apache's split (`decodeVarLong` for the first 4 bytes,
`decodeVarLong2` for the rest) but both were `private inline` with a `readByte` lambda, so the Kotlin compiler pasted
the rare long-tail decoding back into the entry point, twice (in-segment array path and byte-by-byte path).

## What changed

`core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/codec/KotlinxIoDecoder.kt`

- **`Source.readVarInt()` / `Source.readVarLong()`** (now `internal` instead of `private`, so that `KotlinxIoDecoder`
  calls them directly instead of through a synthetic `access$` accessor): `readByte()`, and if the continuation bit is
  clear, zig-zag and return. Every other case calls the out-of-line tail with the first byte.
- **`readVarIntTail(first)` / `readVarLongTail(first)`** (private, non-inline): peek the head segment; if it holds every
  byte the varint may still need (4 for an int, 9 for a long), decode from its array and `skip(len)`; otherwise (split
  across segments, or not yet loaded from the underlying `RawSource`, or the buffer is empty after the first byte) call
  the byte-by-byte function.
- **`readVarIntTailByteByByte` / `readVarLongTailByteByByte`** (private, non-inline): the cross-segment slow path, one
  copy each instead of the copy pasted in every entry point.
- **Long tail split in two** (follow-up asked by the coordinator before benchmarking): `readVarLongTail` and
  `readVarLongTailByteByByte` decode only the 2nd to 4th bytes (the lengths ≥ 64, block counts and small values that
  do not fit in one byte); when the 4th byte has its continuation bit set, the 28 bits decoded so far go to the
  non-inline `readVarLongHighFromArray(lo, bytes, offset, consumed)` (decodes the 5th to 10th bytes from the array,
  then skips every byte read from it) or `readVarLongHighByteByByte(lo)`. `decodeVarLongTail` takes the hand-off as a
  second lambda; in the array path it returns non-locally, so the skip happens exactly once, in the high function.
  `readVarIntTail` (295) was left whole: it is already under 325, and an int has no high part worth splitting off
  (only a 5th byte).
- **`decodeVarIntTail` / `decodeVarLongTail`**: the old `decodeVarInt` / `decodeVarLong` taking the first byte as a
  parameter; same arithmetic, same unrolling, `decodeVarLong2` unchanged. Messages unchanged:
  `"Invalid varint encoding: more bytes than expected"` (int), `"Invalid varlong encoding: more bytes than expected"`
  (long).

Reading the first byte with `Source.readByte()` instead of `require(1)` + peeking the segment: `Buffer.readByte()` is
small and already handles the head-segment bookkeeping (recycling an emptied head); the 1-byte case no longer goes
through `Buffer.skip`. `readByte()` throws the same `EOFException` as `require(1)` on an empty input (it calls
`require(1)` itself on a `RealSource`), so callers' `endOfInputError` conversion is unchanged. A side effect: when the
first byte is the last one of a segment, `readByte()` recycles that segment, so the rest of the varint is read from the
next segment's array instead of byte by byte.

`core/src/commonMain/kotlin/com/github/avrokotlin/avro4k/internal/codec/KotlinxIoEncoder.kt`

- **`writeInt` / `writeLong`**: zig-zag, and if it fits in 7 bits `sink.writeByte(...)`; otherwise the private member
  `writeVarInt` / `writeVarLong`, which hold the previous `writeToInternalBuffer` + `writeToTail` body unchanged. Private
  members rather than top-level functions, so no `access$` accessor. Most of the 270 bytes were the two kotlinx-io inline
  helpers (`writeToTail` carries a `check` with a string-template message and the commit bookkeeping), not the encoding.
- `Varint.kt` `encodeZigZagInt` (128) / `encodeZigZagLong` (315) left as they are: both are already under 325 and are
  now only reached from the out-of-line writers; a 1-byte fast path there would duplicate the one in `writeInt`/`writeLong`.

Other decoders: `AvroBinaryDecoder` is abstract, `ValidatingAvroBinaryDecoder` only delegates to the wrapped decoder,
`apache-interop` wraps Apache's own decoders; no other varint loop exists in the repository (`grep 0x7F` hits only
`Varint.kt`, `KotlinxIoDecoder.kt`, the fingerprint and UTF-8 code).

## Bytecode sizes (offset of the last instruction, `javap -c -p`, the measurement command of the task)

| Method | Before | After |
|---|---:|---|
| `KotlinxIoDecoderKt.readVarInt(Source)` | 486 | **27** |
| `KotlinxIoDecoderKt.readVarLong(Source)` | 951 | **28** |
| `KotlinxIoDecoderKt.access$readVarInt` / `access$readVarLong` | 4 each | gone |
| `readVarIntTail(Source, int)` (new, out of line) | — | 295 |
| `readVarLongTail(Source, int)` (new, out of line) | — | 558 first cut → **270** after the split |
| `readVarLongHighFromArray(Source, long, byte[], int, int)` (new, 5–10 bytes) | — | 318 |
| `readVarIntTailByteByByte(Source, int)` (new, cold) | — | 134 |
| `readVarLongTailByteByByte(Source, int)` (new, cold) | — | 336 first cut → 116 after the split |
| `readVarLongHighByteByByte(Source, long)` (new, cold) | — | 230 |
| `KotlinxIoEncoder.writeInt(int)` | 270 | **35** |
| `KotlinxIoEncoder.writeLong(long)` | 272 | **39** |
| `KotlinxIoEncoder.writeVarInt(int)` / `writeVarLong(long)` (new, out of line) | — | 270 / 272 |
| `VarintKt.encodeZigZagInt` / `encodeZigZagLong` | 128 / 315 | unchanged |

(The unused JVM bodies of the `private inline` lambda helpers, `decodeVarIntTail` 132, `decodeVarLongTail` 350 then
125 after the split, `decodeVarLong2` 243, are emitted but never called, as before.)

`readVarInt`/`readVarLong` are now under `MaxInlineSize` (35), so HotSpot inlines them even before they are hot;
`writeInt`/`writeLong` sit just above it and far under `FreqInlineSize`, so they are inlined once hot;
`readVarIntTail` (295) and, after the split, `readVarLongTail` (270) are under `FreqInlineSize` and can be inlined
when hot, so a 2-to-4-byte long (string lengths of 64+ bytes, block counts) no longer costs a call. Longs of 5 bytes
or more (timestamps) take one call into `readVarLongHighFromArray` (318, itself under 325), as Apache's
`innerLongDecode`.

## Tests added (`core/src/commonTest/.../internal/codec/VarintTest.kt`, +8, all common)

- `every int length is read from the segment array and consumes exactly its bytes` /
  `every long length …`: every vector (int 1–5 bytes, long 1–10 bytes, `MIN`/`MAX`, 0, -1, each 7-bit boundary)
  followed by an 11-byte trailer, so the in-array path is taken for every length, then the trailer must be intact
  (the existing vector tests decode a buffer holding only the varint, which takes the byte-by-byte path for every
  length but the longest).
- `an int split across two segments is read whole at every split position` / `a long …`: filler so the first segment
  is full (8192 bytes, asserted through `UnsafeBufferOperations.forEachSegment`, so the test fails if the layout
  assumption ever breaks) and ends with 0..n bytes of the varint, for every vector and split position; trailer checked.
- `a varint arriving one byte at a time from the underlying source is read whole`: a `RawSource` delivering one byte
  per read, behind `buffered()`, for every vector.
- `an int of more than 5 bytes is malformed` / `a long of more than 10 bytes is malformed`: from the array, from the
  one-byte-at-a-time source, and split at every position.
- `the input ending inside a varint is an end of input`: empty input, and every strict prefix of every vector, from a
  `Buffer` and from the one-byte-at-a-time source, throw `kotlinx.io.EOFException`.

Checked that they bite: removing the head-segment size guard fails 5 of them (split, malformed, EOF); `skip(len - 1)`
fails 4 (array and split). All 14 `VarintTest` tests also pass against the original decoder (behaviour preserved).

No test added for the long split: the hand-off between the 4th and 5th bytes is already crossed by every 5-to-10-byte
long vector, from the array (`every long length is read from the segment array…`, with the trailer checking the skip),
byte by byte (the vector and boundary tests decode a buffer holding only the varint, so the head segment is too short
for the array path; the one-byte-at-a-time source), across a segment boundary at every position, and by the malformed
and truncated-input tests. Handing the wrong `consumed` count to `readVarLongHighFromArray` fails 4 tests (array, split,
vectors, boundaries).

## Acceptance

| Step | Result |
|---|---|
| `:core:jvmTest :apache-interop:test :confluent-kafka-serializer:test :kotlin-generator:test :gradle-plugin:test :benchmark:compileKotlin apiCheck spotlessApply` | green — jvmTest 1026, apache-interop 575, confluent 80, kotlin-generator 155 (1 skipped), gradle-plugin 3 |
| `actionsBeforeCommit` + `git diff --exit-code core/api apache-interop/api` | green, clean |
| `:core:check` | green — jvm 1026, jsNode 213, jsBrowser 213, macosArm64 216, iosSimulatorArm64 216 |
| 10 cross-compiled native `TestBinaries` | green |

Counts before → after: jvmTest 1018 → 1026, jsNode/jsBrowser 205 → 213, macosArm64/iosSimulatorArm64 208 → 216.
All four steps re-run after the long split, same counts, all green.

No benchmark was run (serialized by the orchestrator).

## Surprising / behaviour notes

- **Bytes consumed on a malformed varint:** before, a too-long varint read from the segment array threw before
  `skip`, consuming nothing (the byte-by-byte path already consumed what it read). Now the first byte is always consumed
  by `readByte()`. Decoding is aborted by the exception either way and nothing re-reads after it (no caller catches
  `AvroMalformedDataException` to retry), so this is not observable through the API.
- `UnsafeBufferOperations.writeToTail` takes its "fast path" only when the action writes exactly `minimumCapacity`
  bytes; a varint asks for 5 or 10 and usually writes fewer, so the multi-byte writes always go through the `check`
  path. Harmless, out of line now; noted in case a later unit looks at write throughput.
- A 5th int byte in `0x10..0x7F` (bits beyond 32) is accepted and the extra bits dropped, as Apache's
  `BinaryDecoder.readInt` does: not a bug relative to the reference, unchanged.

No pre-existing bug found.

## Open points

- The "start in the array, finish byte by byte" TODO for a varint split across segments is kept; it only affects
  varints whose bytes straddle a segment boundary.
