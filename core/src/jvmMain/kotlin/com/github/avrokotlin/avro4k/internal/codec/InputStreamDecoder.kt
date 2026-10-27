package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import java.io.EOFException
import java.io.InputStream

/**
 * Reads Avro binary from an [InputStream] through an array it refills as it decodes (see [BufferedDecoder]), never the
 * whole input at once.
 *
 * The array starts at the size the stream says it holds, so that a small value is not decoded through a large array
 * allocated for it, and doubles on each refill that filled it, up to [MAX_BUFFER_SIZE].
 *
 * The array is filled ahead of the value being read, and the stream cannot give those bytes back: the stream holds a
 * single value, and [requireEnd] checks that nothing follows it, as kotlinx-serialization-json's `decodeFromStream`
 * does. The stream is neither rewound nor closed.
 */
internal class InputStreamDecoder(
    private val input: InputStream,
) : BufferedDecoder(ByteArray(input.available().coerceIn(MIN_BUFFER_SIZE, MAX_BUFFER_SIZE)), 0, 0) {
    /** Throws unless every byte of the stream has been read. */
    fun requireEnd() {
        if (position < limit || input.read() != -1) {
            throw AvroMalformedDataException("Not all bytes were consumed during deserialization")
        }
    }

    override fun nextArray() {
        if (limit == bytes.size && bytes.size < MAX_BUFFER_SIZE) {
            bytes = ByteArray(minOf(bytes.size shl 1, MAX_BUFFER_SIZE))
        }
        var read: Int
        // `read` only returns 0 when asked for no byte; looping keeps a stream that does it anyway from being read as empty
        do {
            read = input.read(bytes, 0, bytes.size)
        } while (read == 0)
        if (read < 0) {
            throw EOFException("The input ended in the middle of a value")
        }
        position = 0
        limit = read
    }

    override fun readAcross(length: Int): ByteArray {
        val head = limit - position
        val missing = length - head
        val result: ByteArray
        if (missing <= MAX_LENGTH_ALLOCATED_AHEAD) {
            result = ByteArray(length)
            bytes.copyInto(result, 0, position, limit)
            position = limit
            if (input.readNBytes(result, head, missing) < missing) {
                throw endOfInput(length.toLong())
            }
        } else {
            // Read before allocating the whole value: a corrupt length must not allocate far beyond what the stream holds
            val tail = input.readNBytes(missing)
            if (tail.size < missing) {
                throw endOfInput(length.toLong())
            }
            result = ByteArray(length)
            bytes.copyInto(result, 0, position, limit)
            position = limit
            tail.copyInto(result, head)
        }
        return result
    }

    override fun skipAcross(length: Long) {
        val head = limit - position
        position = limit
        skipExactly(length - head, length)
    }

    // `InputStream.skipNBytes` is Java 12 and core targets Java 11: `skip` may skip less, and only `read` tells the end
    private fun skipExactly(
        count: Long,
        length: Long,
    ) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (input.read() == -1) {
                throw endOfInput(length)
            } else {
                left--
            }
        }
    }

    private fun endOfInput(length: Long): EOFException = EOFException("$length bytes expected, the input ended before")
}

/** The smallest array refilled from the stream, whatever it says it holds: a typical record or more. */
private const val MIN_BUFFER_SIZE = 512

/** The largest array refilled from the stream: large enough for most values to be decoded without crossing a refill. */
private const val MAX_BUFFER_SIZE = 8 * 1024

/** Beyond this, the bytes of a value are read before its array is allocated. */
private const val MAX_LENGTH_ALLOCATED_AHEAD = 1024 * 1024