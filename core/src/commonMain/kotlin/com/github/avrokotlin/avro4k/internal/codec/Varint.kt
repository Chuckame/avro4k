package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException

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
 * Replaces the system-limit checks of Apache Avro's decoders: Apache Avro's `limits` system properties no
 * longer apply.
 */
internal fun checkBinaryLength(length: Long): Int {
    if (length < 0L) {
        throw AvroMalformedDataException("Malformed data: negative length $length")
    }
    if (length > MAX_BINARY_LENGTH) {
        throw AvroMalformedDataException("Cannot read $length bytes: the maximum length is $MAX_BINARY_LENGTH")
    }
    return length.toInt()
}

/** Decodes the bytes of a varint int following its [first] byte, whose continuation bit is set. */
internal inline fun decodeVarIntTail(
    first: Int,
    readByte: () -> Byte,
): Int {
    var b = readByte().toInt()
    var varint = (first and 0x7F) + ((b and 0x7F) shl 7)
    if (b < 0) {
        b = readByte().toInt()
        varint += ((b and 0x7F) shl 14)
        if (b < 0) {
            b = readByte().toInt()
            varint += ((b and 0x7F) shl 21)
            if (b < 0) {
                b = readByte().toInt()
                if (b < 0) {
                    throw AvroMalformedDataException("Invalid varint encoding: more bytes than expected")
                }
                varint += (b shl 28)
            }
        }
    }
    return decodeZigZag(varint)
}

/** Decodes the bytes of a varint long following its [first] byte, whose continuation bit is set. */
internal inline fun decodeVarLongTail(
    first: Int,
    readByte: () -> Byte,
): Long {
    var b: Int = readByte().toInt()
    var i = (first and 0x7F) + ((b and 0x7F) shl 7)
    if (b < 0) {
        b = readByte().toInt()
        i += ((b and 0x7F) shl 14)
        if (b < 0) {
            b = readByte().toInt()
            i += ((b and 0x7F) shl 21)
            if (b < 0) {
                return decodeVarLong2(i.toLong(), readByte)
            }
        }
    }
    return decodeZigZag(i).toLong()
}

internal inline fun decodeVarLong2(
    lo: Long,
    readByte: () -> Byte,
): Long {
    // then next 28 bits (altogether 8 bytes)
    var lo = lo
    var b: Int = readByte().toInt()
    var i = b and 0x7F
    if (b < 0) {
        i = i and 0x7F
        b = readByte().toInt()
        i += ((b and 0x7F) shl 7)
        if (b < 0) {
            b = readByte().toInt()
            i += ((b and 0x7F) shl 14)
            if (b < 0) {
                b = readByte().toInt()
                i += ((b and 0x7F) shl 21)
                if (b < 0) {
                    // Ok 56-bits gone... still going strong!
                    lo = lo or ((i.toLong()) shl 28)
                    b = readByte().toInt()
                    i = b and 0x7F
                    if (b < 0) {
                        b = readByte().toInt()
                        if (b < 0) {
                            throw AvroMalformedDataException("Invalid varlong encoding: more bytes than expected")
                        }
                        i = i or (b shl 7)
                    }
                    lo = lo or ((i.toLong()) shl 56)
                    return decodeZigZag(lo)
                }
            }
        }
    }
    lo = lo or ((i.toLong()) shl 28)
    return decodeZigZag(lo)
}

internal fun decodeZigZag(varint: Int): Int = (varint ushr 1) xor (-(varint and 1))

internal fun decodeZigZag(varint: Long): Long = (varint ushr 1) xor (-(varint and 1))