package com.github.avrokotlin.avro4k.internal.codec

import kotlinx.io.InternalIoApi
import kotlinx.io.Source
import kotlinx.io.UnsafeIoApi
import kotlinx.io.readByteArray
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Reads Avro binary from a kotlinx-io [Source], straight from the array of its head segment (see [ArrayCursorDecoder]).
 *
 * The head segment's array is taken once and read with a plain position: the bytes read are only consumed from the
 * [source] when the segment has been read to its end, or by [release]. A value that does not fit in what is left of the
 * segment is read across segments, through the [Source] API or byte by byte.
 *
 * So the [source] lags behind what has been read until [release] is called: nothing else may read the [source] in
 * between, and [release] must be called once decoding ends, failed or not, so that the [source] is left right after
 * the last value read.
 */
internal class KotlinxIoDecoder(
    private val source: Source,
) : ArrayCursorDecoder(EMPTY, 0, 0) {
    /**
     * The index of the head segment's first byte when it was taken: the bytes from there to [position] are read, but not
     * yet consumed from the [source].
     */
    private var start = 0

    /** Consumes from the [source] every byte read so far, and lets go of the head segment. */
    fun release() {
        val read = position - start
        bytes = EMPTY
        position = 0
        limit = 0
        start = 0
        if (read != 0) {
            source.skip(read.toLong())
        }
    }

    /** Consumes the segment read so far and takes the next one, throwing an EOFException at the end of the input. */
    @OptIn(InternalIoApi::class, UnsafeIoApi::class)
    override fun nextArray() {
        release()
        source.require(1L)
        UnsafeBufferOperations.readFromHead(source.buffer) { array, from, to ->
            bytes = array
            position = from
            limit = to
            start = from
            0
        }
    }

    override fun readAcross(length: Int): ByteArray {
        release()
        return source.readByteArray(length)
    }

    override fun skipAcross(length: Long) {
        release()
        source.skip(length)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}