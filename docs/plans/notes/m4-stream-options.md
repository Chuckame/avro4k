# M4 — Stream input and output without kotlinx-io in core: the two implementations compared

**Status:** written 2026-10-25 for the user's choice on `m4-no-stream-library.md` §6 Q1 (they asked to see both
implementations before choosing). Q2 is answered: an internal avro4k `EOFException` (typealias to
`java.io.EOFException` on the JVM). The code below is a **sketch, not compiled** (written while the encoder A/B held
the machine); names and shapes are what a unit would build.

Both options start the same way: **merge `v3/one-cursor-decoder`** (`d4aae3bf`) and rename its `ArrayCursorDecoder`
to `BufferedDecoder` (user, 2026-10-24). A stream decoder is then only the subclass that says what follows the array.
Encoding needs nothing new in either option: `ByteArrayEncoder` writes the value, then its bytes go to the stream.

Neither is needed to drop kotlinx-io: option (a), nothing in v3.0, stays possible, and both can be added later without
breaking anything.

## At a glance

| | (b) JVM `InputStream`/`OutputStream` in core | (c) `avro4k-kotlinx-io` module |
|---|---|---|
| Platforms | JVM only | every platform core has |
| New dependency | none | kotlinx-io 0.x, in the new module only |
| Public API | 8 new functions (`encodeToStream`/`decodeFromStream` ×3 on `Avro`, 1 each on `AvroSingleObject`), JVM source set | today's 8 functions moved, same names and package; the 2 `AvroSingleObject` members become extensions |
| Internal API core must open | none (same module) | `BufferedDecoder` and the single-object header helpers become `public @InternalAvro4kApi` (in `core.api`) |
| New code | `InputStreamDecoder` (~70 lines), the 8 functions (~120 with KDoc) | a module (build script, publication, `apiDump`, CI); the files move almost unchanged |
| Tests | new: refill boundaries, rewind, end of input (with a stream returning 1 byte per `read`) | today's stream tests move with the code |
| Back-to-back values from one stream | exact when the stream supports `mark` (`BufferedInputStream`, `ByteArrayInputStream`), otherwise the bytes read ahead are lost (as v2's `decodeFromStream`) | exact, as today: the `Source` keeps what was read ahead |
| v2 users | v2's deprecated `encodeToStream`/`decodeFromStream` come back (with `AvroSchema`) | v2's `encodeToSink`/`decodeFromSource` keep working once the module is added |
| Risk | the `mark` limit: a value read across a refill moves the mark | a kotlinx-io break (its `@InternalIoApi` `source.buffer`, `UnsafeBufferOperations`) forces a release of the module |
| Speed (expected) | like today's stream path: an 8 KiB array refilled, read with a position | today's stream path, unchanged |

## (b) JVM streams in core

### Decoding: `core/src/jvmMain/.../internal/codec/InputStreamDecoder.kt` (new)

```kotlin
/**
 * Reads Avro binary from an [InputStream] through an array it refills (see [BufferedDecoder]).
 *
 * The array is filled ahead of the value being read. When the stream supports `mark`, [release] rewinds it and skips
 * exactly the bytes read, so that the stream is left right after the value; otherwise the bytes read ahead are lost.
 */
internal class InputStreamDecoder(
    private val input: InputStream,
) : BufferedDecoder(ByteArray(BUFFER_SIZE), 0, 0) {
    private val rewindable = input.markSupported()

    override fun nextArray() {
        if (rewindable) {
            input.mark(bytes.size)
        }
        val read = input.read(bytes, 0, bytes.size)
        if (read <= 0) {
            throw EOFException("The input ended")
        }
        position = 0
        limit = read
    }

    override fun readAcross(length: Int): ByteArray {
        val result = ByteArray(length)
        val head = limit - position
        bytes.copyInto(result, 0, position, limit)
        position = limit // nothing read ahead any more: the mark is not needed until the next refill
        if (input.readNBytes(result, head, length - head) < length - head) {
            throw EOFException("The input ended")
        }
        return result
    }

    override fun skipAcross(length: Long) {
        val head = limit - position
        position = limit
        skipExactly(length - head)
    }

    /** Leaves the stream right after the last value read, when it can be rewound. Called once decoding ends. */
    fun release() {
        if (rewindable && position < limit) {
            input.reset()
            skipExactly(position.toLong())
        }
        position = limit
    }

    // `InputStream.skipNBytes` is Java 12, core targets Java 11: `skip` may skip less, `read` tells the end of input
    private fun skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (input.read() == -1) {
                throw EOFException("The input ended")
            } else {
                left--
            }
        }
    }
}

private const val BUFFER_SIZE = 8 * 1024
```

### Encoding and the entry points: `core/src/jvmMain/.../AvroJvmStreams.kt` (new)

```kotlin
public fun <T> Avro.encodeToStream(writerSchema: AvroSchema, serializer: SerializationStrategy<T>, value: T, output: OutputStream) {
    val encoder = ByteArrayEncoder()
    encodeWithValidation(writerSchema, serializer, value, encoder)
    encoder.writeTo(output) // new on ByteArrayEncoder: output.write(bytes, 0, position), or each chunk
}

public fun <T> Avro.decodeFromStream(writerSchema: AvroSchema, deserializer: DeserializationStrategy<T>, input: InputStream): T {
    val decoder = InputStreamDecoder(input)
    try {
        return decodeWithValidation(writerSchema, deserializer, decoder)
    } finally {
        decoder.release()
    }
}

// + the two reified shapes of each, and AvroSingleObject.encodeToStream / decodeFromStream as JVM extensions
//   (same module: the header helpers stay private to core)
```

### Elsewhere
- `README.md` "JVM stream encoding" stays (switched to `InputStream`/`OutputStream`); "Encode many objects in the same
  stream" says to wrap the stream in a `BufferedInputStream` once.
- Off the JVM: `ByteArray` only.

## (c) An `avro4k-kotlinx-io` module

### The module

```
settings.gradle.kts                    include("kotlinx-io")
kotlinx-io/build.gradle.kts            the same multiplatform targets as core;
                                       commonMainApi(project(":core")), commonMainApi(libs.kotlinx.io)
kotlinx-io/api/*.api                   apiDump of the 8 functions
kotlinx-io/src/commonMain/kotlin/com/github/avrokotlin/avro4k/
    AvroKotlinxIoExtensions.kt         moved from core, unchanged (Avro.encodeToSink ×3, decodeFromSource ×3)
    AvroSingleObjectKotlinxIo.kt       encodeToSink / decodeFromSource: members of AvroSingleObject today, extensions here
    kotlinxio/internal/KotlinxIoDecoder.kt   from v3/one-cursor-decoder, extends core's BufferedDecoder
    kotlinxio/internal/KotlinxIoEncoder.kt   moved from core, unchanged
kotlinx-io/src/commonTest/             KotlinxIoCodecTest, the stream half of VarintTest, ReadBytesBufferBoundsTest (moved)
```

### What core opens to it

```kotlin
// core: from `internal abstract class` to
@InternalAvro4kApi
public abstract class BufferedDecoder(...) : AvroBinaryDecoder() { /* protected bytes, position, limit, nextArray, … */ }

// core, AvroSingleObject: the header, so that the module writes and reads it without duplicating the fingerprint cache
@InternalAvro4kApi public fun fingerprintOf(writerSchema: AvroSchema): ByteArray   // today the private crc64avro()
@InternalAvro4kApi public fun writerSchemaOf(fingerprint: Long): AvroSchema         // today the private writerSchema()
@InternalAvro4kApi public inline fun readHeader(readByte: () -> Byte): Long         // today private
```

`decodeWithValidation` and `encodeWithValidation` are already `public @InternalAvro4kApi` (for `apache-interop`).

### For users
- Same imports for the `Avro` functions; the `AvroSingleObject` ones need an import (member → extension); one more
  dependency, `com.github.avro-kotlin.avro4k:avro4k-kotlinx-io`.
- Migration item: "add the module" instead of "rewrite with `ByteArray`".

## What each changes in the rest of the plan

- **IO-3** keeps its scope (kotlinx-io out of core) in both; (b) adds the JVM stream unit after it, (c) moves the files
  into the module instead of deleting them.
- **IO-4** (the encoder A/B) is unaffected.
- `backlog-after-v3.0.md`'s new item "a stream entry point again" is done instead of deferred.
- The `readFromSource` benchmark rows: (b) become `readFromStream` (`ByteArrayInputStream`), (c) move to a benchmark
  dependency on the module and stay as they are.
