package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule

// What the code shared by every platform gets from the platform it runs on.

/** The serializers module of [com.github.avrokotlin.avro4k.Avro.Default]. */
internal expect fun defaultSerializersModule(): SerializersModule

/** The default of [com.github.avrokotlin.avro4k.AvroConfiguration.logicalTypes]: a new map on each call. */
internal expect fun defaultLogicalTypes(): Map<String, KSerializer<out Any>>

/** The generic value of a fixed default, for the deprecated `AvroDecoder.decodeValue`. */
internal expect fun genericFixedValue(
    schema: AvroSchema,
    bytes: ByteArray,
): Any