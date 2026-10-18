# M4 — A stream decoded from its head segment's array

**Outcome:** done on 2026-10-18. `decodeFromSource` reads the head segment's array with a plain position: stream
reads +12% to +48% end to end (separated on every row but the noisy `simple` @1, +16.5%), `ByteArray` reads unchanged.
Internal code only, no API change. Numbers: `benchmark/README.md` → "Results — a stream read from its head segment's
array".

## Why

`Avro.decodeFromByteArray` reads through `ByteArrayDecoder` (a plain array and position, `m4-bytearray-decoder.md`),
but `decodeFromSource` still read every value through the kotlinx-io `Source` API, which updates the buffer's segment
bookkeeping on every read (the varint read peeked the head segment, then `skip`ped what it had decoded).

## What changed (`v3/stream-cursor`, `79621284`)

- **`KotlinxIoDecoder`** takes the head segment's array once (`UnsafeBufferOperations.readFromHead`, consuming
  nothing) and reads it with a plain position, with `ByteArrayDecoder`'s Apache-shaped `readInt`/`readLong`. The bytes
  read are consumed from the source (`skip`) only when the segment has been read to its end, or by `release()`. A value
  that does not fit in what is left of the segment is read byte by byte across segments (varints, floats, doubles,
  booleans) or through the `Source` API after a `release()` (strings, bytes, fixed, skips).
- **`decodeFromSource` calls `release()` in a `finally`**, so the source is left right after the value, failed or not,
  as before: values decoded one after the other from one source still each stop after their own bytes (new test).
- `AvroSingleObject.decodeFromByteArray` reads its header from the array and decodes through
  `ByteArrayDecoder(bytes, 10)` instead of copying the input into a `Buffer`; the header parsing is shared with
  `decodeFromSource` (the fingerprint read byte by byte, little-endian). Same errors as before, same lack of a check
  for trailing bytes.
- The big-decimal bytes serializer (`JavaStdLibSerializers`) reads through `ByteArrayDecoder` (`isExhausted` for the
  trailing-bytes check) instead of a `Buffer` copy.
- Internal code only: no API change, `apiCheck` green.

**The contract this adds:** while a `KotlinxIoDecoder` is in use, the source lags behind what has been read, and nothing
else may read it until `release()`. Only `decodeFromSource` creates one outside tests. Tests that inspect the source
after reading with the decoder now call `release()` first.

## Tests

`KotlinxIoCodecTest` +3: every kind of value split across two segments at every split position; reading after a
`release()`; three values decoded one after the other from one source through `decodeFromSource`.
`:core:jvmTest` 1045 → 1048, jsNode 229 → 232, macosArm64 232 → 235; apache-interop, Confluent, kotlin-generator and
`apiCheck` green.

## Measurement

New benchmark rows (`8920310f`, committed alone so both sides run it): `readFromSource` in each end-to-end avro4k class,
decoding the same bytes as `read` from `ByteArrayInputStream(data).asSource().buffered()`. No row read from a `Source`
before.

A/B 2026-10-18 01:27 → 02:35, base `30c10eae` vs new `79621284`, avro4k reads only, 5 alternated invocations per side,
the unchanged `read` rows as control (all within noise, none separated). `readFromSource`: `simple` +16.5% (n) / +46.2% /
+47.7%, `complex` +11.7% / +22.8% / +19.2%, `lists` +24.9% / +20.2%. B/op +16 on every stream row.

## Open points

1. ~~**`lists` @10000 and `complex` from a stream stay 8–11% below the same bytes from a `ByteArray`.**~~ **Closed
   (2026-10-18): a benchmark setup effect, not the decoder.** The benchmark's setup decodes the payload once through
   `decodeFromByteArray`, so in a `readFromSource` fork every call into the decoder has seen two classes. With the setup
   decoding through a stream, the gap reverses (`read` 76.5, `readFromSource` 82.9 ops/s @10000); with setup and
   measure on the same decoder, the stream is 2.4% below, about the cost of building the stream and copying the
   payload through it (`drainSource`: 0.23 ms of 11.8 ms). A guard on the array's length too, so the JIT could drop
   the bounds checks, changed nothing (rejected). Numbers: `benchmark/README.md` → "Results — why a large stream read
   looked slower than a `ByteArray` read". Possible follow-up, not done: have the setup decode through the path the
   fork measures (JMH `BenchmarkParams` in `@Setup`), so the `readFromSource` rows are clean at large sizes; it would
   shift those rows' numbers.
2. The trailing bytes after a single object read from a `ByteArray` are still not checked (as before; the `ByteArray`
   overload of `Avro` does check them). Unchanged behaviour, not a migration item.
