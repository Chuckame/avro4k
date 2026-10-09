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