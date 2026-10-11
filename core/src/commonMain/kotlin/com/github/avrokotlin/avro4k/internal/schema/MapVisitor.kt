package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.internal.SchemaGenerationFailure
import com.github.avrokotlin.avro4k.isNullable
import kotlinx.serialization.descriptors.SerialDescriptor

internal class MapVisitor(
    private val context: VisitorContext,
    private val onSchemaBuilt: (AvroSchema) -> Unit,
) : SerialDescriptorMapVisitor {
    private lateinit var valueSchema: AvroSchema

    override fun visitMapKey(
        mapDescriptor: SerialDescriptor,
        keyElementIndex: Int,
    ) = ValueVisitor(context.copy(path = context.path.mapKey())) {
        // In avro, the map key must be a string.
        // Here we just delegate the schema building to the value visitor
        // and then check if the output schema is about a type that we can
        // stringify (e.g. when .toString() makes sense).
        // Here we are just checking if the schema is string-compatible. We don't need to
        // store the schema as it is always a string.
        if (it.isNullable) {
            throw SchemaGenerationFailure("Map key cannot be nullable. Actual generated map key schema: ${it.toSchemaJsonStringOrName()}")
        }
        if (!it.isNonNullScalarType()) {
            throw SchemaGenerationFailure(
                "Map key must be a non-null scalar type (e.g. not a record, map or array). Actual generated map key schema: ${it.toSchemaJsonStringOrName()}"
            )
        }
    }

    override fun visitMapValue(
        mapDescriptor: SerialDescriptor,
        valueElementIndex: Int,
    ) = ValueVisitor(context.copy(path = context.path.mapValue())) {
        valueSchema = it
    }

    override fun endMapVisit(descriptor: SerialDescriptor) {
        onSchemaBuilt(MapSchema(valueSchema))
    }
}