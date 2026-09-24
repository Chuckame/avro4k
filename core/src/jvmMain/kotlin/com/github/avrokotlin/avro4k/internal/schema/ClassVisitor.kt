package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.internal.asSchemaList
import com.github.avrokotlin.avro4k.internal.isValidDefaultFor
import com.github.avrokotlin.avro4k.internal.jsonNode
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.toFieldDefault
import com.github.avrokotlin.avro4k.serializer.ElementLocation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import org.apache.avro.JsonProperties
import org.apache.avro.Schema

internal class ClassVisitor(
    descriptor: SerialDescriptor,
    private val context: VisitorContext,
    private val onSchemaBuilt: (Schema) -> Unit,
) : SerialDescriptorClassVisitor {
    private val fields = mutableListOf<Schema.Field>()
    private val schemaAlreadyResolved: Boolean
    private val schema: Schema

    init {
        var schemaAlreadyResolved = true
        schema =
            context.resolvedSchemas.getOrPut(descriptor.nonNullSerialName) {
                schemaAlreadyResolved = false

                val annotations = TypeAnnotations(descriptor)
                val schema =
                    Schema.createRecord(
                        // name =
                        descriptor.nonNullSerialName,
                        // doc =
                        annotations.doc?.value,
                        // namespace =
                        null,
                        // isError =
                        false
                    )
                annotations.aliases?.value?.forEach { schema.addAlias(it) }
                annotations.props.forEach { schema.addProp(it.key, it.jsonNode) }
                schema
            }
        this.schemaAlreadyResolved = schemaAlreadyResolved
    }

    override fun visitClassElement(
        descriptor: SerialDescriptor,
        elementIndex: Int,
    ): SerialDescriptorValueVisitor? {
        if (schemaAlreadyResolved) {
            return null
        }
        return ValueVisitor(context.copy(inlinedElements = listOf(ElementLocation(descriptor, elementIndex)))) { fieldSchema ->
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
        if (!schemaAlreadyResolved) {
            schema.fields = fields
        }
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
        elementSchema: Schema,
    ): Schema.Field {
        val (finalSchema, fieldDefault) = getDefaultAndReorderUnionIfNeeded(annotations, elementSchema)

        val field =
            Schema.Field(
                fieldName,
                finalSchema,
                annotations.doc?.value,
                fieldDefault
            )
        annotations.aliases?.value?.forEach { field.addAlias(it) }
        annotations.props.forEach { field.addProp(it.key, it.jsonNode) }
        return field
    }

    private fun getDefaultAndReorderUnionIfNeeded(
        annotations: FieldAnnotations,
        elementSchema: Schema,
    ): Pair<Schema, Any?> {
        val defaultAnnotation = annotations.default
        if (defaultAnnotation == null) {
            if (context.configuration.implicitNulls && elementSchema.isNullable) {
                return elementSchema.moveToHeadOfUnion { it.type == Schema.Type.NULL } to JsonProperties.NULL_VALUE
            } else if (context.configuration.implicitEmptyCollections) {
                elementSchema.asSchemaList().forEachIndexed { index, schema ->
                    if (schema.type == Schema.Type.ARRAY) {
                        return elementSchema.moveToHeadOfUnion(index) to emptyList<Any>()
                    }
                    if (schema.type == Schema.Type.MAP) {
                        return elementSchema.moveToHeadOfUnion(index) to emptyMap<Any, Any>()
                    }
                }
            }
            return elementSchema to null
        }

        val defaultValue = defaultAnnotation.toFieldDefault(elementSchema)
        if (!defaultValue.isValidDefaultFor(elementSchema) && defaultValue.isValidDefaultFor(elementSchema, allowAnyCodePoint = true)) {
            throw SerializationException(
                "Invalid default value $defaultValue for a bytes or fixed field: the specification only allows the code points 0-255, each one being a byte"
            )
        }
        val orderedSchema =
            when {
                defaultValue is JsonNull -> elementSchema.moveToHeadOfUnion { it.type == Schema.Type.NULL }

                elementSchema.asSchemaList().any { it.logicalType?.name == CHAR_LOGICAL_TYPE_NAME } ->
                    elementSchema.moveToHeadOfUnion { it.logicalType?.name == CHAR_LOGICAL_TYPE_NAME }

                // default is not null, so let's just put the null schema at the end of the union which should cover the main use cases
                elementSchema.isNullable -> elementSchema.moveToTailOfUnion { it.type === Schema.Type.NULL }

                else -> elementSchema
            }
        // The specification reads a union's default from its first branch that matches, but most implementations only ever
        // read it from the first branch: so that branch goes first (e.g. the second subclass of a sealed type).
        return orderedSchema.moveToHeadOfUnion { defaultValue.isValidDefaultFor(it) } to defaultValue.toAvroObject()
    }
}

private fun JsonElement.toAvroObject(): Any =
    when (this) {
        is JsonNull -> JsonProperties.NULL_VALUE

        is JsonObject -> this.entries.associate { it.key to it.value.toAvroObject() }

        is JsonArray -> this.map { it.toAvroObject() }

        is JsonPrimitive ->
            when {
                this.isString -> this.content

                this.booleanOrNull != null -> this.boolean

                else -> {
                    this.content.toBigDecimal().stripTrailingZeros().let {
                        if (it.scale() <= 0) {
                            it.toBigInteger()
                        } else {
                            it
                        }
                    }
                }
            }
    }

private fun Schema.moveToHeadOfUnion(predicate: (Schema) -> Boolean): Schema {
    if (!isUnion) {
        return this
    }
    types.indexOfFirst(predicate).let { index ->
        if (index <= 0) {
            return this
        }
        return moveToHeadOfUnion(index)
    }
}

private fun Schema.moveToHeadOfUnion(typeIndex: Int): Schema {
    if (!isUnion || typeIndex >= types.size) {
        return this
    }
    return Schema.createUnion(types.toMutableList().apply { add(0, removeAt(typeIndex)) })
}

private fun Schema.moveToTailOfUnion(predicate: (Schema) -> Boolean): Schema {
    if (!isUnion) {
        return this
    }
    types.indexOfFirst(predicate).let { index ->
        if (index == -1) {
            return this
        }
        return moveToTailOfUnion(index)
    }
}

private fun Schema.moveToTailOfUnion(typeIndex: Int): Schema {
    if (!isUnion || typeIndex >= types.size) {
        return this
    }
    return Schema.createUnion(types.toMutableList().apply { add(removeAt(typeIndex)) })
}