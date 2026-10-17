# M4 — A `ByteArray` decoded with a plain position

**Outcome:** done on 2026-10-17. `Avro.decodeFromByteArray` reads through `ByteArrayDecoder` (`commonMain`): the array
and a position, no kotlinx-io, Apache Avro's varint shape. Internal code only, no API change. Numbers:
`benchmark/README.md` → "Results — a ByteArray read with a plain position".

## Why

Every avro4k read went through kotlinx-io's `Buffer`, which updates its segment bookkeeping on every read, and
`decodeFromByteArray` first copied the whole input into one. Apache's `BinaryDecoder` reads a plain `byte[] buf; int pos`,
which is what lets its `readInt`/`readLong` stay small and fast for the JIT. The user allowed code shaped for the JIT
(decision log, 2026-10-16).

## Candidates (A/B 2026-10-17, head `fe8debc1`)

- **v1** (`f9c3f34c`, merged as the first commit of the branch): the one-byte varint inline (`readInt` 41, `readLong` 42
  bytes of JVM bytecode), every longer one out of line.
- **v2, kept** (the commit after the merge): `readInt` decodes the whole int in one go when 5 bytes are left; `readLong`
  its first 4 bytes when 10 are left, bytes 5–10 out of line (`readVarLongHigh`, as Apache's `innerLongDecode`). Near the
  end of the input every byte is checked (`readVarIntSlow`/`readVarLongSlow`).

End to end both beat head on every read row (`simple` +22% to +35%, `complex` +6% to +16%, `lists` +4% to +8%) and tie
each other (one separated row: `lists` @100, v1 +3.3%). v2 is faster on the varint micros (field order +6% to +13%,
`readLongs` @10/@1000 +22%/+11%), slower on `readLongs` @100000 (−8%).

## Open points

1. **`CollectionMicroBenchmark.readLongs` @10 is below head** (v1 −28.5%, v2 −12.9%, separated, steady across forks).
   Mostly the fixed cost of one call (10 random longs, each a 10-byte varint, boxed into a `List<Long>`); the
   end-to-end rows with the smallest payloads do not show it. Probes on 2026-10-17 (single forks, indicative):
   - Steady, not bimodal: head ~5.85M, v2 ~4.0M ops/s in every iteration of one fork with `PrintInlining`. Allocation
     is lower on v2 (536 vs 600 B/op: no `Buffer` copy).
   - `PrintInlining`: `readLong` (169 bytes) and `readVarLongHigh` (315) inline hot; `readVarLongSlow` (450) is "too
     big" at its `readLong` call site, which every call reaches for its last varint (the block terminator is within
     10 bytes of the end); `decodeSerializableValue` is "already compiled into a big method" on both sides.
   - **Ruled out:** the terminator's slow path (a one-byte fast path near the end: 4.64M, unchanged); `readVarLongHigh`
     inlined at every site (`-XX:CompileCommand=dontinline` on it: 4.12M, unchanged); a polymorphic decoder call site
     (each side loads a single `AvroBinaryDecoder` subclass).
   - JMH's stack sampler only shows more time in `CollectionLikeSerializer.readAll` (the loop with `readLong`
     inlined: v2 25% vs head 20% + 5% in `Buffer.skip`).
   Next: per-instruction profile (async-profiler in `cpu` mode, or `-prof perfasm`-style with hsdis) on `readAll`.
2. **Short Latin-1 strings stay well below the pre-varint base** (16 chars: 3.158M then, 1.796M now; different days,
   indicative). ASCII and CJK short strings are now above that base. The string decision (threshold / revert / keep for
   C5, `notes/m4-strings-and-bytebuffer.md`) still stands, but its data moved: the `Buffer` was part of the short-string
   loss.
3. Streams (`decodeFromSource`, and `AvroSingleObject.decodeFromByteArray`, which wraps its bytes in a `Buffer`) still
   read through `KotlinxIoDecoder`.

Tests: `ByteArrayDecoderTest` (common, 13). `:core:jvmTest` 1045, jsNode 229, macosArm64 232.
