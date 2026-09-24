@file:OptIn(ExperimentalSerializationApi::class)

package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.BigDecimalSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import org.intellij.lang.annotations.Language

/**
 * Adds a property to the Avro schema or field. Its value could be any valid JSON or just a string.
 *
 * When annotated on a value class or its underlying field, the props are applied to the underlying type.
 *
 * Only works with classes (data, enum & object types) and class properties (not enum values).
 * Fails at runtime when used in value classes wrapping a named schema (fixed, enum or record).
 */
@SerialInfo
@Repeatable
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class AvroProp(
    val key: String,
    @Language("JSON") val value: String,
)

/**
 * Adds documentation to:
 * - a record's field when annotated on a data class property
 * - a record when annotated on a data class or object
 * - an enum type when annotated on an enum class
 * - a fixed type when annotated on a value class whose property is annotated with [AvroFixed]
 *
 * Only works with classes (data, enum, object & value types) and class properties (not enum values).
 * Ignored on other value classes and on value class properties.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class AvroDoc(val value: String)

/**
 * Adds aliases to a field of a record, or to a named type: a record, an enum, or a fixed when annotated on a value class
 * whose property is annotated with [AvroFixed]. It helps to allow having different names for the same field or type for
 * better compatibility when changing a schema. A type's alias without a namespace is resolved in the type's namespace.
 *
 * Only works with classes (data, enum, object & value types) and class properties (not enum values).
 * Ignored on other value classes and on value class properties.
 *
 * @param value The aliases for the annotated property. Note that the given aliases won't be changed by the configured [AvroConfiguration.fieldNamingStrategy].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class AvroAlias(vararg val value: String)

/**
 * Sets the annotated property as a `string` type when inferring the class' schema. Takes precedence over any other schema modifier built-in annotation, except
 * the fields referring to a custom serializer implementing [AvroSerializer] where you'll need to handle the annotation.
 *
 * If the given property is not string-able, the schema will be generated as a string, but it will fail at runtime during serialization or deserialization.
 * You may need to provide a custom serializer for the given property to handle the string specifications.
 *
 * Only works with class properties for the following inferred schemas:
 * - `string`
 * - `boolean`
 * - `int`
 * - `long`
 * - `float`
 * - `double`
 * - `bytes` & `fixed` (will take the fixed bytes as UTF-8 string, or custom toString/parse for logical types)
 * - works also for all the nullable types of the above
 * The rest will fail, except if your custom serializers handle the string type.
 */
@SerialInfo
@ExperimentalAvro4kApi
@Target(AnnotationTarget.PROPERTY)
public annotation class AvroStringable

/**
 * To be used with [BigDecimalSerializer] to specify the scale, precision, type and rounding mode of the decimal value.
 *
 * Can be used with [AvroFixed] to serialize value as a fixed type.
 *
 * Only works with [java.math.BigDecimal] property type.
 */
@SerialInfo
@ExperimentalAvro4kApi
@Target(AnnotationTarget.PROPERTY)
public annotation class AvroDecimal(
    val scale: Int,
    val precision: Int,
)

/**
 * Indicates that the annotated property should be encoded as an Avro fixed type.
 *
 * Only works with [ByteArray], [String] and [java.math.BigDecimal] property types.
 *
 * The fixed type's name depends on where the annotation is:
 * - on a value class's property: the value class's serial name (set it with `@SerialName`), with the value class's
 *   [AvroAlias] and [AvroDoc] as the fixed's aliases and doc.
 * - on a data class property: the property's name, in the namespace of the record's full name (`<record full name>.<property>`).
 *
 * The fixed type created by [java.util.UUID], [kotlin.uuid.Uuid] and [java.math.BigDecimal] serializers follows the same naming.
 *
 * @param size The number of bytes of the fixed type. Note that smaller values will be padded with 0s during encoding, but not unpadded when decoding.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class AvroFixed(val size: Int)

/**
 * Sets the default avro value for a record's field.
 *
 * - Records and maps have to be represented as a json object
 * - Arrays have to be represented as a json array
 * - Nulls have to be represented as a json `null`. To set the string `"null"`, don't forget to quote the string, example: `""""null""""` or `"\"null\""`.
 * - Any non json content will be treated as a string
 *
 * Only works with data class properties (not enum values). Ignored in value classes.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class AvroDefault(
    @Language("JSON") val value: String,
)

/**
 * Sets the enum default value when decoded an unknown enum value.
 *
 * Only works with enum classes.
 */
@SerialInfo
@ExperimentalAvro4kApi
@Target(AnnotationTarget.PROPERTY)
public annotation class AvroEnumDefault