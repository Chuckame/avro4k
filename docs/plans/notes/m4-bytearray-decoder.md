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

1. **Closed (2026-10-18): `CollectionMicroBenchmark.readLongs` @10 is multimodal on v2, not slower.** The A/B had it
   below head (v1 −28.5%, v2 −12.9%, separated). Single-fork probes on 2026-10-18, v2 (`a28a84f5`) against head
   (`fe8debc1`), size 10 only:
   - head, 4 forks: 5.39M / 5.28M / 5.29M / 5.32M ops/s, steady (mean 5.32M).
   - v2, 4 forks: 4.67M / 5.17M / 4.92M / 4.73M (mean 4.87M); 5 more forks with `PrintInlining`: **4.0M / 5.6M / 6.65M /
     7.07M / 6.1M**; 1 fork with JFR: 5.41M. Each fork steady within itself, the mode is chosen per fork.
   - `PrintInlining` of the slowest fork (4.0M) against the fastest (7.07M): the difference is C2's compilation order.
     In the fast fork `CollectionLikeSerializer.readAll` is compiled on its own first, with `readLong` and
     `readVarLongHigh` inlined into its loop, and its callers then find it "already compiled into a big method". In the
     slow fork `readAll` is inlined into a larger caller compilation, where `decodeSerializableValue` hits "recursive
     inlining is too deep".
   - So @10 measures which compilation order a fork gets, not the varint code. The fixed cost of one call is the
     same on both sides (same `decodeWithValidation` path, 536 vs 600 B/op). Head's single decoder path happens to land
     in one mode. Not acted on: the larger rows (@1000 +11%/+60%) and every end-to-end read row are faster.
   Earlier probes (2026-10-17) ruled out the terminator's slow path, `readVarLongHigh` inlining and a polymorphic call
   site.
2. **Short Latin-1 strings stay well below the pre-varint base** (16 chars: 3.158M then, 1.796M now; different days,
   indicative). ASCII and CJK short strings are now above that base. The string decision (threshold / revert / keep for
   C5, `notes/m4-strings-and-bytebuffer.md`) still stands, but its data moved: the `Buffer` was part of the short-string
   loss.
3. Streams (`decodeFromSource`, and `AvroSingleObject.decodeFromByteArray`, which wraps its bytes in a `Buffer`) still
   read through `KotlinxIoDecoder`.

Tests: `ByteArrayDecoderTest` (common, 13). `:core:jvmTest` 1045, jsNode 229, macosArm64 232.
