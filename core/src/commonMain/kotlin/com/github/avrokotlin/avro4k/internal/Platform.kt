package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.serializer.AvroDurationSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule

// What the code shared by every platform gets from the platform it runs on.

/** The serializers module of [com.github.avrokotlin.avro4k.Avro.Default]. */
internal expect fun defaultSerializersModule(): SerializersModule

/**
 * The default of [com.github.avrokotlin.avro4k.AvroConfiguration.logicalTypes]: a new map on each call.
 *
 * `duration` and `uuid` decode to the same types on every platform, so a generic value of those logical types does
 * not depend on where it is decoded; the platform adds its own types for the others.
 */
internal fun defaultLogicalTypes(): Map<String, KSerializer<out Any>> =
    mapOf(
        AvroDurationSerializer.LOGICAL_TYPE_NAME to AvroDurationSerializer,
        KotlinUuidSerializer.LOGICAL_TYPE_NAME to KotlinUuidSerializer
    ) + platformLogicalTypes()

/** The logical types that [defaultLogicalTypes] decodes to a type of the platform it runs on. */
internal expect fun platformLogicalTypes(): Map<String, KSerializer<out Any>>

/**
 * The UTF-8 bytes of this string as avro4k writes them: the JVM's `String.getBytes(UTF_8)`, byte for byte, on every
 * platform. An unpaired surrogate becomes `?` (0x3F), as on the JVM and so in Apache Avro, where Kotlin's
 * `encodeToByteArray()` writes U+FFFD (3 bytes) on JS and native: the same string must give the same bytes everywhere.
 */
internal expect fun String.encodeToUtf8(): ByteArray