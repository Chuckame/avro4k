package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.SchemaGenerationFailure
import kotlinx.serialization.descriptors.SerialDescriptor

internal class PolymorphicVisitor(
    private val context: VisitorContext,
    private val onSchemaBuilt: (AvroSchema) -> Unit,
) : SerialDescriptorPolymorphicVisitor {
    private val possibleSchemas = mutableListOf<AvroSchema>()

    override fun visitPolymorphicFoundDescriptor(descriptor: SerialDescriptor): SerialDescriptorValueVisitor {
        return ValueVisitor(context.copy(path = context.path.subclass(descriptor.serialName))) {
            possibleSchemas += it
        }
    }

    override fun endPolymorphicVisit(descriptor: SerialDescriptor) {
        if (possibleSchemas.isEmpty()) {
            throw SchemaGenerationFailure("Polymorphic descriptor '$descriptor' must have at least one possible schema")
        }
        if (possibleSchemas.size == 1) {
            // flatten the useless union schema
            onSchemaBuilt(possibleSchemas.first())
        } else {
            onSchemaBuilt(
                UnionSchema(
                    possibleSchemas.map {
                        // Apache's `Schema.createUnion` rejected a nested union with this message
                        it as? ResolvedSchema ?: throw SchemaGenerationFailure("Nested union: ${it.toSchemaJsonStringOrName()}")
                    }
                )
            )
        }
    }
}