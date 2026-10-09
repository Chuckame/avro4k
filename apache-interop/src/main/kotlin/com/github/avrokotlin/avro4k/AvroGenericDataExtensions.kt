package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.decoder.generic.AvroValueGenericDecoder
import com.github.avrokotlin.avro4k.internal.encoder.generic.AvroValueGenericEncoder
import com.github.avrokotlin.avro4k.toApacheSchema
import com.github.avrokotlin.avro4k.toAvro4k
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.apache.avro.generic.GenericContainer

/**
 * Encodes [value] with [serializer] into Apache Avro's generic data model, following [writerSchema]: a record as an
 * Apache `GenericData.Record`, a fixed as a `GenericData.Fixed`, an enum as a `GenericData.EnumSymbol`, an array as a
 * collection, a map as a map, a string as an Apache `Utf8`, bytes as a `ByteBuffer`, a primitive as its boxed value, a logical
 * type as its underlying type's value. The result can be written by Apache's `GenericDatumWriter`.
 *
 * From the `avro4k-apache-interop` artifact (JVM only), which bridges avro4k and Apache Avro.
 */
@ExperimentalAvro4kApi
public fun <T> Avro.encodeToGenericData(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): Any? {
    var result: Any? = null
    // converted once per call, identity-cached: the encoders only read the AvroSchema
    AvroValueGenericEncoder(this, writerSchema.toAvro4k()) {
        result = it
    }.encodeSerializableValue(serializer, value)
    return result
}

/**
 * Encodes [value] into Apache Avro's generic data model, following the schema avro4k infers for [T] (see
 * [Avro.schema]). See the overload taking a writer schema for the generic values produced.
 *
 * From the `avro4k-apache-interop` artifact (JVM only), which bridges avro4k and Apache Avro.
 */
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.encodeToGenericData(value: T): Any? {
    val serializer = serializersModule.serializer<T>()
    // Apache-typed, as the generic data it produces: the generated schema's Apache form, through the cached converter
    return encodeToGenericData(schema(serializer).toApacheSchema(), serializer, value)
}

/**
 * Encodes [value] into Apache Avro's generic data model, following [writerSchema], with the serializer of [T] from
 * [Avro.serializersModule]. See the overload taking a serializer for the generic values produced.
 *
 * From the `avro4k-apache-interop` artifact (JVM only), which bridges avro4k and Apache Avro.
 */
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.encodeToGenericData(
    writerSchema: Schema,
    value: T,
): Any? {
    val serializer = serializersModule.serializer<T>()
    return encodeToGenericData(writerSchema, serializer, value)
}

/**
 * Decodes [value], a value of Apache Avro's generic data model (e.g. read by Apache's `GenericDatumReader`), written
 * with [writerSchema], with [deserializer]. Reader and writer schemas are resolved as when decoding bytes (missing
 * fields take their default, an `int` can be read as a `long`…), and a union takes the branch of the generic value it
 * holds.
 *
 * From the `avro4k-apache-interop` artifact (JVM only), which bridges avro4k and Apache Avro.
 */
@ExperimentalAvro4kApi
public fun <T> Avro.decodeFromGenericData(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    value: Any?,
): T {
    // converted once per call, identity-cached: the decoders only read the AvroSchema
    return AvroValueGenericDecoder(this, value, writerSchema.toAvro4k())
        .decodeSerializableValue(deserializer)
}

/**
 * Decodes [value], a value of Apache Avro's generic data model written with [writerSchema], with the serializer of [T]
 * from [Avro.serializersModule]. See the overload taking a deserializer.
 *
 * From the `avro4k-apache-interop` artifact (JVM only), which bridges avro4k and Apache Avro.
 */
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.decodeFromGenericData(
    writerSchema: Schema,
    value: Any?,
): T {
    val deserializer = serializersModule.serializer<T>()
    return decodeFromGenericData(writerSchema, deserializer, value)
}

/**
 * Decodes [value], an Apache generic container (e.g. a `GenericRecord`), with its own schema as writer schema and the
 * serializer of [T] from [Avro.serializersModule]; `null` for a `null` value. See the overload taking a deserializer.
 *
 * From the `avro4k-apache-interop` artifact (JVM only), which bridges avro4k and Apache Avro.
 */
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.decodeFromGenericData(value: GenericContainer?): T? {
    if (value == null) return null
    val deserializer = serializersModule.serializer<T>()
    return decodeFromGenericData(value.schema, deserializer, value)
}