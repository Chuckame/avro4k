package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.serializer.AnyTypeSerializersModule
import com.github.avrokotlin.avro4k.serializer.BigDecimalSerializer
import com.github.avrokotlin.avro4k.serializer.InstantSerializer
import com.github.avrokotlin.avro4k.serializer.JavaStdLibSerializersModule
import com.github.avrokotlin.avro4k.serializer.JavaTimeSerializersModule
import com.github.avrokotlin.avro4k.serializer.KotlinStdLibSerializersModule
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_DATE
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_TIMESTAMP_MICROS
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_TIME_MICROS
import com.github.avrokotlin.avro4k.serializer.LOGICAL_TYPE_NAME_TIME_MILLIS
import com.github.avrokotlin.avro4k.serializer.LocalDateSerializer
import com.github.avrokotlin.avro4k.serializer.LocalTimeSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

// What the code shared by every platform gets from the platform it runs on. On the JVM: the java.time, java.util,
// java.math and java.net serializers next to the Kotlin ones.

/**
 * The serializers module of [com.github.avrokotlin.avro4k.Avro.Default]: the serializers of the Java standard library
 * types, of the java.time types, of the Kotlin standard library types, and [com.github.avrokotlin.avro4k.serializer.AnySerializer].
 */
internal actual fun defaultSerializersModule(): SerializersModule =
    JavaStdLibSerializersModule +
        JavaTimeSerializersModule +
        KotlinStdLibSerializersModule +
        AnyTypeSerializersModule

/**
 * The logical types decoded to Java types: the date, time and timestamp logical types as java.time types, and
 * `decimal` and `big-decimal` as `BigDecimal`.
 */
internal actual fun platformLogicalTypes(): Map<String, KSerializer<out Any>> =
    mapOf(
        BigDecimalSerializer.DECIMAL_LOGICAL_TYPE_NAME to BigDecimalSerializer,
        BigDecimalSerializer.BIG_DECIMAL_LOGICAL_TYPE_NAME to BigDecimalSerializer,
        LOGICAL_TYPE_NAME_DATE to LocalDateSerializer,
        LOGICAL_TYPE_NAME_TIME_MILLIS to LocalTimeSerializer,
        LOGICAL_TYPE_NAME_TIME_MICROS to LocalTimeSerializer,
        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS to InstantSerializer,
        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS to InstantSerializer
    )

/** `String.getBytes(UTF_8)`, the reference the other platforms match. */
internal actual fun String.encodeToUtf8(): ByteArray = encodeToByteArray()