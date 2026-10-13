package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type
import com.github.avrokotlin.avro4k.LockableList
import com.github.avrokotlin.avro4k.internal.SchemaGenerationFailure
import com.github.avrokotlin.avro4k.internal.isValidDefaultFor
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.toFieldDefault
import com.github.avrokotlin.avro4k.isNullable
import com.github.avrokotlin.avro4k.serializer.ElementLocation
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class ClassVisitor(
    descriptor: SerialDescriptor,
    private val context: VisitorContext,
    private val onSchemaBuilt: (AvroSchema) -> Unit,
) : SerialDescriptorClassVisitor {
    /**
     * The fields of the record being built, or null when the record was already resolved by an enclosing visit (a
     * recursive record, or a record used twice): it is registered in [VisitorContext.resolvedSchemas] *before* its fields
     * are visited, so that they can reference it, and its fields are locked in [endClassVisit] once all of them are added.
     */
    private val fields: LockableList<RecordSchema.Field>?
    private val schema: RecordSchema

    init {
        var fields: LockableList<RecordSchema.Field>? = null
        val resolved =
            context.resolvedSchemas.getOrPut(descriptor.nonNullSerialName) {
                val annotations = TypeAnnotations(descriptor)
                val name = namedSchemaName(descriptor.nonNullSerialName)
                val newFields = LockableList<RecordSchema.Field>()
                fields = newFields
                RecordSchema(
                    name = name,
                    fields = newFields,
                    doc = annotations.doc?.value,
                    aliases = annotations.aliases.toSchemaAliases(name),
                    props = annotations.props.toSchemaProps(SCHEMA_RESERVED_PROPS)
                )
            }
        schema = resolved as? RecordSchema
            ?: throw SchemaGenerationFailure(
                "Can't redefine: ${descriptor.nonNullSerialName}. The record '${descriptor.nonNullSerialName}' has the same full name as another named schema: " +
                    resolved.toSchemaJsonStringOrName()
            )
        this.fields = fields
    }

    override fun visitClassElement(
        descriptor: SerialDescriptor,
        elementIndex: Int,
    ): SerialDescriptorValueVisitor? {
        val fields = fields ?: return null
        return ValueVisitor(
            context.copy(
                path = context.path.property(descriptor.getElementName(elementIndex)),
                inlinedElements = listOf(ElementLocation(descriptor, elementIndex))
            )
        ) { fieldSchema ->
            fields.add(
                createField(
                    context.avro.configuration.fieldNamingStrategy.resolve(descriptor, elementIndex),
                    FieldAnnotations(descriptor, elementIndex),
                    fieldSchema
                )
            )
        }
    }

    override fun endClassVisit(descriptor: SerialDescriptor) {
        // Locked before anything reads the fields: a record's fields cannot be read, nor looked up by name, before
        fields?.lock()
        onSchemaBuilt(schema)
    }

    /**
     * Create a field with the given annotations.
     * Here are managed the generic field level annotations:
     * - namespaceOverride
     * - default (also sort unions according to the default value)
     * - aliases
     * - doc
     * - props & json props
     */
    private fun createField(
        fieldName: String,
        annotations: FieldAnnotations,
        elementSchema: AvroSchema,
    ): RecordSchema.Field {
        validateAvroName(fieldName)
        val (finalSchema, fieldDefault) = getDefaultAndReorderUnionIfNeeded(annotations, elementSchema)
        if (fieldDefault != null && !fieldDefault.isValidDefaultFor(finalSchema)) {
            // Apache's `Schema.Field` constructor validated the default, with this message (it threw an `AvroTypeException`)
            throw SchemaGenerationFailure("Invalid default for field $fieldName: $fieldDefault not a ${finalSchema.toSchemaJsonStringOrName()}")
        }

        return RecordSchema.Field(
            name = fieldName,
            schema = finalSchema,
            defaultValue = fieldDefault,
            doc = annotations.doc?.value,
            // an alias equal to the field's own name is a no-op that Apache accepts and the model rejects
            aliases = annotations.aliases?.value?.filterTo(LinkedHashSet()) { it != fieldName } ?: emptySet(),
            props = annotations.props.toSchemaProps(FIELD_RESERVED_PROPS)
        )
    }

    private fun getDefaultAndReorderUnionIfNeeded(
        annotations: FieldAnnotations,
        elementSchema: AvroSchema,
    ): Pair<AvroSchema, JsonElement?> {
        val defaultAnnotation = annotations.default
        if (defaultAnnotation == null) {
            if (context.configuration.implicitNulls && elementSchema.isNullable) {
                return elementSchema.moveToHeadOfUnion { it.type === Type.NULL } to JsonNull
            } else if (context.configuration.implicitEmptyCollections) {
                elementSchema.asSchemaList().forEachIndexed { index, schema ->
                    if (schema.type === Type.ARRAY) {
                        return elementSchema.moveToHeadOfUnion(index) to JsonArray(emptyList())
                    }
                    if (schema.type === Type.MAP) {
                        return elementSchema.moveToHeadOfUnion(index) to JsonObject(emptyMap())
                    }
                }
            }
            return elementSchema to null
        }

        val defaultValue = defaultAnnotation.toFieldDefault(elementSchema)
        if (!defaultValue.isValidDefaultFor(elementSchema) && defaultValue.isValidDefaultFor(elementSchema, allowAnyCodePoint = true)) {
            throw SchemaGenerationFailure(
                "Invalid default value $defaultValue for a bytes or fixed field: the specification only allows the code points 0-255, each one being a byte"
            )
        }
        val orderedSchema =
            when {
                defaultValue is JsonNull -> elementSchema.moveToHeadOfUnion { it.type === Type.NULL }

                elementSchema.asSchemaList().any { it.logicalTypeName == CHAR_LOGICAL_TYPE_NAME } ->
                    elementSchema.moveToHeadOfUnion { it.logicalTypeName == CHAR_LOGICAL_TYPE_NAME }

                // default is not null, so let's just put the null schema at the end of the union which should cover the main use cases
                elementSchema.isNullable -> elementSchema.moveToTailOfUnion { it.type === Type.NULL }

                else -> elementSchema
            }
        // The specification reads a union's default from its first branch that matches, but most implementations only ever
        // read it from the first branch: so that branch goes first (e.g. the second subclass of a sealed type).
        return orderedSchema.moveToHeadOfUnion { defaultValue.isValidDefaultFor(it) } to defaultValue
    }
}

/**
 * The schema as json for a message, or its name when it cannot be rendered: a recursive record still being built (its
 * fields are not locked yet) cannot be read.
 */
internal fun AvroSchema.toSchemaJsonStringOrName(): String =
    try {
        toSchemaJsonString()
    } catch (_: IllegalStateException) {
        fullNameForMessages
    }