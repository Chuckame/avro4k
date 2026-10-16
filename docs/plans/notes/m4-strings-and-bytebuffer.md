# M4 — Strings decoded by the JDK, `ByteBuffer` decoded in place

**Outcome:** both committed on 2026-10-15 (`9a63c731`, `9de25315`), measured on 2026-10-16. Internal code only, no API
change. Strings: faster on long strings, **slower than base on short ones, ASCII included** (`simple` reads −8% to −12%) and one new cross-platform
divergence on malformed input (open points below). `ByteBuffer`: not covered by any benchmark.

## Strings (`9a63c731`)

`KotlinxIoDecoder.readString` decodes with the standard library's `ByteArray.decodeToString` instead of kotlinx-io's
`Source.readString`: straight from the head segment's array when it holds the whole string, otherwise from a copied
array. On the JVM that is the JDK's UTF-8 decoder (as Apache Avro uses), which skips kotlinx-io's intermediate
`CharArray`, checks for ASCII with an intrinsic, and stores ASCII and Latin-1 strings one byte per character.

Tests (common, `KotlinxIoCodecTest`): malformed sequences decode as on the JVM (one replacement character per byte of an
overlong or out-of-range sequence, one for a sequence cut short); a string split across segments; a string longer than
the input is an end of input.

### Measured (`benchmark/README.md` → "Results — varint rework and JDK string decoding", varint `6f78f0a8` → head `9de25315`)

- End to end: `complex` read +42% @1 / +19% @15 / +15% @200 (separated; at @1, all nine head forks are above all nine base
  forks); every other row within noise. Allocation (EA off) −18% to −22% on every read that decodes strings.
- In isolation (`StringDecodeMicroBenchmark`, 3 × 3 forks): ASCII +26% (16 chars) / +139% (256); Latin-1 **−45%** (16) /
  +32% (256); CJK **−23%** (16) / **−13%** (256). The end-to-end workloads hold only ASCII strings (commons-lang
  `RandomStringUtils` with letters/digits draws from `' '..'z'`), so these losses do not show there.
- An earlier variant measured on 2026-10-15 (2 invocations, against `6f78f0a8`, not committed): a Kotlin ASCII scan,
  then the JDK for ASCII and kotlinx-io for anything else. No loss on any content (Latin-1 16 +7%, CJK 16 +7%, CJK 256
  0%), but much smaller ASCII gains (16: +10%, 256: +35%), since the scan is a plain loop where the JDK's is intrinsic.

### Against base, once the varint reads were reverted (2026-10-16)

The numbers above compare against the varint side, itself 33% slower than base on short ASCII strings. Against base
(`9740fb08` → `9de25315` + the varint revert, `benchmark/README.md` → "Results — varint reads back to one function"):
**short strings are slower than before, ASCII included** — 16 ASCII characters −18.5%, `StringFieldMicroBenchmark.readNonNull`
−12%, Latin-1 16 −46%, CJK 16 −23% — while long strings win (ASCII 256 +159%, Latin-1 256 +31%). End to end:
`complex` reads +9% to +12%, **`simple` reads −8% to −12%** (@500 −11.8%, separated). So this change, as committed, is
a regression for data made of short strings.

### Open points (for the user)

1. **Short strings are slower than base** (16 characters: ASCII −18.5%, Latin-1 −46%, CJK −23%; `simple` reads end to
   end −8% to −12%), long strings much faster. Options: keep kotlinx-io's decoder for short strings and the JDK's above
   a length threshold (to measure), revert the change, or keep it and look again in M5's string fast paths (C5).
2. **New cross-platform divergence on malformed input:** an encoded surrogate (`ED A0 80`) decodes to one replacement
   character on the JVM and to three on JS and native (noted in the test's KDoc, not pinned). Before this change,
   kotlinx-io's decoder gave the same result on every platform. Only invalid UTF-8 is affected; Avro requires valid UTF-8.

## `ByteBuffer` decoded in place (`9de25315`)

`Avro.decodeFromByteBuffer` (JVM) reads the buffer through a dedicated `ByteBufferDecoder` (`jvmMain`) instead of
copying all its remaining bytes into a kotlinx-io `Buffer` first (kotlinx-io has no `ByteBuffer`-backed `Source`): only
the value's bytes are read, the position ends right after them and is restored when decoding fails, floats and doubles
are read little-endian whatever the buffer's byte order. Heap, direct and read-only buffers. The varint multi-byte
decoders moved to `Varint.kt` (`inline`) to be shared by both decoders; `KotlinxIoDecoder`'s bytecode is unchanged by
that move. Tests: `DecodeFromByteBufferTest` (+2: a value cut by the limit, a direct buffer ending inside a varint).

Not measured: no benchmark decodes from a `ByteBuffer`. Its varint reads use the same one-byte fast path that was
reverted for `KotlinxIoDecoder` (`notes/m4-varint.md`), but there the first byte is a plain `ByteBuffer.get()`, not
kotlinx-io's `Buffer.readByte()`, so the reason for that revert does not carry over as is.
