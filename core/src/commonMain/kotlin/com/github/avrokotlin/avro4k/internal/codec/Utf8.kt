package com.github.avrokotlin.avro4k.internal.codec

/**
 * Decodes the UTF-8 bytes of a string, from [startIndex] to [endIndex] of this array. Every decoder decodes its strings
 * through here: from its input's own array when it holds the whole string, from a copy otherwise.
 *
 * It is the standard library's [decodeToString]. On the JVM, that is the JDK's decoder, as with Apache Avro: it checks for
 * ASCII with an intrinsic and builds the string straight from the bytes, where a decoder filling a `CharArray` byte by
 * byte is several times slower on ASCII, the most common content, and allocates twice as much.
 *
 * Invalid UTF-8 is replaced as the platform's [decodeToString] does it: an encoded surrogate (`ED A0 80`) gives one
 * replacement character on the JVM, three on JS and native.
 */
internal fun ByteArray.decodeUtf8(
    startIndex: Int = 0,
    endIndex: Int = size,
): String = decodeToString(startIndex, endIndex)

/**
 * The number of bytes [encodeUtf8Into] writes for this whole string, as the JVM's `String.getBytes(UTF_8)` counts them:
 * 1 byte below U+0080, 2 below U+0800, 3 for the rest of the basic plane, 4 for a surrogate pair, and 1 for an unpaired
 * surrogate, written as `?`.
 *
 * @throws IllegalStateException when that is more than [MAX_BINARY_LENGTH] bytes: they could not be held in an array,
 * nor read back by any decoder.
 */
internal fun String.utf8Length(): Int =
    // 3 bytes per char at most, so the count of a shorter string fits in an int
    if (length <= MAX_UTF8_SLICE_LENGTH) utf8Length(0, length) else utf8LengthOfHugeString()

/** Counted by slices short enough for each count to fit in an int, a surrogate pair never split between two. */
private fun String.utf8LengthOfHugeString(): Int {
    var size = 0L
    var start = 0
    while (start < length) {
        var end = if (length - start <= MAX_UTF8_SLICE_LENGTH) length else start + MAX_UTF8_SLICE_LENGTH
        if (end < length && this[end - 1].isHighSurrogate()) end++
        size += utf8Length(start, end)
        if (size > MAX_BINARY_LENGTH) {
            throw IllegalStateException("The string is too long to be written: more than $MAX_BINARY_LENGTH bytes")
        }
        start = end
    }
    return size.toInt()
}

/** At most this many chars, plus one to keep a surrogate pair whole, have a UTF-8 length that fits in an int. */
private const val MAX_UTF8_SLICE_LENGTH = Int.MAX_VALUE / 3 - 1

private fun String.utf8Length(
    startIndex: Int,
    endIndex: Int,
): Int {
    var size = 0
    var i = startIndex
    while (i < endIndex) {
        val c = this[i++].code
        size +=
            when {
                c < 0x80 -> 1

                c < 0x800 -> 2

                c < 0xd800 || c > 0xdfff -> 3

                c <= 0xdbff && i < endIndex && this[i].code in 0xdc00..0xdfff -> {
                    i++
                    4
                }

                else -> 1
            }
    }
    return size
}

/**
 * Writes the UTF-8 bytes of the chars of this string from [startIndex] to [endIndex] into [bytes] from [offset], and
 * returns the offset after the last byte written. Every string the encoders write goes through here, and on JS and native
 * every string encoded to an array by [com.github.avrokotlin.avro4k.internal.encodeToUtf8] too: there is one UTF-8 encoder
 * in avro4k, matching the JVM's `String.getBytes(UTF_8)` byte for byte.
 *
 * The bytes are those counted by [utf8Length], so at most 3 per char: the caller guarantees that much room. An unpaired
 * surrogate is written as `?` (0x3F), as the JVM does; a surrogate pair must not be split between two calls, or each
 * half would be written as an unpaired one.
 */
internal fun String.encodeUtf8Into(
    bytes: ByteArray,
    offset: Int,
    startIndex: Int,
    endIndex: Int,
): Int {
    var pos = offset
    var i = startIndex
    // ASCII, the most common content, in a loop small enough to be inlined into the encoders; any other char, out of line
    while (i < endIndex) {
        val c = this[i].code
        if (c >= 0x80) return encodeUtf8From(bytes, pos, i, endIndex)
        bytes[pos++] = c.toByte()
        i++
    }
    return pos
}

/** [encodeUtf8Into] from the first char that is not ASCII. */
private fun String.encodeUtf8From(
    bytes: ByteArray,
    offset: Int,
    startIndex: Int,
    endIndex: Int,
): Int {
    var pos = offset
    var i = startIndex
    while (i < endIndex) {
        val c = this[i++].code
        if (c < 0x80) {
            bytes[pos++] = c.toByte()
        } else if (c < 0x800) {
            bytes[pos++] = (0xc0 or (c shr 6)).toByte()
            bytes[pos++] = (0x80 or (c and 0x3f)).toByte()
        } else if (c < 0xd800 || c > 0xdfff) {
            bytes[pos++] = (0xe0 or (c shr 12)).toByte()
            bytes[pos++] = (0x80 or ((c shr 6) and 0x3f)).toByte()
            bytes[pos++] = (0x80 or (c and 0x3f)).toByte()
        } else if (c <= 0xdbff && i < endIndex && this[i].code in 0xdc00..0xdfff) {
            val codePoint = 0x10000 + ((c - 0xd800) shl 10) + (this[i++].code - 0xdc00)
            bytes[pos++] = (0xf0 or (codePoint shr 18)).toByte()
            bytes[pos++] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
            bytes[pos++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
            bytes[pos++] = (0x80 or (codePoint and 0x3f)).toByte()
        } else {
            bytes[pos++] = '?'.code.toByte()
        }
    }
    return pos
}