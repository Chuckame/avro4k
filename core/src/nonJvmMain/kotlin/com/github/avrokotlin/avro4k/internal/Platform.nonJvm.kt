package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.serializer.AnyTypeSerializersModule
import com.github.avrokotlin.avro4k.serializer.KotlinInstantSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinStdLibSerializersModule
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_TIMESTAMP_MICROS
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

// What the code shared by every platform gets from JS and native: only the Kotlin standard library types.

/**
 * The serializers module of [com.github.avrokotlin.avro4k.Avro.Default]: the serializers of the Kotlin standard library
 * types and [com.github.avrokotlin.avro4k.serializer.AnySerializer].
 */
internal actual fun defaultSerializersModule(): SerializersModule = KotlinStdLibSerializersModule + AnyTypeSerializersModule

/** The logical types decoded to Kotlin types: `timestamp-millis` and `timestamp-micros` as `kotlin.time.Instant`. */
internal actual fun platformLogicalTypes(): Map<String, KSerializer<out Any>> =
    mapOf(
        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS to KotlinInstantSerializer,
        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS to KotlinInstantSerializer
    )

/**
 * Kotlin's `encodeToByteArray()` when the string has no unpaired surrogate, the usual case: the bytes are then the same
 * as the JVM's. Otherwise, encoded here, with `?` for each unpaired surrogate.
 */
internal actual fun String.encodeToUtf8(): ByteArray {
    val length = length
    var size = 0
    var unpairedSurrogate = false
    var i = 0
    while (i < length) {
        val c = this[i++].code
        size +=
            when {
                c < 0x80 -> 1

                c < 0x800 -> 2

                c < 0xd800 || c > 0xdfff -> 3

                c <= 0xdbff && i < length && this[i].code in 0xdc00..0xdfff -> {
                    i++
                    4
                }

                else -> {
                    unpairedSurrogate = true
                    1
                }
            }
    }
    if (!unpairedSurrogate) return encodeToByteArray()

    val bytes = ByteArray(size)
    var pos = 0
    i = 0
    while (i < length) {
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
        } else if (c <= 0xdbff && i < length && this[i].code in 0xdc00..0xdfff) {
            val codePoint = 0x10000 + ((c - 0xd800) shl 10) + (this[i++].code - 0xdc00)
            bytes[pos++] = (0xf0 or (codePoint shr 18)).toByte()
            bytes[pos++] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
            bytes[pos++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
            bytes[pos++] = (0x80 or (codePoint and 0x3f)).toByte()
        } else {
            bytes[pos++] = '?'.code.toByte()
        }
    }
    return bytes
}