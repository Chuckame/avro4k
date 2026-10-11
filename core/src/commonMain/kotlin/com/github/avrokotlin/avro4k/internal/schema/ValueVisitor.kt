package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.BooleanSchema
import com.github.avrokotlin.avro4k.AvroSchema.DoubleSchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FloatSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchemaGenerationException
import com.github.avrokotlin.avro4k.fromJsonString
import com.github.avrokotlin.avro4k.internal.AvroGenerated
import com.github.avrokotlin.avro4k.internal.SchemaGenerationFailure
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.findAnnotation
import com.github.avrokotlin.avro4k.internal.nameForMessages
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.isNullable
import com.github.avrokotlin.avro4k.nullable
import com.github.avrokotlin.avro4k.serializer.AvroSchemaSupplier
import com.github.avrokotlin.avro4k.serializer.getAvroSchema
import com.github.avrokotlin.avro4k.serializer.stringable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule

internal class ValueVisitor internal constructor(
    private val context: VisitorContext,
    private val onSchemaBuilt: (AvroSchema) -> Unit,
) : SerialDescriptorValueVisitor {
    private var isNullable: Boolean = false

    override val serializersModule: SerializersModule
        get() = context.avro.serializersModule

    constructor(avro: Avro, rootDescriptor: SerialDescriptor, onSchemaBuilt: (AvroSchema) -> Unit) : this(
        VisitorContext(
            avro,
            mutableMapOf(),
            SchemaPath.root(rootDescriptor.nonNullSerialName)
        ),
        onSchemaBuilt = onSchemaBuilt
    )

    override fun visitPrimitive(
        descriptor: SerialDescriptor,
        kind: PrimitiveKind,
    ) = setSchema(kind.toSchema())

    override fun visitEnum(descriptor: SerialDescriptor) {
        val annotations = TypeAnnotations(descriptor)
        val name = namedSchemaName(descriptor.nonNullSerialName)

        val schema =
            EnumSchema(
                name = name,
                symbols = List(descriptor.elementsCount) { validateAvroName(descriptor.getElementName(it)) },
                defaultSymbol = context.avro.enumResolver.getDefaultValueIndex(descriptor)?.let { descriptor.getElementName(it) },
                doc = annotations.doc?.value,
                aliases = annotations.aliases.toSchemaAliases(name),
                props = annotations.props.toSchemaProps(ENUM_RESERVED_PROPS)
            )

        setSchema(schema)
    }

    override fun visitObject(descriptor: SerialDescriptor) {
        // we consider objects as records without fields.
        visitClass(descriptor).endClassVisit(descriptor)
    }

    override fun visitClass(descriptor: SerialDescriptor) = ClassVisitor(descriptor, context.copy(inlinedElements = emptyList())) { setSchema(it) }

    @OptIn(ExperimentalSerializationApi::class)
    override fun visitPolymorphic(
        descriptor: SerialDescriptor,
        kind: PolymorphicKind,
    ) = PolymorphicVisitor(context) { setSchema(it) }

    override fun visitList(descriptor: SerialDescriptor) = ListVisitor(context.copy(inlinedElements = emptyList())) { setSchema(it) }

    override fun visitMap(descriptor: SerialDescriptor) = MapVisitor(context.copy(inlinedElements = emptyList())) { setSchema(it) }

    override fun visitInlineClass(descriptor: SerialDescriptor) = InlineClassVisitor(context) { setSchema(it) }

    private fun setSchema(builtSchema: AvroSchema) {
        val schema = context.shareNamedSchema(builtSchema)
        if (isNullable && !schema.isNullable) {
            onSchemaBuilt(schema.nullable)
        } else {
            onSchemaBuilt(schema)
        }
    }

    /**
     * Every element of the generated schema is visited here, so this is where a failure gets the path of the element:
     * the innermost visitor wraps it first, and the outer ones pass an [AvroSchemaGenerationException] through as is. The
     * model's own checks (`require`), custom serializers' `getSchema` and annotation checks are wrapped with their
     * exception as the cause; the failures avro4k throws itself ([SchemaGenerationFailure]) keep their message, without a cause.
     */
    override fun visitValue(descriptor: SerialDescriptor) {
        try {
            visitValueWithoutPath(descriptor)
        } catch (e: AvroSchemaGenerationException) {
            throw e
        } catch (e: SchemaGenerationFailure) {
            throw AvroSchemaGenerationException(e.message ?: "", context.path.toString())
        } catch (e: Exception) {
            throw AvroSchemaGenerationException(e.message ?: e::class.nameForMessages, context.path.toString(), e)
        }
    }

    private fun visitValueWithoutPath(descriptor: SerialDescriptor) {
        val finalDescriptor = SerializerLocatorMiddleware.apply(unwrapNullable(descriptor))
        descriptor.findAnnotation<AvroGenerated>()?.let {
            // Ignore everything and use the provided schema
            setSchema(AvroSchema.fromJsonString(it.originalSchema))
            return
        }

        if (finalDescriptor is AvroSchemaSupplier) {
            setSchema(finalDescriptor.getAvroSchema(context))
            return
        }

        if (context.inlinedElements.any { it.stringable != null }) {
            setSchema(StringSchema())
            return
        }

        super.visitValue(finalDescriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun unwrapNullable(descriptor: SerialDescriptor): SerialDescriptor {
        if (descriptor.isNullable) {
            isNullable = true
            return descriptor.nonNullOriginal
        }
        return descriptor
    }
}

internal const val CHAR_LOGICAL_TYPE_NAME = "char"

/**
 * A new instance on each call, never a shared constant: the Apache form of a generated schema (`toApacheSchema()`) pairs
 * each of its nodes with its Apache node, and a node shared by several generated schemas could only be paired once.
 */
private fun PrimitiveKind.toSchema(): AvroSchema =
    when (this) {
        PrimitiveKind.BOOLEAN -> BooleanSchema()
        PrimitiveKind.CHAR -> IntSchema(mapOf("logicalType" to JsonPrimitive(CHAR_LOGICAL_TYPE_NAME)))
        PrimitiveKind.BYTE -> IntSchema()
        PrimitiveKind.SHORT -> IntSchema()
        PrimitiveKind.INT -> IntSchema()
        PrimitiveKind.LONG -> LongSchema()
        PrimitiveKind.FLOAT -> FloatSchema()
        PrimitiveKind.DOUBLE -> DoubleSchema()
        PrimitiveKind.STRING -> StringSchema()
    }