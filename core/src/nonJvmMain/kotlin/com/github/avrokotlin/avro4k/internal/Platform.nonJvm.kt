package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.internal.codec.encodeUtf8Into
import com.github.avrokotlin.avro4k.internal.codec.utf8Length
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
 * Encoded by the loop the encoders write every string with, rather than Kotlin's `encodeToByteArray()`, which writes
 * U+FFFD for an unpaired surrogate where the JVM writes `?`.
 */
internal actual fun String.encodeToUtf8(): ByteArray {
    val bytes = ByteArray(utf8Length())
    encodeUtf8Into(bytes, 0, 0, length)
    return bytes
}

internal actual open class EOFException actual constructor(message: String?) : Exception(message)