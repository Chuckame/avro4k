package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchema
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

/** The bytes themselves: there is no generic fixed type outside the JVM. */
internal actual fun genericFixedValue(
    schema: AvroSchema,
    bytes: ByteArray,
): Any = bytes