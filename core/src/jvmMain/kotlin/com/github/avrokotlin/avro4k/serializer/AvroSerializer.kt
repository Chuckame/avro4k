package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroConfiguration
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroStringable
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.findAnnotation
import com.github.avrokotlin.avro4k.internal.findElementAnnotation
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Base class for custom Avro serializers. It also provides a way to define custom Avro schema.
 *
 * Use it at your own risk, as it's directly bypassing the internal checks, so you can have runtime errors.
 *
 * Don't forget to implement [serializeGeneric] and [deserializeGeneric] if you want to use the serializer outside the Avro serialization, like with json format.
 */
public abstract class AvroSerializer<T>(
    descriptorName: String,
) : KSerializer<T>, AvroSchemaSupplier {
    @Suppress("LeakingThis")
    @OptIn(InternalSerializationApi::class)
    final override val descriptor: SerialDescriptor =
        SerialDescriptorWithAvroSchemaDelegate(buildSerialDescriptor(descriptorName, SerialKind.CONTEXTUAL), this)
            .let { if (supportsNull) it.nullable else it }

    /**
     * Indicates if this serializer is able to serialize and deserialize a null value.
     * It allows materializing an encoded null value by a specific non-null instance (like an object placeholder).
     *
     * `false` by default, which means a `null` avro encoded value will always be represented by a nullable field.
     */
    public open val supportsNull: Boolean
        get() = false

    final override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        if (encoder is AvroEncoder) {
            serializeAvro(encoder, value)
            return
        }
        serializeGeneric(encoder, value)
    }

    final override fun deserialize(decoder: Decoder): T {
        if (decoder is AvroDecoder) {
            return deserializeAvro(decoder)
        }
        return deserializeGeneric(decoder)
    }

    /**
     * This method is called when the serializer is used outside Avro serialization.
     * By default, it throws an exception.
     *
     * Implement it to provide a generic serialization logic with the standard [Encoder].
     */
    public open fun serializeGeneric(
        encoder: Encoder,
        value: T,
    ) {
        throw UnsupportedOperationException("The serializer ${this::class.qualifiedName} is not usable outside of Avro serialization.")
    }

    /**
     * Serialize the value using an Avro encoder. It is highly recommended to use `encoder.encodeResolving` methods. See [AvroEncoder] for more details.
     */
    public abstract fun serializeAvro(
        encoder: AvroEncoder,
        value: T,
    )

    /**
     * This method is called when the serializer is used outside Avro serialization.
     * By default, it throws an exception.
     *
     * Implement it to provide a generic deserialization logic with the standard [Decoder].
     */
    public open fun deserializeGeneric(decoder: Decoder): T {
        throw UnsupportedOperationException("The serializer ${this::class.qualifiedName} is not usable outside of Avro serialization.")
    }

    /**
     * Deserialize the value from an Avro decoder. It is highly recommended to use `decoder.decodeResolvingXx` methods. See [AvroDecoder] for more details.
     */
    public abstract fun deserializeAvro(decoder: AvroDecoder): T
}

@ExperimentalAvro4kApi
public interface SchemaSupplierContext {
    public val configuration: AvroConfiguration

    /**
     * Corresponds to the elements-tree, always starting from the data class property.
     *
     * The first element is the data class property, and the next elements are the inlined elements when the property type is a value class.
     */
    public val inlinedElements: List<ElementLocation>
}

/**
 * Search for the first annotation of type [T] in the given [ElementLocation].
 */
@ExperimentalAvro4kApi
public inline fun <reified T : Annotation> ElementLocation.findAnnotation(): T? {
    return descriptor.findElementAnnotation(elementIndex)
}

/**
 * Shorthand for [findAnnotation] with [AvroDecimal] as it is a built-in annotation.
 */
@ExperimentalAvro4kApi
public val ElementLocation.decimal: AvroDecimal?
    get() = findAnnotation()

/**
 * Shorthand for [findAnnotation] with [AvroStringable] as it is a built-in annotation.
 */
@ExperimentalAvro4kApi
public val ElementLocation.stringable: AvroStringable?
    get() = findAnnotation()

/**
 * Creates a string schema from the [AvroStringable] annotation.
 */
@ExperimentalAvro4kApi
public fun AvroStringable.createSchema(): AvroSchema = AvroSchema.StringSchema()

/**
 * Shorthand for [findAnnotation] with [AvroFixed] as it is a built-in annotation.
 */
@ExperimentalAvro4kApi
public val ElementLocation.fixed: AvroFixed?
    get() = findAnnotation()

/**
 * Creates a fixed schema from the [AvroFixed] annotation, found at [elementLocation]. Its name depends on where the annotation is:
 * - on a **value class's own property**: the fixed is named after the value class's serial name (so `@SerialName` sets it,
 *   and a serial name without a dot means no namespace, as for records). The value class's [AvroAlias] and [AvroDoc] are the
 *   fixed's aliases (relative to its namespace, as for records) and doc.
 * - on a **data class property**: the fixed is named after the property's element name, in the namespace of the record's full
 *   name (e.g. `com.example.MyRecord.myField`), so that two records' fields never have the same fixed name.
 */
@ExperimentalAvro4kApi
public fun AvroFixed.createSchema(elementLocation: ElementLocation): AvroSchema {
    val descriptor = elementLocation.descriptor
    if (descriptor.isInline) {
        // A name with a dot is a full name, and then the namespace argument is ignored
        val name = Name(descriptor.nonNullSerialName, null)
        return AvroSchema.FixedSchema(
            name = name,
            size = size,
            doc = descriptor.findAnnotation<AvroDoc>()?.value,
            // relative to the fixed's namespace; its own full name as an alias is a no-op, dropped as the model rejects it
            aliases = descriptor.findAnnotation<AvroAlias>()?.value?.mapNotNullTo(LinkedHashSet()) { alias -> Name(alias, name.space).takeIf { it != name } } ?: emptySet()
        )
    }
    return AvroSchema.FixedSchema(Name(descriptor.getElementName(elementLocation.elementIndex), descriptor.nonNullSerialName), size)
}

@ExperimentalAvro4kApi
public data class ElementLocation
    internal constructor(
        val descriptor: SerialDescriptor,
        val elementIndex: Int,
    )

internal fun interface AvroSchemaSupplier {
    fun getSchema(context: SchemaSupplierContext): AvroSchema
}

/**
 * A schema of [type] (a primitive, or bytes) with the logical type [logicalTypeName]: the `logicalType` prop, then
 * [additionalProps], in this order.
 */
internal fun logicalTypeSchema(
    type: AvroSchema.Type,
    logicalTypeName: String,
    vararg additionalProps: Pair<String, JsonElement>,
): AvroSchema {
    val props = logicalTypeProps(emptyMap(), logicalTypeName, additionalProps)
    return when (type) {
        AvroSchema.Type.BOOLEAN -> AvroSchema.BooleanSchema(props)
        AvroSchema.Type.INT -> AvroSchema.IntSchema(props)
        AvroSchema.Type.LONG -> AvroSchema.LongSchema(props)
        AvroSchema.Type.FLOAT -> AvroSchema.FloatSchema(props)
        AvroSchema.Type.DOUBLE -> AvroSchema.DoubleSchema(props)
        AvroSchema.Type.STRING -> AvroSchema.StringSchema(props)
        AvroSchema.Type.BYTES -> AvroSchema.BytesSchema(props)
        else -> throw IllegalArgumentException("Not a primitive type: $type")
    }
}

/**
 * A copy of this schema (a fixed, a primitive or bytes) with the logical type [logicalTypeName], after its own props,
 * then [additionalProps]: the props Apache's `LogicalType.addToSchema` adds, in its order.
 */
internal fun AvroSchema.withLogicalType(
    logicalTypeName: String,
    vararg additionalProps: Pair<String, JsonElement>,
): AvroSchema {
    val props = logicalTypeProps((this as ResolvedSchema).props, logicalTypeName, additionalProps)
    return when (this) {
        is AvroSchema.FixedSchema -> copy(props = props)
        is AvroSchema.BooleanSchema -> copy(props = props)
        is AvroSchema.IntSchema -> copy(props = props)
        is AvroSchema.LongSchema -> copy(props = props)
        is AvroSchema.FloatSchema -> copy(props = props)
        is AvroSchema.DoubleSchema -> copy(props = props)
        is AvroSchema.StringSchema -> copy(props = props)
        is AvroSchema.BytesSchema -> copy(props = props)
        else -> throw IllegalArgumentException("A logical type cannot be added to a $type schema")
    }
}

private fun logicalTypeProps(
    props: Map<String, JsonElement>,
    logicalTypeName: String,
    additionalProps: Array<out Pair<String, JsonElement>>,
): Map<String, JsonElement> =
    LinkedHashMap(props).apply {
        put("logicalType", JsonPrimitive(logicalTypeName))
        additionalProps.forEach { (key, value) -> put(key, value) }
    }

/** The schema of this supplier, for the schema generation. It runs once per generated schema: `Avro.schema()` caches it. */
internal fun AvroSchemaSupplier.getAvroSchema(context: SchemaSupplierContext): AvroSchema = getSchema(context)

@OptIn(SealedSerializationApi::class)
internal class SerialDescriptorWithAvroSchemaDelegate(
    private val descriptor: SerialDescriptor,
    private val schemaSupplier: AvroSchemaSupplier,
) : SerialDescriptor by descriptor, AvroSchemaSupplier by schemaSupplier {
    override fun toString(): String {
        return "${descriptor.serialName}(<custom schema>)"
    }
}