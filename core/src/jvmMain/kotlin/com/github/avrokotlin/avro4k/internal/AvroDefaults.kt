package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.LockableList
import com.github.avrokotlin.avro4k.internal.schema.CHAR_LOGICAL_TYPE_NAME
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.isNullable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.apache.avro.Schema
import com.github.avrokotlin.avro4k.internal.schema.asSchemaList as avroSchemaList

// The rules for field default values, shared by the schema generation (on AvroSchema) and by the decoding of a field
// missing from the writer schema (still on Apache's Schema until the decoders read AvroSchema; the Apache-typed variants
// below go away then).
// Each rule is written once, over the kind of schema (AvroSchema.Type) and plain facts about the field's schema; the two
// variants of each function only walk their own schema model and delegate to it.

/**
 * The default value of a field as json, exactly as the schema generation writes it in the field's schema, and as it is
 * read back for a field missing from the writer schema, so that both always agree:
 * - a value that looks like json is parsed, anything else is a string, and numbers are normalized
 *   (`1.0` is the integer `1`, trailing zeros are stripped);
 * - `null` for a non-nullable field is the string `"null"`, as the user may have wanted the "null" string default;
 * - a char's default must be a single-character string, and becomes its code as the char logical type is an int.
 */
internal fun AvroDefault.toFieldDefault(fieldSchema: AvroSchema): JsonElement =
    toFieldDefault(
        isNullable = fieldSchema.isNullable,
        isChar = fieldSchema.avroSchemaList().any { it.logicalTypeName == CHAR_LOGICAL_TYPE_NAME }
    )

/** [toFieldDefault] for an Apache schema: the decoder's reader schema, until the decoders read `AvroSchema`. */
internal fun AvroDefault.toFieldDefault(fieldSchema: Schema): JsonElement =
    toFieldDefault(
        isNullable = fieldSchema.isNullable,
        isChar = fieldSchema.asSchemaList().any { it.logicalType?.name == CHAR_LOGICAL_TYPE_NAME }
    )

private fun AvroDefault.toFieldDefault(
    isNullable: Boolean,
    isChar: Boolean,
): JsonElement {
    val json = if (value.isStartingAsJson()) Json.parseToJsonElement(value).normalizeNumbers() else JsonPrimitive(value)
    return when {
        // Kept by the user's decision (2026-09-24, PROGRESS.md decision log): re-challenge it (e.g. reject `null` on a
        // non-nullable field) only if keeping it ever adds complexity to the code.
        json is JsonNull -> if (isNullable) json else JsonPrimitive("null")

        isChar ->
            if (json is JsonPrimitive && json.isString && json.content.length == 1) {
                JsonPrimitive(json.content.single().code)
            } else {
                throw SerializationException("Default value for Char must be a single character string. Invalid value: $value")
            }

        else -> json
    }
}

private fun JsonElement.normalizeNumbers(): JsonElement =
    when (this) {
        is JsonNull -> this

        is JsonObject -> JsonObject(mapValues { it.value.normalizeNumbers() })

        is JsonArray -> JsonArray(map { it.normalizeNumbers() })

        is JsonPrimitive ->
            if (isString || booleanOrNull != null) {
                this
            } else {
                val number = content.toBigDecimal().stripTrailingZeros()
                if (number.scale() <= 0) JsonPrimitive(number.toBigInteger()) else JsonPrimitive(number)
            }
    }

/**
 * Whether this json is a valid default value for [schema], following Apache Java's validation of defaults (itself the
 * specification's table of json types per Avro type), plus the specification's rule for bytes and fixed: their json
 * string may only contain the code points 0-255, each one being a byte. That rule is for the schema generation only:
 * the common model's own check (`isValidJsonForSchema`) keeps accepting every default Apache Java accepts.
 *
 * A union accepts a default that is valid for any of its branches, and a record a json object whose missing fields have
 * a default in the schema. A record still being built (the fields of a recursive record are not locked yet) accepts any
 * json object, like the model's own check: its fields are not known yet.
 *
 * @param allowAnyCodePoint lifts the bytes/fixed rule, to tell that rule apart from the other ones
 */
internal fun JsonElement.isValidDefaultFor(
    schema: AvroSchema,
    allowAnyCodePoint: Boolean = false,
): Boolean =
    when (schema) {
        is ArraySchema -> this is JsonArray && all { it.isValidDefaultFor(schema.elementSchema, allowAnyCodePoint) }

        is MapSchema -> this is JsonObject && values.all { it.isValidDefaultFor(schema.valueSchema, allowAnyCodePoint) }

        is RecordSchema -> {
            val fields = schema.fields
            this is JsonObject &&
                (
                    (fields is LockableList && !fields.isLocked) ||
                        fields.all { field -> this[field.name]?.isValidDefaultFor(field.schema, allowAnyCodePoint) ?: (field.defaultValue != null) }
                )
        }

        is UnionSchema -> schema.types.any { isValidDefaultFor(it, allowAnyCodePoint) }

        else -> isValidDefaultForLeaf(schema.type, allowAnyCodePoint)
    }

/** [isValidDefaultFor] for an Apache schema: the decoder's reader schema, until the decoders read `AvroSchema`. */
internal fun JsonElement.isValidDefaultFor(
    schema: Schema,
    allowAnyCodePoint: Boolean = false,
): Boolean =
    when (schema.type) {
        Schema.Type.ARRAY -> this is JsonArray && all { it.isValidDefaultFor(schema.elementType, allowAnyCodePoint) }

        Schema.Type.MAP -> this is JsonObject && values.all { it.isValidDefaultFor(schema.valueType, allowAnyCodePoint) }

        Schema.Type.RECORD ->
            this is JsonObject &&
                schema.fields.all { field -> this[field.name()]?.isValidDefaultFor(field.schema(), allowAnyCodePoint) ?: field.hasDefaultValue() }

        Schema.Type.UNION -> schema.types.any { isValidDefaultFor(it, allowAnyCodePoint) }

        else -> isValidDefaultForLeaf(schema.type.toAvroSchemaType(), allowAnyCodePoint)
    }

/** The rule of [isValidDefaultFor] for the schemas that contain no other schema, shared by both schema models. */
private fun JsonElement.isValidDefaultForLeaf(
    type: AvroSchema.Type,
    allowAnyCodePoint: Boolean,
): Boolean =
    when (type) {
        AvroSchema.Type.NULL -> this is JsonNull

        AvroSchema.Type.BOOLEAN -> this is JsonPrimitive && !isString && booleanOrNull != null

        AvroSchema.Type.INT -> this.isNumberLiteral() && (this as JsonPrimitive).content.toIntOrNull() != null

        AvroSchema.Type.LONG -> this.isNumberLiteral() && (this as JsonPrimitive).content.toLongOrNull() != null

        AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE -> this.isNumberLiteral() && (this as JsonPrimitive).content.toDoubleOrNull() != null

        AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> this is JsonPrimitive && isString

        AvroSchema.Type.BYTES, AvroSchema.Type.FIXED -> this is JsonPrimitive && isString && (allowAnyCodePoint || content.all { it.code <= 0xFF })

        // Composite schemas are walked by the callers, each over its own model
        AvroSchema.Type.ARRAY, AvroSchema.Type.MAP, AvroSchema.Type.RECORD, AvroSchema.Type.UNION -> error("Not a leaf schema type: $type")
    }

private fun Schema.Type.toAvroSchemaType(): AvroSchema.Type =
    when (this) {
        Schema.Type.RECORD -> AvroSchema.Type.RECORD
        Schema.Type.ENUM -> AvroSchema.Type.ENUM
        Schema.Type.ARRAY -> AvroSchema.Type.ARRAY
        Schema.Type.MAP -> AvroSchema.Type.MAP
        Schema.Type.UNION -> AvroSchema.Type.UNION
        Schema.Type.FIXED -> AvroSchema.Type.FIXED
        Schema.Type.STRING -> AvroSchema.Type.STRING
        Schema.Type.BYTES -> AvroSchema.Type.BYTES
        Schema.Type.INT -> AvroSchema.Type.INT
        Schema.Type.LONG -> AvroSchema.Type.LONG
        Schema.Type.FLOAT -> AvroSchema.Type.FLOAT
        Schema.Type.DOUBLE -> AvroSchema.Type.DOUBLE
        Schema.Type.BOOLEAN -> AvroSchema.Type.BOOLEAN
        Schema.Type.NULL -> AvroSchema.Type.NULL
    }

private fun JsonElement.isNumberLiteral() = this is JsonPrimitive && this !is JsonNull && !isString && booleanOrNull == null

/**
 * Returns the schema a default value is decoded with: this schema, or, for a union, its **first branch the default is
 * valid for**, as the specification says since 1.12 ("the first schema that matches in the union").
 *
 * The schema generation puts that branch first anyway (see `ClassVisitor`), since most other Avro implementations only
 * ever read a union's default from its first branch.
 */
internal fun AvroSchema.resolveDefaultBranch(value: JsonElement): AvroSchema {
    if (this !is UnionSchema) return this
    return types.firstOrNull { value.isValidDefaultFor(it) } ?: throw noCompatibleBranchError(toSchemaJsonString(), value)
}

/** [resolveDefaultBranch] for an Apache schema: the decoder's reader schema, until the decoders read `AvroSchema`. */
internal fun Schema.resolveDefaultBranch(value: JsonElement): Schema {
    if (type != Schema.Type.UNION) return this
    return types.firstOrNull { value.isValidDefaultFor(it) } ?: throw noCompatibleBranchError(toString(), value)
}

private fun noCompatibleBranchError(
    unionJson: String,
    value: JsonElement,
) = SerializationException("No branch of the union $unionJson is compatible with the default value '$value'")

/**
 * The bytes of a bytes or fixed default: each code point 0-255 of the json string is one byte, as the specification says.
 */
internal fun String.defaultValueBytes(): ByteArray =
    ByteArray(length) { index ->
        val code = this[index].code
        if (code > 0xFF) {
            throw SerializationException(
                "A bytes or fixed default value can only contain the code points 0-255, but '$this' contains '${this[index]}' at index $index"
            )
        }
        code.toByte()
    }