package com.github.avrokotlin.avro4k.kafka.confluent

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.avro4k.toApacheSchema
import com.github.avrokotlin.avro4k.toAvro4k
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import org.apache.avro.Schema

/*
 * The tests of this module build their writer schemas with Apache's API, as the schema registry and its clients do, while
 * core's public API is `AvroSchema`-typed. These Apache-typed overloads of core's entry points convert with apache-interop,
 * as an Apache user does, so the call sites stay Apache-typed.
 */

/** The schema core infers for [T], as an Apache schema. */
internal inline fun <reified T> Avro.apacheSchema(): Schema = schema<T>().toApacheSchema()

internal fun <T> Avro.encodeToByteArray(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema.toAvro4k(), serializer, value)

internal inline fun <reified T> Avro.encodeToByteArray(
    writerSchema: Schema,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, serializersModule.serializer<T>(), value)

internal fun <T> Avro.decodeFromByteArray(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    bytes: ByteArray,
): T = decodeFromByteArray(writerSchema.toAvro4k(), deserializer, bytes)

internal inline fun <reified T> Avro.decodeFromByteArray(
    writerSchema: Schema,
    bytes: ByteArray,
): T = decodeFromByteArray(writerSchema, serializersModule.serializer<T>(), bytes)