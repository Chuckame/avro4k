# C5 input — `fixed` and `bytes` values without the intermediate array

**Status:** proposal recorded on 2026-10-11 at the user's request, for M5's C5 ("string and bytes encode/decode without
intermediate arrays"). Nothing implemented. Part A is internal (no API change); part B adds public API and needs the
user's approval of its final shape before it is implemented.

## The copy today

All decoding input is a kotlinx-io `Source`: `decodeFromByteArray` wraps the array without copying it (one segment,
`UnsafeBufferOperations.moveToTail`), stream input comes in segments of about 8 KB. `AvroDecoder.decodeFixed()` and
`decodeBytes()` end in `source.readByteArray(n)`, one `n`-byte copy per value, which the built-in logical types then
convert and drop:

| logical type | today, per value |
|---|---|
| `decimal` on `bytes`/`fixed` (JVM) | `ByteArray(n)` → `BigDecimal(BigInteger(bytes), scale)` |
| `uuid` on `fixed(16)` | `ByteArray(16)` → `Uuid.fromByteArray` (common); on the JVM `java.util.UUID` also wraps it in a `ByteBuffer` |
| `duration` on `fixed(12)` | `ByteArray(12)` → three little-endian unsigned ints (months, days, milliseconds) |
| avro4k's `big-decimal` on `bytes` (JVM) | `ByteArray(n)`, then a second `KotlinxIoDecoder` over it to read the unscaled bytes and the scale |

The array is small (8–16 bytes) next to the `BigInteger`/`BigDecimal` objects built anyway, so the gain must be
measured: check first that a benchmark decodes decimals, UUIDs and durations, and add one if not.

**Rejected: returning a kotlinx-io `Buffer` "view"** (the user's first idea). (1) It is not a view for small values:
moving fewer than 1,024 bytes out of a buffer copies them into a pooled segment (`Segment.split`, `SHARE_MINIMUM =
1024` in kotlinx-io 0.9.1), so a 16-byte value would cost a segment, a copy and a `Buffer` — more than today. (2) A raw
view held after the decode is not just memory held too long: a segment consumed from a stream source is recycled to the
pool and reused, so the view's bytes would change silently; only shared copy-on-write segments (≥ 1,024 bytes) are
safe. (3) A `copy: Boolean` flag would put that risk on every caller of a public API.

## Part A — internal: the built-in serializers read the value directly (no API change)

The built-in serializers are avro4k's own, so they can read through internal paths:

- **`uuid` on `fixed(16)`:** two big-endian `readLong()` → `Uuid.fromLongs(msb, lsb)` / `UUID(msb, lsb)`: no array, no
  `ByteBuffer`.
- **`duration` on `fixed(12)`:** three `readUIntLe()` (months, days, milliseconds): no array.
- **`decimal` (JVM):** `UnsafeBufferOperations.readFromHead` exposes the head segment's array as `(bytes, start, end)`;
  when the value fits in the head segment (always for byte-array input, almost always for small values), build
  `BigInteger(bytes, off, len)` (JDK 9+) on it, no copy; otherwise fall back to the copy.
- **`big-decimal` (JVM):** read the unscaled bytes the same way and the scale with the decoder's own `readInt()`,
  without the second decoder.

Design points to settle when implementing:
- The paths must go through avro4k's codec ABI (`AvroBinaryDecoder`, internal) so that `validateSerialization` still
  checks them: e.g. a new internal codec method reading a fixed/bytes value through a callback (`readFixed(size) { source
  -> … }`), which the validating decoder wraps like `readFixed(size)`; the callback must consume exactly the value's bytes.
- The serializers receive the public `AvroDecoder`: take the fast path when it is avro4k's binary direct decoder (a type
  check), else fall back to `decodeFixed()`/`decodeBytes()` (the decoder of a missing field's default, apache-interop's
  generic trees, third-party decoders). Unions and record-field defaults must take the same route as today.
- Off the JVM, check that Kotlin/JS and Native have the same `readLong`/`readUIntLe` cost profile (no boxing).

## Part B — public: scoped access to the value's bytes (needs the user's approval)

For custom serializers, a scoped API instead of a returned view, so "don't keep the bytes" is enforced by its shape:

```kotlin
public inline fun <R> AvroDecoder.decodeFixed(read: (source: Source, size: Int) -> R): R   // shape to settle
public inline fun <R> AvroDecoder.decodeBytes(read: (source: Source, size: Int) -> R): R
```

The `Source` is only valid inside the block, and the block must consume exactly `size` bytes (the decoder checks it,
or skips what is left — to decide). Every `AvroDecoder` has to support it: avro4k's binary decoder hands its own source
(bounded); the default-value and generic decoders hand a `Buffer` wrapping their already-materialised bytes (zero-copy
`moveToTail`). Open points for the user: interface member vs extension (an interface member is a breaking addition for
third-party implementations, as `val avro` was), names (never rename `decodeFixed()`/`decodeBytes()`: these are
overloads), whether `size` is passed or the `Source` is bounded, and the experimental marker. The `ByteArray`-returning
`decodeFixed()`/`decodeBytes()` stay as they are, copying.
