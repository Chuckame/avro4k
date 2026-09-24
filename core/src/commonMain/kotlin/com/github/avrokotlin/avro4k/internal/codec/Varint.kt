package com.github.avrokotlin.avro4k.internal.codec

import kotlinx.serialization.SerializationException

/** The largest zig-zag varint encoding of an `int`: 32 bits, 7 per byte. */
internal const val MAX_VARINT_INT_BYTES = 5

/** The largest zig-zag varint encoding of a `long`: 64 bits, 7 per byte. */
internal const val MAX_VARINT_LONG_BYTES = 10

/**
 * The largest length accepted for a STRING or BYTES value: the largest array most runtimes can allocate (the JVM
 * reserves a few header words). A larger — or a negative — length can only come from corrupted or malicious input.
 */
internal const val MAX_BINARY_LENGTH: Int = Int.MAX_VALUE - 8

/**
 * Writes [value] as an Avro zig-zag varint into [bytes] at [offset], and returns the number of bytes written (1 to
 * [MAX_VARINT_INT_BYTES]). The caller guarantees that many bytes of room.
 *
 * Unrolled rather than looped, as Apache Avro's `BinaryData.encodeInt`, whose output it matches byte for byte.
 */
internal fun encodeZigZagInt(
    value: Int,
    bytes: ByteArray,
    offset: Int,
): Int {
    // move the sign to the low-order bit, and flip the others if negative
    var n = (value shl 1) xor (value shr 31)
    var pos = offset
    if (n and 0x7F.inv() != 0) {
        bytes[pos++] = (n or 0x80).toByte()
        n = n ushr 7
        if (n > 0x7F) {
            bytes[pos++] = (n or 0x80).toByte()
            n = n ushr 7
            if (n > 0x7F) {
                bytes[pos++] = (n or 0x80).toByte()
                n = n ushr 7
                if (n > 0x7F) {
                    bytes[pos++] = (n or 0x80).toByte()
                    n = n ushr 7
                }
            }
        }
    }
    bytes[pos++] = n.toByte()
    return pos - offset
}

/**
 * Writes [value] as an Avro zig-zag varint into [bytes] at [offset], and returns the number of bytes written (1 to
 * [MAX_VARINT_LONG_BYTES]). The caller guarantees that many bytes of room.
 *
 * Unrolled rather than looped, as Apache Avro's `BinaryData.encodeLong`, whose output it matches byte for byte.
 */
internal fun encodeZigZagLong(
    value: Long,
    bytes: ByteArray,
    offset: Int,
): Int {
    // move the sign to the low-order bit, and flip the others if negative
    var n = (value shl 1) xor (value shr 63)
    var pos = offset
    if (n and 0x7FL.inv() != 0L) {
        bytes[pos++] = (n or 0x80L).toByte()
        n = n ushr 7
        if (n > 0x7F) {
            bytes[pos++] = (n or 0x80L).toByte()
            n = n ushr 7
            if (n > 0x7F) {
                bytes[pos++] = (n or 0x80L).toByte()
                n = n ushr 7
                if (n > 0x7F) {
                    bytes[pos++] = (n or 0x80L).toByte()
                    n = n ushr 7
                    if (n > 0x7F) {
                        bytes[pos++] = (n or 0x80L).toByte()
                        n = n ushr 7
                        if (n > 0x7F) {
                            bytes[pos++] = (n or 0x80L).toByte()
                            n = n ushr 7
                            if (n > 0x7F) {
                                bytes[pos++] = (n or 0x80L).toByte()
                                n = n ushr 7
                                if (n > 0x7F) {
                                    bytes[pos++] = (n or 0x80L).toByte()
                                    n = n ushr 7
                                    if (n > 0x7F) {
                                        bytes[pos++] = (n or 0x80L).toByte()
                                        n = n ushr 7
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    bytes[pos++] = n.toByte()
    return pos - offset
}

/**
 * Checks the length prefix of a STRING or BYTES value before anything is allocated or skipped for it.
 *
 * Replaces the system-limit checks of Apache Avro's decoders: the `org.apache.avro.limits.*` system properties no
 * longer apply.
 */
internal fun checkBinaryLength(length: Long): Int {
    if (length < 0L) {
        throw SerializationException("Malformed data: negative length $length")
    }
    if (length > MAX_BINARY_LENGTH) {
        throw SerializationException("Cannot read $length bytes: the maximum length is $MAX_BINARY_LENGTH")
    }
    return length.toInt()
}