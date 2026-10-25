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