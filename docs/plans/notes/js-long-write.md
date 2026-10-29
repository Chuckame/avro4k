# Writing `long` values on Kotlin/JS without emulated-`Long` arithmetic

**Outcome:** two candidates on branch `v3/js-long-write`, base `4b7ea253`, each green on its own (ACC and ACC+KMP):

1. **Platform split** (first commit): JS writes a long from its two 32-bit halves; the JVM and native keep their `Long`
   code, JVM bytecode unchanged but for one call opcode.
2. **Common** (second commit): the halves code on every platform, the split removed. To keep only if the JVM write rows
   do not move.

**Kept (2026-10-30): the platform split**, merged as `19a335ef`; the common version is left out. Measured
(`benchmark/README.md` → "Results — reordered and nested record reads, and long values written on JS"): both
candidates give the same JS gains (`lists` write ×9, `complex` +21% to +34%, `simple` +33% to +61%); the common one
cost the JVM `CollectionMicroBenchmark.writeLongs` −18.8% @10 and −10.6% @100000 (every fork) and native writes 2–5%
against the split.

Internal code only: `core/api` and `apache-interop/api` clean, no rename, no new public API. Nothing pre-existing found:
`main` is JVM-only, so JS speed is migration scope.

## Why

Kotlin/JS (2.4.20, the default `Long`, not the BigInt one) represents a `Long` as an object `Long(low, high)`, and
each operation on it is a stdlib call that returns a new object. The same holds for constants, which are not hoisted:
`0x7FL` becomes `new Long(127, 0)` at each use. Read from the compiled JS of the test executable
(`build/js/packages/avro4k-core-test/kotlin/avro4k-core.js`):

- the one-byte check of `writeLong`, `(value shl 1) xor (value shr 63)`, then `and 0x7FL.inv() == 0L`: 6 allocations
  (`shiftLeft`, `shiftRight`, `bitwiseXor`, two constants, `bitwiseAnd`) and `equalsLong`, **even for a value of one
  byte**;
- the varint (`encodeZigZagLong(Long, …)`) redoes the zig-zag (6 more), then for each byte a `bitwiseOr` with a new
  constant, a `shiftRightUnsigned` and a `compare` against `new Long(127, 0)`, which itself calls `subtract`
  (`add(negate(…))`): about 6 to 7 allocations per byte.

A random 64-bit long, 9 or 10 bytes, cost about 70 allocations and as many calls. After this change, the JS writer
allocates **once** per long: `(value ushr 32).toInt()` goes through `shiftRightUnsigned`, which returns a new `Long`
whose `low` is read (`convertToInt`). The stdlib offers no allocation-free way to the high half (its `high` field is
internal to the stdlib and its name mangled, minified in production builds); V8 may scalar-replace that one object once
the shift is inlined, which is not measured here. Everything else is `Int` arithmetic, compiled to plain JS `<<`, `>>`,
`>>>`, `^`, `|`.

A fast path for longs that fit in an `Int` would not have been enough: `lists` writes full-range random longs, and
real longs (millisecond timestamps, ids) are mostly above 32 bits.

## The halves encoder

`encodeZigZagLong(low: Int, high: Int, bytes, offset): Int` in `C/internal/codec/Varint.kt`, where `C` is
`core/src/commonMain/kotlin/com/github/avrokotlin/avro4k`:

- zig-zag per half: `sign = high shr 31`, `lo = (low shl 1) xor sign`,
  `hi = ((high shl 1) or (low ushr 31)) xor sign`;
- if `hi != 0` (33 bits or more, so 5 bytes or more): the low 28 bits in 4 continuation bytes, then
  `lo = (lo ushr 28) or (hi shl 4)`, `hi = hi ushr 28`; if that `hi != 0` again (61 bits or more, 9 bytes or more):
  the same once more, after which `lo` holds the last 8 bits;
- what is left is an unsigned 32-bit value in `lo`, written as 1 to 5 bytes with the same unrolled shape as
  `encodeZigZagInt` (first test on the mask, as `lo` can be negative as an `Int`, then `> 0x7F`).

`writeLong` takes `low = value.toInt()` and `high = (value ushr 32).toInt()` once. Its one-byte case is the long being
an `Int` of one zig-zag byte: `high == (low shr 31) && zigZag and 0x7F.inv() == 0`, `zigZag` being the zig-zag of
`low`; otherwise it calls the out-of-line `writeVarLong(low, high)`, which reserves 10 bytes and calls the encoder.

## Candidate 1: platform split (`17e6711f`)

`writeLong` is a member reading the encoder's private `bytes` and `position`, and a member of a non-`expect` class
cannot be written per platform. A top-level `expect` helper called from `writeLong` would have changed its bytecode
(an extra call, or the local-variable markers of an inline function), so the split is at the class:

- `ChunkedByteArrayEncoder` (common, abstract): today's `ByteArrayEncoder` without `writeLong`. `bytes` and `position`
  become `@JvmField internal` (still read and written with `getfield`/`putfield`); `writeVarLong(Long)` becomes
  `internal` (the JVM and native out-of-line part); `writeVarLong(low, high)` is added (the JS one).
- `internal expect class ByteArrayEncoder(writeFullChunk = null) : ChunkedByteArrayEncoder`, declaring
  `override fun writeLong`. Every caller still says `ByteArrayEncoder(…)`.
- `jvmAndNativeMain/…/codec/ByteArrayEncoder.jvmAndNative.kt`: today's `writeLong`, with a comment on why it stays.
- `jsMain/…/codec/ByteArrayEncoder.js.kt`: the halves `writeLong`, with a comment on why.

The `Long` encoder `encodeZigZagLong(Long, …)` stays for the JVM and native; the halves one is common (its tests run on
the three platforms), unused on the JVM and native in this candidate.

## Candidate 2: common (`a731c81d`)

One `ByteArrayEncoder` again, as at `4b7ea253`, with the halves `writeLong` and a private `writeVarLong(low, high)`.
`encodeZigZagLong(Long, …)` is removed; its tests (`VarintTest` in common, the Apache differential `VarintDifferentialTest`
on the JVM, 100,000 random longs) now call the halves encoder through `value.toInt()` and `(value ushr 32).toInt()`.
No expected byte changed.

## Bytecode (`javap -c -p`, offset of the last instruction, `core/build/classes/kotlin/jvm/main`)

Compared instruction by instruction, constant-pool indexes and owner classes aside.

| Method | `4b7ea253` | Split | Common |
|---|---:|---:|---:|
| `ByteArrayEncoder.writeLong(long)` | 61 | 61, identical but one instruction (below) | 81 |
| `writeVarLong(long)` | 22 | 22, identical (now `ChunkedByteArrayEncoder.writeVarLong$…`) | removed |
| `writeVarLong(int, int)` | — | 23 (unused on the JVM) | 23 |
| `VarintKt.encodeZigZagLong(long, byte[], int)` | 315 | 315, identical | removed |
| `VarintKt.encodeZigZagLong(int, int, byte[], int)` | — | 328 (unused on the JVM) | 328 |
| every other method of the encoder | | identical | identical, but `nextChunk` 248 → 249 |

- Split, `writeLong` at 58: `invokespecial writeVarLong` became `invokevirtual writeVarLong$com_github_avro_kotlin_avro4k_core`.
  Same size; the target is `public final`, so HotSpot binds it statically as before. Some opcode had to change: the
  out-of-line part needs the private `reserveUpTo`/`nextChunk` of the shared class, so either it moves there (this) or
  `reserveUpTo` becomes non-private, which changes `writeVarInt` and `reserve` too.
- Common, `writeLong` 62 → 82 bytes: two `l2i` and a `lushr` then `int` operations, instead of `lshl`/`lshr`/`lxor`/
  `land`/`lcmp`. Above `MaxInlineSize` (35) before and after, far below `FreqInlineSize` (325): it is inlined when hot,
  as before. `writeInt` (53) is unchanged.
- Common, `nextChunk` + 1: the constant pool passed 255 entries, so the `ldc` of its error message (on the throw path)
  became `ldc_w`.

## Other places that write a long

- `ValidatingAvroBinaryEncoder.writeLong`: delegates, no arithmetic. Unchanged.
- `ByteArrayEncoder.writeItemCount(count: Long)`: `count > 0L` stays a `Long` comparison on JS (one `compare` and one
  constant per array or map, not per item), then `writeLong(count)`, which now takes the halves path. The count is a
  `Long` in `AvroBinaryEncoder.writeArrayStart`/`writeMapStart` (public, `@InternalAvro4kApi`), not changed here.
- `apache-interop`'s adapters are JVM-only and delegate to Apache; the Confluent serde's `writeLong` throws. Unchanged.
- Decoders already read a varint long in `Int`s.

## Tests

Counts at `4b7ea253`: `:core:jvmTest` 1175, jsNode 332, macosArm64 335. With either candidate: **1179 / 336 / 339**,
`ZigZagLongWriteTest` (commonTest) adding 4 tests on each platform:

- `the encoder working on the halves of a long writes the bytes of a plain long encoder`
- `writeLong writes the bytes of a plain long encoder and they are read back`
- `writeLong at the end of a chunk writes every value whole and grows the chunk` (filler of 246 to 256 bytes before the
  long: from 10 bytes of room in the first chunk to none, so the one-byte path, the out-of-line path and chunk growth)
- `every value written one after the other across many chunks is read back in order` (with and without a chunk writer)

The reference is a plain `Long` loop in the test (`referenceEncode`). The values: 0, ±1; ±2^b and ±2^b − 1 for
b = 7k − 1 and 7k, k = 1..9; `Int.MIN_VALUE`/`Int.MAX_VALUE` and ±1; ±2^32 and ±1; `Long.MIN_VALUE`, `Long.MAX_VALUE`
and their neighbours; small values (−64, −1, 0, 1, 63) plus or minus 2^32 and with the sign bit flipped, whose low half
alone would take one byte; then 1,000 random longs and 2,000 shifted right by a random count (every length equally
likely), seed 20261029.

Checked that the tests bite (throwaway mutation, not committed): dropping `high == (low shr 31)` from the JS one-byte
check fails the 4 tests on JS; replacing `hi = hi ushr 28` by `hi = 0` fails the halves-encoder test on the three
platforms. The golden fixtures and the Apache oracle are unchanged and green.

## ACC

Both commits: ACC (both invocations) green, `git diff --exit-code core/api apache-interop/api` clean, and the native test
binaries of the 10 targets without a runner linked (ACC+KMP), the split touching platform source sets and the common
commit removing them.

## Predictions

Rows of `benchmark-common` (JS, native) and `benchmark/` (JVM). No timing was taken here.

**JS write rows move, read rows do not:**

- `lists` write moves most: its payload is almost only full-range random longs (`StatsEntry.elementId` and `values`),
  9 or 10 bytes each, which go from about 70 allocations and calls each to 1 allocation. Expect a several-fold gain
  over the 190 ops/s of the smoke run, toward the read row's order of magnitude (1,016), not above it: the record and
  list encoding around each long are unchanged.
- `complex` write: `Client.id`, the 5 to 10 `phones` (each a non-negative `Int` widened to `Long`: 5 bytes, `hi` = 0
  or 1 after the zig-zag), the partner's `id` and `since` (a `timestamp-millis` long). A clear gain, smaller than
  `lists`.
- `simple` write: one `long` field among nine, plus the list count: a small gain, by that share.
- Every read row on JS: no change (decoders untouched).

**JVM, split candidate:** no row moves. `writeLong` is the same bytecode but for one equivalent call opcode.

**JVM, common candidate:** no row should move. Writes (`lists`, `complex`, `simple`, the client writes) run `int`
operations in place of `long` ones in `writeLong` and the varint; the instruction count is close, and the inlining
class of `writeLong` is unchanged. Reads untouched. **B/op: exactly 0 delta on every JVM row, with and without escape
analysis**: neither version allocates in `writeLong` or the varint on the JVM.

**Native:** split candidate unchanged. Common candidate: native also runs the halves code; native write rows should not
move (64-bit registers either way, a few more `Int` operations). Worth one look at the `lists` write row on
macosArm64 before keeping the common candidate.

## Open questions

- The one allocation left on JS, `(value ushr 32)`: no stdlib accessor reaches the high half without it. If it shows in
  a profile, `value.toDouble()` arithmetic or a JS `actual` reading the object's field by name were rejected here: the
  first costs more operations, the second depends on a mangled, minified stdlib field name.
- Which candidate to keep is the orchestrator's measurement: the common one if the JVM (and native) write rows hold,
  otherwise the split.
