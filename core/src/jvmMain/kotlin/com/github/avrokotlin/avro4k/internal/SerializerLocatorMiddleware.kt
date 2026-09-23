@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
    /*
     * The intercepted built-in types. **Adding or changing one means updating both [interceptedSerializer] and
     * [apply] (descriptor)** — `SerializerLocatorMiddlewareTest` lists every intercepted type once and checks both.
     *
     * Why `when` chains of `===` over these static finals, rather than a map: `apply` runs on every encoded and
     * decoded value and nearly always misses. Measured by `benchmark/.../micro/IdentityLookupMicroBenchmark.kt`
     * (see benchmark/README.md, "Results — M2"): per lookup, the `when` chain costs 0.75 ns on a miss, an
     * `IdentityHashMap` 1.24 ns, and a linear `===` scan over an array 2.39 ns — the JIT compares against the static
     * finals as constants. It is also platform-neutral and allocation-free, and keeps identity semantics (`===`, never
     * `equals`: a plain `when (x) { A -> }` would compare with `equals`).
     */
    private val byteArraySerializer = ByteArraySerializer()
    private val durationSerializer = Duration.serializer()
    private val uuidSerializer = Uuid.serializer()

    /** Null when the kotlinx-serialization on the classpath predates `kotlin.time.Instant` support. */
    private val instantSerializer: KSerializer<Instant>? = runCatching { Instant.serializer() }.getOrNull()

    private val byteArrayDescriptor = byteArraySerializer.descriptor
    private val stringDescriptor = String.serializer().descriptor
    private val durationDescriptor = durationSerializer.descriptor
    private val uuidDescriptor = uuidSerializer.descriptor
    private val instantDescriptor: SerialDescriptor? = instantSerializer?.descriptor

    private fun interceptedSerializer(serializer: Any): KSerializer<*>? =
        when {
            serializer === byteArraySerializer -> AvroByteArraySerializer
            serializer === durationSerializer -> KotlinDurationSerializer
            serializer === uuidSerializer -> KotlinUuidSerializer
            serializer === instantSerializer -> KotlinInstantSerializer
            else -> null
        }

    fun apply(descriptor: SerialDescriptor): SerialDescriptor =
        when {
            descriptor === byteArrayDescriptor -> AvroByteArraySerializer.descriptor

            // Descriptor only: strings keep kotlinx's serializer, only their schema is avro4k's.
            descriptor === stringDescriptor -> AvroStringSerialDescriptor

            descriptor === durationDescriptor -> KotlinDurationSerializer.descriptor

            descriptor === uuidDescriptor -> KotlinUuidSerializer.descriptor

            descriptor === instantDescriptor -> KotlinInstantSerializer.descriptor

            else -> descriptor
        }

    fun <T> apply(serializer: SerializationStrategy<T>): SerializationStrategy<T> {
        interceptedSerializer(serializer)?.let { return it as SerializationStrategy<T> }

        return serializer
    }

    fun <T> apply(deserializer: DeserializationStrategy<T>): DeserializationStrategy<T> {
        interceptedSerializer(deserializer)?.let { return it as DeserializationStrategy<T> }

        return deserializer
    }
}

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