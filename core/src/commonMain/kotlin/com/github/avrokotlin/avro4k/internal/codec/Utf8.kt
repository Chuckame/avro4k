package com.github.avrokotlin.avro4k.internal.codec

/**
 * Decodes the UTF-8 bytes of a string, from [startIndex] to [endIndex] of this array. Every decoder decodes its strings
 * through here: from its input's own array when it holds the whole string, from a copy otherwise.
 *
 * It is the standard library's [decodeToString], never kotlinx-io's `Source.readString()`. On the JVM, [decodeToString]
 * is the JDK's decoder, as with Apache Avro: it checks for ASCII with an intrinsic and builds the string straight from
 * the bytes. kotlinx-io's decoder fills a `CharArray` byte by byte and compacts it back into a string: several times
 * slower on ASCII, the most common content, and twice the allocation (https://github.com/Kotlin/kotlinx-io/issues/515).
 * Once kotlinx-io decodes UTF-8 with the JDK on the JVM (https://github.com/Kotlin/kotlinx-io/pull/516),
 * `Source.readString()` is an option again; measure it on short non-ASCII strings, where the JDK's decoder is slower.
 *
 * Invalid UTF-8 is replaced as the platform's [decodeToString] does it: an encoded surrogate (`ED A0 80`) gives one
 * replacement character on the JVM, three on JS and native.
 */
internal fun ByteArray.decodeUtf8(
    startIndex: Int = 0,
    endIndex: Int = size,
): String = decodeToString(startIndex, endIndex)