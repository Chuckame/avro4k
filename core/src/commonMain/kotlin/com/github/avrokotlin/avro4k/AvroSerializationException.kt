package com.github.avrokotlin.avro4k

import kotlinx.serialization.SerializationException

// Explicit secondary constructors rather than default arguments: a default argument adds a synthetic constructor to
// the public binary API.

/**
 * The base class of every failure avro4k reports while generating or parsing a schema, encoding or decoding.
 *
 * It is a [SerializationException], so code catching [SerializationException] (or [IllegalArgumentException], its
 * parent) keeps catching every avro4k failure. Catch one of the subclasses to tell the failures apart:
 * - [AvroSchemaGenerationException]: a schema cannot be generated from a descriptor,
 * - [AvroSchemaParseException]: a schema's json is not a valid Avro schema,
 * - [AvroSchemaMismatchException]: the writer schema does not fit the Kotlin type, or the value does not fit the schema,
 * - [AvroMalformedDataException]: the decoded bytes are not a valid Avro encoding (truncated, corrupted, out of bounds),
 * - [AvroValidationException]: with [AvroConfiguration.validateSerialization], a serializer did not follow the writer schema.
 *
 * Programming errors, like a bad configuration or an invalid argument, are not reported with this class.
 */
public open class AvroSerializationException(message: String?, cause: Throwable?) : SerializationException(message, cause) {
    public constructor(message: String?) : this(message, null)
}

/**
 * A schema cannot be generated from a descriptor.
 *
 * [path] locates the element that failed, from the root type: the root serial name, then `.property` for a class
 * property, `[]` for a list item, `{key}` or `{value}` for a map key or value, and `<serial name>` for a subclass of a
 * polymorphic type, like `com.example.Order.lines[].product<com.example.Digital>.price`. The message ends with it.
 */
@ExperimentalAvro4kApi
public open class AvroSchemaGenerationException(message: String, public val path: String, cause: Throwable?) :
    AvroSerializationException("$message (at '$path')", cause) {
    public constructor(message: String, path: String) : this(message, path, null)
}

/** A schema's json is not a valid Avro schema. */
@ExperimentalAvro4kApi
public open class AvroSchemaParseException(message: String?, cause: Throwable?) : AvroSerializationException(message, cause) {
    public constructor(message: String?) : this(message, null)
}

/**
 * The writer schema does not fit the Kotlin type, or the value being encoded or decoded does not fit the other side
 * (a number out of range, a string that is not a number, an unknown enum symbol, …).
 */
@ExperimentalAvro4kApi
public open class AvroSchemaMismatchException(message: String?, cause: Throwable?) : AvroSerializationException(message, cause) {
    public constructor(message: String?) : this(message, null)
}

/** The decoded input is not a valid Avro encoding of the writer schema: truncated, corrupted, or out of the encoding's bounds. */
@ExperimentalAvro4kApi
public open class AvroMalformedDataException(message: String?, cause: Throwable?) : AvroSerializationException(message, cause) {
    public constructor(message: String?) : this(message, null)
}

/**
 * With [AvroConfiguration.validateSerialization], the calls of a serializer do not follow the writer schema.
 *
 * [path] is where in the datum, like `inner.list[2].name`, or empty for the root and after the end of the datum.
 */
@ExperimentalAvro4kApi
public open class AvroValidationException(message: String, public val path: String) : AvroSerializationException(message, null)