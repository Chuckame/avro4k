# M4 — `ByteArrayEncoder`: `encodeToByteArray` without kotlinx-io (IO-1, IO-2, IO-4)

**Status:** done 2026-10-25, merged into `v3/m2-foundation` by `afcaecd4`. Plan: `m4-no-stream-library.md` §4.
Numbers: `benchmark/README.md` → "Results — a ByteArray written without kotlinx-io".

## What was built

- **IO-1** `d0391d70`: `writeToByteArray` rows in `Avro4kSimpleBenchmark`, `Avro4kBenchmark`, `Avro4kListsBenchmark`
  (`Avro.encodeToByteArray(schema, value)`) and in the three Apache reflect classes (a buffered `BinaryEncoder` created
  once over a `ByteArrayOutputStream` reset per call, `flush()`, `toByteArray()`). Smoke run: 18 rows.
- **IO-2**, `internal class ByteArrayEncoder : AvroBinaryEncoder()` in `internal/codec`, used by
  `Avro.encodeToByteArray`, `AvroSingleObject.encodeToByteArray` (through a new `internal fun encodeWithHeader`: marker,
  version and fingerprint written by `writeByte`/`writeFixed`, then the value, into one encoder) and
  `JavaStdLibSerializers.toBigDecimalBytes`. `encodeToSink` keeps `KotlinxIoEncoder` until IO-3.
  - Candidate **A** `ef5fc8fa` (+ test `a704b005`): one array, 256 B, doubled when full, `copyOf(position)`.
  - Candidate **B** `0d614465`, **kept**: chunks, the first 256 B, each next twice the previous up to 64 KiB; a varint
    or number needs contiguous room, so a chunk can end with up to 9 unused bytes (its length is kept in
    `fullChunkLengths`); `writeFixed` fills the chunk and puts the rest at the start of the next one, sized for it if
    larger; `toByteArray` concatenates once.
  - Floats and doubles written with `toBits()` (every NaN canonical), as kotlinx-io's `writeFloatLe`/`writeDoubleLe`
    did: byte output unchanged.
  - Over `MAX_BINARY_LENGTH` bytes in total: `IllegalStateException` (kotlinx-io's `readByteArray` failed there too).

## Tests (7 added; jvm 1048 → 1055, jsNode 232 → 239, macosArm64 235 → 242)

`ByteArrayEncoderTest` (common): every varint vector written after 240–260 bytes (fits, ends exactly at the end,
grows); several growths in order (≈45 KB); a payload of 400 KB mixing 1 KB arrays and 10-byte longs (crosses the 64 KiB
cap); a value larger than the next array; float/double little-endian with canonical NaN, also across a growth; empty
string/bytes/collections; nothing written. `VarintTest`: the vectors and boundaries also through `ByteArrayEncoder`.
`KotlinxIoEncoderTest` (jvm): the Apache byte-identity cases run on both encoders.

The tests caught one bug in A before its commit: `value.copyInto(bytes, reserve(n))` read `bytes` before `reserve`
replaced it.

## Bytecode sizes (`javap -c -p`, last instruction offset)

| method | `KotlinxIoEncoder` | B |
|---|---:|---:|
| `writeInt` | 35 | 53 |
| `writeLong` | 39 | 61 |
| `writeFixed` | 19 | 54 |
| `writeBytes` | 43 | 35 |
| `toByteArray` | — | 123 |

`writeInt`/`writeLong` grew past the 35-byte `MaxInlineSize` (the room check joins the one-byte check), still far under
`FreqInlineSize` (325) for hot call sites. The write rows did not suffer (+5% to +14%).

## A/B (IO-4) in short

B above base on every avro4k write row (+5.1% to +14.1%; separated on 6 of 8), A only on small records and below base
on `complex` @15/@200 (−3.3%, separated). Controls flat. EA-off allocation is higher than base on the JVM (kotlinx-io's
segment pool is not counted): B ×1.3 to ×1.8, A ×2.2 to ×3.5.

## Open points

1. **`simple` @1: A is 11% above B.** B's encoder carries three more fields and allocates an empty `IntArray` per
   call (+24 B/op), and its `toByteArray` is larger. A shared empty `IntArray` (or a lazily created lengths array) is a
   one-line change; not done after the A/B so that the merged code is the measured code. Worth measuring with M5's
   batch 1.
2. **First chunk size.** 256 B is 4× a `simple` @1 record; a smaller start allocates less for single small values but
   grows once more for records of a few hundred bytes. Not measured.
3. **Native:** kotlinx-io does not pool segments there, so base allocated an 8 KiB segment per call; the gain is
   expected larger, to measure once the multiplatform benchmarks exist (M5-00).
4. **The driver's sleep check** matched `pmset -g log`'s undated summary lines (letters sort after digits): restrict
   to lines starting with a date. The real check (`$1 ~ /^20[0-9][0-9]-/`) found no sleep or dark wake.
