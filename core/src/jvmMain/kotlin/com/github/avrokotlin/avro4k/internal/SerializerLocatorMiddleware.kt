@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.internal.decoder.direct.AbstractAvroDirectDecoder
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import com.github.avrokotlin.avro4k.serializer.AvroDurationSerializer
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinInstantSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import com.github.avrokotlin.avro4k.serializer.SerialDescriptorWithAvroSchemaDelegate
import com.github.avrokotlin.avro4k.serializer.createSchema
import com.github.avrokotlin.avro4k.serializer.fixed
import com.github.avrokotlin.avro4k.serializer.stringable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.AbstractCollectionSerializer
import org.apache.avro.Schema
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * This middleware is here to intercept some native types like kotlin Duration or ByteArray as we want to apply some
 * specific rules on them for generating custom schemas or having specific serialization strategies.
 */
@Suppress("UNCHECKED_CAST")
internal object SerializerLocatorMiddleware {
    // Identity-keyed, like the IdentityHashMaps these used to be, but platform-neutral. Every decoded value goes
    // through [apply], so a scan over these few entries must stay a handful of reference compares, with no allocation.
    private val serializers: IdentityLookup<KSerializer<*>, KSerializer<*>> =
        IdentityLookup(
            buildList {
                add(ByteArraySerializer() to AvroByteArraySerializer)
                add(Duration.serializer() to KotlinDurationSerializer)
                add(Uuid.serializer() to KotlinUuidSerializer)
                runCatching { add(Instant.serializer() to KotlinInstantSerializer) }
            }
        )

    private val descriptors: IdentityLookup<SerialDescriptor, SerialDescriptor> =
        IdentityLookup(
            buildList {
                add(ByteArraySerializer().descriptor to AvroByteArraySerializer.descriptor)
                add(String.serializer().descriptor to AvroStringSerialDescriptor)
                add(Duration.serializer().descriptor to KotlinDurationSerializer.descriptor)
                add(Uuid.serializer().descriptor to KotlinUuidSerializer.descriptor)
                runCatching { add(Instant.serializer().descriptor to KotlinInstantSerializer.descriptor) }
            }
        )

    fun <T> apply(serializer: SerializationStrategy<T>): SerializationStrategy<T> {
        serializers[serializer]?.let { return it as SerializationStrategy<T> }

        return serializer
    }

    @OptIn(InternalSerializationApi::class)
    fun <T> apply(deserializer: DeserializationStrategy<T>): DeserializationStrategy<T> {
        serializers[deserializer]?.let { return it as DeserializationStrategy<T> }
        (deserializer as? AbstractCollectionSerializer<*, T, *>)?.let { return wrapCollection(it) }

        return deserializer
    }

    /** Deliberately not `@Volatile`: see [wrapCollection]. */
    private var cachedCollectionDeserializer: CachedCollectionDeserializer? = null

    /**
     * [AbstractAvroDirectDecoder.decodeSerializableValue] runs [apply] for every decoded value, so a collection used to
     * allocate a brand new [AvroCollectionSerializer] every single time it was decoded. The wrapper only holds its
     * `original` and keeps no decoding state (the state lives on the decoder), so one wrapper instance can safely be
     * shared across calls and across threads: it is memoized here.
     *
     * The memoization is a **one-entry inline cache** on purpose:
     * - A map keyed by the serializer instance would leak. Compiler-generated serializers are per-type singletons, but
     *   `ListSerializer(elementSerializer)` / `MapSerializer(...)` hand out a *fresh* [AbstractCollectionSerializer]
     *   on every call, so a global strong-keyed map would grow without bound in a long-running process.
     * - A weak-keyed cache would be leak-free but re-introduces a per-call lookup that allocates, which is exactly
     *   what this is trying to remove.
     *
     * Decoding is overwhelmingly repetitive - the same collection serializer comes back for every element of the
     * enclosing collection - so a single entry captures nearly all of the wins while staying bounded.
     *
     * Thread safety: the cached key and value live in **one** immutable holder behind **one** non-volatile reference.
     * A single reference read/write is atomic on every supported platform, so a racing thread either sees a fully
     * consistent holder or a stale/null one and simply falls through to allocating a new wrapper - never a wrapper
     * paired with the wrong `original`. Splitting the key and the value into two fields would allow exactly that torn
     * read, which is why they are kept together.
     */
    @OptIn(InternalSerializationApi::class)
    private fun <T> wrapCollection(deserializer: AbstractCollectionSerializer<*, T, *>): DeserializationStrategy<T> {
        val cached = cachedCollectionDeserializer
        if (cached != null && cached.original === deserializer) {
            return cached.wrapped as DeserializationStrategy<T>
        }
        val wrapped = AvroCollectionSerializer(deserializer)
        cachedCollectionDeserializer = CachedCollectionDeserializer(deserializer, wrapped)
        return wrapped
    }

    fun apply(descriptor: SerialDescriptor): SerialDescriptor {
        return descriptors[descriptor] ?: descriptor
    }
}

/**
 * Immutable (key, value) pair for the one-entry collection-wrapper cache of [SerializerLocatorMiddleware].
 * Both fields must always be published together - see [SerializerLocatorMiddleware.wrapCollection].
 */
@OptIn(InternalSerializationApi::class)
private class CachedCollectionDeserializer(
    @JvmField val original: AbstractCollectionSerializer<*, *, *>,
    @JvmField val wrapped: AvroCollectionSerializer<*>,
)

private val AvroStringSerialDescriptor: SerialDescriptor =
    SerialDescriptorWithAvroSchemaDelegate(String.serializer().descriptor) { context ->
        context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema() ?: it.fixed?.createSchema(it)
        } ?: Schema.create(Schema.Type.STRING)
    }

private object KotlinDurationSerializer : AvroSerializer<Duration>(Duration::class.qualifiedName!!) {
    private const val MILLIS_PER_DAY = 1000 * 60 * 60 * 24

    override fun getSchema(context: SchemaSupplierContext): Schema {
        return context.inlinedElements.firstNotNullOfOrNull { it.stringable?.createSchema() }
            ?: AvroDurationSerializer.DURATION_SCHEMA
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Duration,
    ) {
        AvroDurationSerializer.serializeAvro(encoder, value.toAvroDuration())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Duration {
        return AvroDurationSerializer.deserializeAvro(decoder).toKotlinDuration()
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: Duration,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeGeneric(decoder: Decoder): Duration {
        return Duration.parse(decoder.decodeString())
    }

    private fun AvroDuration.toKotlinDuration(): Duration {
        if (months == UInt.MAX_VALUE && days == UInt.MAX_VALUE && millis == UInt.MAX_VALUE) {
            return Duration.INFINITE
        }
        if (months != 0u) {
            throw SerializationException("java.time.Duration cannot contains months")
        }
        return days.toLong().days + millis.toLong().milliseconds
    }

    private fun Duration.toAvroDuration(): AvroDuration {
        if (isNegative()) {
            throw SerializationException("${Duration::class.qualifiedName} cannot be converted to ${AvroDuration::class.qualifiedName} as it cannot be negative")
        }
        if (isInfinite()) {
            return AvroDuration(
                months = UInt.MAX_VALUE,
                days = UInt.MAX_VALUE,
                millis = UInt.MAX_VALUE
            )
        }
        val millis = inWholeMilliseconds
        return AvroDuration(
            months = 0u,
            days = (millis / MILLIS_PER_DAY).toUInt(),
            millis = (millis % MILLIS_PER_DAY).toUInt()
        )
    }
}

private object AvroByteArraySerializer : AvroSerializer<ByteArray>(ByteArray::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): Schema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema() ?: it.fixed?.createSchema(it)
        } ?: Schema.create(Schema.Type.BYTES)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: ByteArray,
    ) {
        // encoding related to the type (fixed or bytes) is handled in AvroEncoder
        encoder.encodeBytes(value)
    }

    override fun deserializeAvro(decoder: AvroDecoder): ByteArray {
        // decoding related to the type (fixed or bytes) is handled in AvroDecoder
        return decoder.decodeBytes()
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun serializeGeneric(
        encoder: Encoder,
        value: ByteArray,
    ) {
        encoder.encodeString(Base64.Mime.encode(value))
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun deserializeGeneric(decoder: Decoder): ByteArray {
        return Base64.Mime.decode(decoder.decodeString())
    }
}

@OptIn(InternalSerializationApi::class)
internal class AvroCollectionSerializer<T>(private val original: AbstractCollectionSerializer<*, T, *>) : KSerializer<T> {
    override val descriptor: SerialDescriptor
        get() = original.descriptor

    override fun deserialize(decoder: Decoder): T {
        if (decoder is AbstractAvroDirectDecoder) {
            var result: T? = null
            decoder.decodedCollectionSize = -1
            do {
                result = original.merge(decoder, result)
            } while (decoder.decodedCollectionSize > 0)
            return result!!
        }
        return original.deserialize(decoder)
    }

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        original.serialize(encoder, value)
    }
}