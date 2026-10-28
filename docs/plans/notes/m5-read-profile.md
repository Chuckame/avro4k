# M5-12 — CPU profile of a `complex` read, avro4k and Apache `SpecificData`

2026-10-28, orchestrator. Head `0fbb6cd8` (M5-01, M5-07 and M5-02 merged — so M5-01's and M5-02's own costs are already
out of the picture, one batch later than the decomposition planned). AC power, Low Power Mode off, load < 3 at each start
(2.94 → 2.35), under `caffeinate -dimsu`, nothing else running. **A profile, not a measurement of record:** no number
here goes into `benchmark/README.md`.

## How

- async-profiler 4.6 (Homebrew), `event=cpu,interval=1ms`, `-XX:+DebugNonSafepoints`, JBR 21.0.11, one run per output
  format (collapsed stacks, flame graph).
- `ManualProfilingRead` (avro4k `decodeFromByteArray`) and the new `ManualProfilingReadApacheSpecific` (Apache 1.12.2
  `SpecificDatumReader`, fast reader on — the `ApacheAvroSpecificBenchmark.read` row), 1,000,000 reads of the seeded
  `complex` payload; both now take the client count as their first argument.
- Only samples under the loop's `main` are counted. 1 sample = 1 ms of CPU.
- @15: avro4k 29,470 samples, Apache 22,946 (ratio 0.78, in line with IO-9's @15 read gap). @1 runs last ~3 s and are
  mostly warm-up; read for confirmation only.
- Scripts and raw output: this session's scratchpad (`m5-12/`: `run.sh`, `analyze.py`, `categorize.py`, `out/`), not
  committed.

## Where the time goes, @15 (self time, each sample charged to its leaf frame)

| Category | avro4k | Apache |
|---|---:|---:|
| UTF-8 → `String` | 2,866 ms | 4,964 ms |
| bytes copied (`copyOfRange`, arraycopy) | 2,532 | 3,475 |
| varint and number reads | 1,549 | 1,975 |
| **data work, subtotal** | **6,947** | **10,414** |
| map building (`HashMap`, `String.hashCode`) | 2,680 | 1,713 |
| dispatch stubs (itable/vtable) | 3,068 | 1,987 |
| avro4k decoder: record field stepping | 2,667 | — |
| avro4k decoder: block decoders (`beginElement`, schema) | 1,912 | — |
| avro4k decoder: nullable/union | 1,523 | — |
| avro4k decoder: other | 1,326 | — |
| avro4k decoder: construction/reset/begin | 856 | — |
| avro4k: serializer lookup (`SerializerLocatorMiddleware`) | 824 | — |
| kotlinx collection serializers | 1,947 | — |
| `ArrayList` and other collections | 793 | 122 |
| Kotlin intrinsics/stdlib | 1,441 | 139 |
| generated serializers, model constructors | 1,095 | 407 |
| Apache readers (`FastReaderBuilder` lambdas, setters) | — | 5,413 |
| GC, allocation, VM | 642 | 1,134 |
| other | 1,655 | 1,616 |
| **total** | **29,470** | **22,946** |

**The read gap is entirely in the structure layer.** avro4k's data work is a third cheaper than Apache's (strings
straight from the array with `decodeToString`, against Apache's `Utf8` then `toString`; fewer copies). Everything that
walks the structure — kotlinx's generated and collection serializers, avro4k's decoders, the dispatch between them —
costs ≈ 17 s against ≈ 8 s for Apache's prebuilt reader lambdas.

## The frames the remaining read units would remove (@15, self)

| Frame | ms | % | Removed by |
|---|---:|---:|---|
| `getCurrentAvroSchema` (abstract property, 4 implementations) | 980 | 3.3 | new: a field in the base class |
| stubs whose caller is `decodeAndResolveUnion` | 305 | 1.0 | same |
| `decodeAndResolveUnion` | 1,001 | 3.4 | same, plus a short path for `["null", T]` |
| `decodeNullableSerializableElement` + `NullableSerializer.deserialize` + `decodeNotNullMark` | 522 | 1.8 | M5-13 as first written |
| `RecordDirectDecoder.decodeElementIndex` | 856 | 2.9 | M5-03 |
| `RecordDirectDecoder.decode*` step checks | 241 | 0.8 | M5-03 |
| `decodeSerializableElement` + `decodeSerializableValue` | 1,480 | 5.0 | partly M5-03 (the step check), the rest stays |
| `beginElement` (block decoders) | 1,055 | 3.6 | — (per item, needed) |
| `SerializerLocatorMiddleware.interceptedSerializer` | 824 | 2.8 | — (≈1.8 ns per value, the measured cheapest form) |
| `MapLikeSerializer.readElement` → `HashMap.containsKey` | 1,203 | 4.1 | — (kotlinx's duplicate-key check, per map entry) |
| `Intrinsics.checkNotNull*` | 382 | 1.3 | — |

@1 shows the same shape (`decodeElementIndex` 3.0%, `decodeAndResolveUnion` 2.8%, getters 2.0%, `beginElement` 3.3%).

## Dispatch

- avro4k 10.4% in stubs, Apache 8.7% (its reader is a tree of lambdas, as megamorphic as kotlinx's serializers).
- avro4k's itable stubs: 2.8% at `decodeSerializableValue` → `deserialize` (serializer-side megamorphism, which no
  decoder design removes), 1.3% at `StringSerializer.deserialize` → `decodeString` and 1.0% at the generated
  serializer's `decodeNullableSerializableElement` (decoder-side). vtable: 1.4% at `decodeSerializableElement`, 1.0% at
  the `currentAvroSchema` getter.
- **Decoder-side megamorphism is ≈ 4–5%**, ≈ 1% of which goes with the getter lever below. This is the evidence §3's
  "when to revisit the frame stack" asks for: measurable but small, and below the getter + union lever. Recorded for
  Q5; nothing is decided here.

## What it points to

1. **M5-03 (exact-match records in order) — keep, smaller than hoped.** Its frames are ≈ 4–6% of a `complex` read
   (`decodeElementIndex` 2.9%, step checks 0.8%, part of `decodeSerializableElement`). Aim it at those; do not expect
   the read gap to close with it.
2. **M5-13 — scheduled, re-aimed.** The nullable-only short path is worth ≈ 1.8%; the per-value schema access around
   it is worth ≈ 7.7%: `currentAvroSchema` is an abstract property read through a virtual call on every value
   (`decodeAndResolveUnion` reads it first on every `decode*` and `decodeSerializableValue`). Re-scoped as: the current
   schema a plain field of `AbstractAvroDirectDecoder` (each subclass assigns it instead of overriding a property; the
   record decoder's throwing "no field begun" check moves to the rare path), then the `["null", T]` short path of the
   original design on top. Same files as before (`RecordResolver.kt`, the direct decoders), still after M5-04.
3. **Not in M5, for `backlog-after-v3.0.md`:**
   - kotlinx's map deserializer checks `containsKey` on every entry (4.1%): avro4k could build maps itself when the
     map serializer is kotlinx's own `LinkedHashMapSerializer`/`HashMapSerializer` (one more interception in
     `decodeCollectionBlocks`).
   - `Intrinsics.checkNotNull*` (1.3–1.9%): parameter null checks on internal hot paths.
   - kotlinx collection serializers + `ArrayList` (9.3%): already item 11 (collections of primitives in one loop).
4. **Strings and bytes need nothing more** on the JVM read side: already cheaper than Apache's.
