package com.github.avrokotlin.avro4k.internal.codec

import kotlinx.io.EOFException

/**
 * Reads Avro binary straight from a [ByteArray], from its [startIndex] on, with nothing between the decoder and the bytes
 * but a position (see [BufferedDecoder]).
 *
 * The array is the whole input: a value that does not fit in what is left of it is an EOFException.
 */
internal class ByteArrayDecoder(
    bytes: ByteArray,
    startIndex: Int = 0,
) : BufferedDecoder(bytes, startIndex, bytes.size) {
    /** Whether every byte of the input has been read. */
    val isExhausted: Boolean
        get() = position == limit

    override fun nextArray() {
        throw EOFException("The input ended in the middle of a value")
    }

    override fun readAcross(length: Int): ByteArray {
        throw EOFException("$length bytes expected, only ${limit - position} remaining")
    }

    override fun skipAcross(length: Long) {
        throw EOFException("$length bytes expected, only ${limit - position} remaining")
    }
}