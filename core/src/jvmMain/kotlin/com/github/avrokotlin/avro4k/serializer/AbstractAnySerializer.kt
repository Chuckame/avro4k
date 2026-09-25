package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroConfiguration
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.IdentityFirstCache
import com.github.avrokotlin.avro4k.internal.decoder.direct.AbstractAvroDirectDecoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

internal val AnyTypeSerializersModule: SerializersModule
    get() =
        SerializersModule {
            contextual(AnySerializer())
        }

/**
 * The part of [AnySerializer] shared by every platform: decoding, which infers the strategy from the writer schema, and
 * its customization hooks. Encoding infers the strategy from the value's runtime type, which only [AnySerializer]
 * knows how to inspect on each platform.
 *
 * At decoding, it delegates the process to the inferred [KSerializer] from the [AvroSchema.type] (example: [AvroSchema.Type.BOOLEAN] is handled by the [Boolean]'s serializer).
 * For more customization about named types, see [resolveFixedDeserializationStrategy], [resolveEnumDeserializationStrategy], and [resolveRecordDeserializationStrategy].
 */
@InternalAvro4kApi
public abstract class AbstractAnySerializer internal constructor() : KSerializer<Any> {
    /**
     * By writer schema, identity first, backed by equality: a writer schema handed in fresh (but equal) on every call —
     * parsed per message, or re-parsed by a registry lookup — hits the equality layer instead of resolving its strategy
     * again, and is never remembered by identity (see [IdentityFirstCache]). `internal` for tests.
     */
    internal val decodingCache = IdentityFirstCache<AvroSchema, DeserializationStrategy<Any>>()

    /**
     * Provides the nullable version of this serializer.
     *
     * Prefer use this instead of [KSerializer.nullable] extension to prevent useless allocations.
     */
    public val nullable: KSerializer<Any?> = nullableSerializer(this)

    /**
     * Uses a method to provide a nullable serializer for the given [serializer].
     * Helps to not use the [AbstractAnySerializer.nullable], so it does not loop on itself.
     */
    private fun <T : Any> nullableSerializer(serializer: KSerializer<T>): KSerializer<T?> {
        return serializer.nullable
    }

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor
        get() = buildSerialDescriptor("kotlin.Any", SerialKind.CONTEXTUAL)

    override fun deserialize(decoder: Decoder): Any {
        decoder as AbstractAvroDirectDecoder
        val writerSchema = decoder.currentAvroSchema
        // getOrPut is inline: no capturing lambda is allocated per call.
        val serializer =
            decodingCache.getOrPut(writerSchema) {
                decoder.serializersModule.resolveDeserializationStrategy(writerSchema, decoder.avro.configuration)
            }
        return decoder.decodeSerializableValue(serializer)
    }

    /**
     * Only runs on a miss of [decodingCache].
     */
    private fun SerializersModule.resolveDeserializationStrategy(
        writerSchema: AvroSchema,
        configuration: AvroConfiguration,
    ): DeserializationStrategy<Any> {
        preResolveDeserializationStrategy(writerSchema)?.let { return it }
        // Let's try to use the logical type's serializer if any
        writerSchema.logicalTypeName?.let { configuration.logicalTypes[it] }?.let { return it }
        // Else infer the serializer from the schema type
        return when (writerSchema.type) {
            AvroSchema.Type.BOOLEAN -> Boolean.serializer()

            AvroSchema.Type.INT -> Int.serializer()

            AvroSchema.Type.LONG -> Long.serializer()

            AvroSchema.Type.FLOAT -> Float.serializer()

            AvroSchema.Type.DOUBLE -> Double.serializer()

            AvroSchema.Type.STRING -> String.serializer()

            AvroSchema.Type.BYTES -> ByteArraySerializer()

            // Named types and collections can be decoded differently depending on the needs
            AvroSchema.Type.ARRAY -> resolveArrayDeserializationStrategy(writerSchema)

            AvroSchema.Type.MAP -> resolveMapDeserializationStrategy(writerSchema)

            AvroSchema.Type.FIXED -> resolveFixedDeserializationStrategy(writerSchema)

            AvroSchema.Type.ENUM -> resolveEnumDeserializationStrategy(writerSchema)

            AvroSchema.Type.RECORD -> resolveRecordDeserializationStrategy(writerSchema)

            // Having an union here is an avro4k error, or a wrong usage of this serializer
            AvroSchema.Type.UNION -> throw UnsupportedOperationException(
                "Do not directly decode values with ${this::class}. " +
                    "Please use Avro.decode methods instead. " +
                    "(error: had to get deserializer of union, which should be resolved by internal decoders before calling deserializers)"
            )

            // Having a null here is an avro4k error, or a wrong usage of this serializer
            AvroSchema.Type.NULL -> throw UnsupportedOperationException(
                "Do not directly decode values with ${this::class}. " +
                    "Please use Avro.decode methods instead. " +
                    "(error: had to get deserializer of null, which should be handled by internal decoders)"
            )
        }
    }

    /**
     * Allows to provide a serializer for the given [writerSchema] before checking its [AvroSchema.type] or its [AvroSchema.logicalTypeName].
     * Useful to provide a custom serializer depending on a custom property.
     *
     * @param writerSchema the schema from which the value is being decoded
     * @return the serializer to be used, or `null` (the default) if no serializer is found.
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.preResolveDeserializationStrategy(writerSchema: AvroSchema): DeserializationStrategy<Any>? {
        return null
    }

    /**
     * Provides the serializer for the given array schema.
     * [resolveArrayDeserializationStrategy] won't be called if the [writerSchema] has its [AvroSchema.logicalTypeName] found in [AvroConfiguration.logicalTypes].
     *
     * By default, an array value is decoded as a `List<Any?>`.
     *
     * Results are cached, so expect only one call per [writerSchema].
     *
     * @param writerSchema the schema always being of the type [AvroSchema.Type.ARRAY]
     * @return the serializer to be used for array types when decoding
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.resolveArrayDeserializationStrategy(writerSchema: AvroSchema): DeserializationStrategy<Any> {
        return ListSerializer(this@AbstractAnySerializer.nullable)
    }

    /**
     * Provides the serializer for the given map schema.
     * [resolveMapDeserializationStrategy] won't be called if the [writerSchema] has its [AvroSchema.logicalTypeName] found in [AvroConfiguration.logicalTypes].
     *
     * By default, a map value is decoded as a `Map<String, Any?>`.
     *
     * Results are cached, so expect only one call per [writerSchema].
     *
     * @param writerSchema the schema always being of the type [AvroSchema.Type.MAP]
     * @return the serializer to be used for map types when decoding
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.resolveMapDeserializationStrategy(writerSchema: AvroSchema): DeserializationStrategy<Any> {
        return MapSerializer(String.serializer(), this@AbstractAnySerializer.nullable)
    }

    /**
     * Provides the serializer for the given enum schema.
     * [resolveEnumDeserializationStrategy] won't be called if the [writerSchema] has its [AvroSchema.logicalTypeName] found in [AvroConfiguration.logicalTypes].
     *
     * By default, an enum value is decoded as a [String].
     *
     * Results are cached, so expect only one call per [writerSchema].
     *
     * @param writerSchema the schema always being of the type [AvroSchema.Type.ENUM]
     * @return the serializer to be used for enum types when decoding
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.resolveEnumDeserializationStrategy(writerSchema: AvroSchema): DeserializationStrategy<Any> {
        return String.serializer()
    }

    /**
     * Provides the serializer for the given fixed schema.
     * [resolveFixedDeserializationStrategy] won't be called if the [writerSchema] has its [AvroSchema.logicalTypeName] found in [AvroConfiguration.logicalTypes].
     *
     * By default, a fixed value is decoded as a [ByteArray].
     *
     * Results are cached, so expect only one call per [writerSchema].
     *
     * @param writerSchema the schema always being of the type [AvroSchema.Type.FIXED]
     * @return the serializer to be used for fixed types when decoding
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.resolveFixedDeserializationStrategy(writerSchema: AvroSchema): DeserializationStrategy<Any> {
        return ByteArraySerializer()
    }

    /**
     * Provides the serializer for the given record schema.
     * [resolveRecordDeserializationStrategy] won't be called if the [writerSchema] has its [AvroSchema.logicalTypeName] found in [AvroConfiguration.logicalTypes].
     *
     * By default, a record is decoded as a [Map], where the keys are field names as [String] and the values are field values.
     *
     * Important notes:
     * For recursively resolve a subtype, do provide a nullable [AnySerializer] to allow resolving the schema at runtime depending on the [writerSchema].
     * Also, results are cached, so expect only one call per [writerSchema].
     *
     * @param writerSchema the schema always being of the type [AvroSchema.Type.RECORD]
     * @return the serializer to be used for record types when decoding
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.resolveRecordDeserializationStrategy(writerSchema: AvroSchema): DeserializationStrategy<Any> {
        return MapSerializer(String.serializer(), this@AbstractAnySerializer.nullable)
    }
}