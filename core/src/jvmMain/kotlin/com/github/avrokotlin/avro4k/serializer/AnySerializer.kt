package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * This serializer handles [Any] type, for both encoding and decoding.
 *
 * At encoding, it barely gets the [KSerializer] from the value's type (see [inferSerializationStrategyFromNonSerializableType] for customization).
 *
 * At decoding, it delegates the process to the inferred [KSerializer] from the [AvroSchema.type] (example: [AvroSchema.Type.BOOLEAN] is handled by the [Boolean]'s serializer).
 * For more customization about named types, see [resolveFixedDeserializationStrategy], [resolveEnumDeserializationStrategy], and [resolveRecordDeserializationStrategy].
 */
@InternalAvro4kApi
public open class AnySerializer : AbstractAnySerializer() {
    // No need to use a WeakHashMap with class keys
    private val encodingCache = ConcurrentHashMap<Class<out Any>, SerializationStrategy<Any>>()

    override fun serialize(
        encoder: Encoder,
        value: Any,
    ) {
        val serializer = encodingCache.computeIfAbsent(value::class.java) { encoder.serializersModule.inferSerializationStrategy(it) }
        encoder.encodeSerializableValue(serializer, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun SerializersModule.inferSerializationStrategy(type: Class<out Any>): SerializationStrategy<Any> {
        // First, try to find the serializer in the serializers module as-is. Here generic types are resolved if possible
        serializerOrNull(type)?.let {
            return@inferSerializationStrategy it
        }
        // Then, try to find the serializer for the type with its type parameters as nullable Any. Any parameterized types will be handled as nullable Any.
        // This handles all the collections, maps, kotlin pair, triple, etc.
        @OptIn(ExperimentalSerializationApi::class)
        runCatching { serializer(type.kotlin, type.typeParameters.map { this@AnySerializer.nullable }, false) }.getOrNull()?.let {
            return@inferSerializationStrategy it as KSerializer<Any>
        }

        if (Collection::class.java.isAssignableFrom(type)) {
            return ListSerializer(this@AnySerializer.nullable) as KSerializer<Any>
        }

        if (Map::class.java.isAssignableFrom(type)) {
            return MapSerializer(this@AnySerializer.nullable, this@AnySerializer.nullable) as KSerializer<Any>
        }

        return inferSerializationStrategyFromNonSerializableType(type) as SerializationStrategy<Any>?
            ?: throw SerializationException("Cannot find serializer for type $type. Please register a serializer for this type in the serializers module.")
    }

    /**
     * Called when avro4k did not find the corresponding [type]'s serializer in the [com.github.avrokotlin.avro4k.Avro.serializersModule].
     *
     * Generally useful when you don't know the exact type at runtime, but the interface.
     *
     * Results are cached, so expect only one call per [type].
     *
     * @return the serializer to use for the given [type], or `null` if none, which will raise a [SerializationException].
     */
    @InternalAvro4kApi
    protected open fun SerializersModule.inferSerializationStrategyFromNonSerializableType(type: Class<out Any>): SerializationStrategy<*>? {
        return null
    }
}