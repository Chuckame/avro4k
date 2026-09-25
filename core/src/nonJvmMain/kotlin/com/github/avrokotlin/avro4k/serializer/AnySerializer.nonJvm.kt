package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializerOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

/**
 * This serializer handles [Any] type, for both encoding and decoding.
 *
 * At encoding, it gets the [KSerializer] from the value's class: the class's own serializer, then the contextual one of
 * the serializers module, then a list or a map of nullable [Any] for any [Collection] or [Map], and finally
 * [inferSerializationStrategyFromNonSerializableType] for customization.
 *
 * Without reflection, the type arguments of a class are unknown at runtime, so a generic serializable class that is
 * neither a [Collection] nor a [Map] (e.g. [Pair]) is not inferred here, unlike on the JVM: register a contextual
 * serializer for it, or override [inferSerializationStrategyFromNonSerializableType].
 *
 * At decoding, it delegates the process to the inferred [KSerializer] from the [AvroSchema.type] (example: [AvroSchema.Type.BOOLEAN] is handled by the [Boolean]'s serializer).
 * For more customization about named types, see [resolveFixedDeserializationStrategy], [resolveEnumDeserializationStrategy], and [resolveRecordDeserializationStrategy].
 */
@InternalAvro4kApi
@OptIn(ExperimentalAtomicApi::class)
public actual open class AnySerializer actual constructor() : AbstractAnySerializer() {
    /**
     * The inferred serializer of each class, held strongly: classes are never unloaded on JS or native, so the map is
     * bounded by the classes encoded as [Any]. A weak map would lose entries at each collection if the platform gives
     * a fresh [KClass] object for each `::class`, while [KClass] equality still matches them here.
     *
     * Copy-on-write: a map is never mutated once published through the atomic, whose stdlib contract makes every write
     * that built it visible to a thread that reads it. A lost race only costs one extra inference.
     */
    private val encodingCache = AtomicReference<Map<KClass<out Any>, SerializationStrategy<Any>>>(emptyMap())

    /** The number of classes whose serializer is cached. */
    internal val encodingCacheSize: Int get() = encodingCache.load().size

    actual override fun serialize(
        encoder: Encoder,
        value: Any,
    ) {
        val type = value::class
        val serializer = encodingCache.load()[type] ?: cache(type, encoder.serializersModule.inferSerializationStrategy(value))
        encoder.encodeSerializableValue(serializer, value)
    }

    private fun cache(
        type: KClass<out Any>,
        serializer: SerializationStrategy<Any>,
    ): SerializationStrategy<Any> {
        while (true) {
            val current = encodingCache.load()
            current[type]?.let { return it }
            if (encodingCache.compareAndSet(current, current + (type to serializer))) return serializer
        }
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun SerializersModule.inferSerializationStrategy(value: Any): SerializationStrategy<Any> {
        val type = value::class
        // the JVM's order: the class's own serializer, then the module's contextual one
        (type.serializerOrNull() ?: getContextual(type))?.let { return it as SerializationStrategy<Any> }
        if (value is Collection<*>) return ListSerializer(this@AnySerializer.nullable) as KSerializer<Any>
        if (value is Map<*, *>) return MapSerializer(this@AnySerializer.nullable, this@AnySerializer.nullable) as KSerializer<Any>
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
    protected open fun SerializersModule.inferSerializationStrategyFromNonSerializableType(type: KClass<out Any>): SerializationStrategy<*>? = null
}