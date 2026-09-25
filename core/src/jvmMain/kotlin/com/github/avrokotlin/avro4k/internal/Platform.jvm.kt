package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.codec.validating
import com.github.avrokotlin.avro4k.serializer.AnyTypeSerializersModule
import com.github.avrokotlin.avro4k.serializer.AvroDurationSerializer
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
import com.github.avrokotlin.avro4k.serializer.UUIDSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import org.apache.avro.generic.GenericData

// What the code shared by every platform gets from the platform it runs on. On the JVM: the java.time, java.util,
// java.math and java.net serializers next to the Kotlin ones, and Apache's validating encoder and decoder.

/**
 * The serializers module of [com.github.avrokotlin.avro4k.Avro.Default]: the serializers of the Java standard library
 * types, of the java.time types, of the Kotlin standard library types, and [com.github.avrokotlin.avro4k.serializer.AnySerializer].
 */
internal fun defaultSerializersModule(): SerializersModule =
    JavaStdLibSerializersModule +
        JavaTimeSerializersModule +
        KotlinStdLibSerializersModule +
        AnyTypeSerializersModule

/** The default of [com.github.avrokotlin.avro4k.AvroConfiguration.logicalTypes]: a new map on each call. */
internal fun defaultLogicalTypes(): Map<String, KSerializer<out Any>> =
    mapOf(
        AvroDurationSerializer.LOGICAL_TYPE_NAME to AvroDurationSerializer,
        UUIDSerializer.LOGICAL_TYPE_NAME to UUIDSerializer,
        BigDecimalSerializer.DECIMAL_LOGICAL_TYPE_NAME to BigDecimalSerializer,
        BigDecimalSerializer.BIG_DECIMAL_LOGICAL_TYPE_NAME to BigDecimalSerializer,
        LOGICAL_TYPE_NAME_DATE to LocalDateSerializer,
        LOGICAL_TYPE_NAME_TIME_MILLIS to LocalTimeSerializer,
        LOGICAL_TYPE_NAME_TIME_MICROS to LocalTimeSerializer,
        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS to InstantSerializer,
        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS to InstantSerializer
    )

/**
 * This encoder, checking every call against [writerSchema], for
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization]: wrapped in Apache's validating encoder, which
 * needs the schema in Apache's model (converted through the cached converter).
 */
internal fun AvroBinaryEncoder.validatingAgainst(writerSchema: AvroSchema): AvroBinaryEncoder = validating(avro4kToApache(writerSchema))

/** This decoder, checking every call against [writerSchema]: see [validatingAgainst] for the encoder. */
internal fun AvroBinaryDecoder.validatingAgainst(writerSchema: AvroSchema): AvroBinaryDecoder = validating(avro4kToApache(writerSchema))

/**
 * The generic value of a fixed default, for the deprecated `AvroDecoder.decodeValue`: an Apache `GenericData.Fixed`
 * carrying its schema in Apache's model, as the generic values of the former default-value path did.
 */
internal fun genericFixedValue(
    schema: AvroSchema,
    bytes: ByteArray,
): Any = GenericData.Fixed(avro4kToApache(schema), bytes)