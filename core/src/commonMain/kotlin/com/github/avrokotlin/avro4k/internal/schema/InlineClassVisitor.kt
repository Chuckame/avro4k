package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.BooleanSchema
import com.github.avrokotlin.avro4k.AvroSchema.BytesSchema
import com.github.avrokotlin.avro4k.AvroSchema.DoubleSchema
import com.github.avrokotlin.avro4k.AvroSchema.FloatSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.SchemaGenerationFailure
import com.github.avrokotlin.avro4k.internal.jsonElement
import com.github.avrokotlin.avro4k.serializer.ElementLocation
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement

internal class InlineClassVisitor(
    private val context: VisitorContext,
    private val onSchemaBuilt: (AvroSchema) -> Unit,
) : SerialDescriptorInlineClassVisitor {
    override fun visitInlineClassElement(
        inlineClassDescriptor: SerialDescriptor,
        inlineElementIndex: Int,
    ): SerialDescriptorValueVisitor {
        val inlinedElements = context.inlinedElements + ElementLocation(inlineClassDescriptor, inlineElementIndex)
        return ValueVisitor(context.copy(inlinedElements = inlinedElements)) { generatedSchema ->
            val annotations = InlineClassFieldAnnotations(inlineClassDescriptor)

            val props = annotations.props.toList()
            val schema =
                if (props.isNotEmpty()) {
                    if (generatedSchema.isNamedSchema()) {
                        throw SchemaGenerationFailure(
                            "The value class property '${inlineClassDescriptor.serialName}.${inlineClassDescriptor.getElementName(0)}' has " +
                                "forbidden additional properties $props for the named schema ${generatedSchema.fullName}. " +
                                "Please create your own serializer extending com.github.avrokotlin.avro4k.serializer.AvroSerializer to add properties to a named schema."
                        )
                    }
                    // The last annotation wins for a prop set twice (the element's one over the class' one)
                    generatedSchema.withAdditionalProps(props.associate { it.key to it.jsonElement })
                } else {
                    generatedSchema
                }

            onSchemaBuilt(schema)
        }
    }
}

/**
 * A copy of this unnamed schema with [additionalProps] added after its own props, like Apache's `Schema.copy(additionalProps = …)`
 * did: reserved props and a different value for an existing prop are rejected ([addProp]).
 *
 * A union has no props in the model, nor in the json of Apache's model (its props were never written), so it is returned as is.
 */
private fun AvroSchema.withAdditionalProps(additionalProps: Map<String, JsonElement>): AvroSchema {
    // A union, the only schema that is not resolved
    val resolved = this as? ResolvedSchema ?: return this
    val props = LinkedHashMap(resolved.props)
    additionalProps.forEach { (key, value) -> props.addProp(key, value, SCHEMA_RESERVED_PROPS) }
    return when (resolved) {
        is BooleanSchema -> resolved.copy(props = props)

        is IntSchema -> resolved.copy(props = props)

        is LongSchema -> resolved.copy(props = props)

        is FloatSchema -> resolved.copy(props = props)

        is DoubleSchema -> resolved.copy(props = props)

        is BytesSchema -> resolved.copy(props = props)

        is StringSchema -> resolved.copy(props = props)

        is NullSchema -> resolved.copy(props = props)

        is ArraySchema -> resolved.copy(props = props)

        is MapSchema -> resolved.copy(props = props)

        // Rejected by the caller with a user-facing message
        is NamedSchema -> throw IllegalArgumentException("A named schema cannot get additional props: ${resolved.fullName}")
    }
}