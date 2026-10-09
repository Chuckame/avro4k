package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.CoreSchema
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

class CustomAvroSerializerTest : StringSpec({
    "Non Avro encoder or decoder should fail" {
        class CustomSerializer : BasicSerializer()
        shouldThrow<UnsupportedOperationException> {
            CustomSerializer().serialize(CustomEncoder(), object {})
        }
        shouldThrow<UnsupportedOperationException> {
            CustomSerializer().deserialize(CustomDecoder())
        }
    }
    "Non Avro encoder or decoder should not fail when generic methods are implemented" {
        var serializeGenericCalled = false
        val expectedDeserializedValue = object {}

        class CustomSerializer : BasicSerializer() {
            override fun deserializeGeneric(decoder: Decoder): Any {
                return expectedDeserializedValue
            }

            override fun serializeGeneric(encoder: Encoder, value: Any) {
                serializeGenericCalled = true
            }
        }
        CustomSerializer().serialize(CustomEncoder(), object {})
        serializeGenericCalled shouldBe true

        CustomSerializer().deserialize(CustomDecoder()) shouldBe expectedDeserializedValue
    }
    "Should not fail when using Avro encoder or decoder" {
        var serializeAvroCalled = false
        val expectedDeserializedValue = object {}

        class CustomSerializer : BasicSerializer() {
            override fun serializeAvro(encoder: AvroEncoder, value: Any) {
                value shouldBe expectedDeserializedValue
                serializeAvroCalled = true
            }

            override fun deserializeAvro(decoder: AvroDecoder): Any {
                return expectedDeserializedValue
            }
        }
        CustomSerializer().serialize(mockk<AvroEncoder>(), expectedDeserializedValue)
        serializeAvroCalled shouldBe true

        CustomSerializer().deserialize(mockk<AvroDecoder>()) shouldBe expectedDeserializedValue
    }
    "The Avro encoder and decoder give the Avro instance they run with, nested and for a missing field's default" {
        val avro = Avro {}
        val writerSchema = avro.schema(AvroInstanceWriter.serializer().descriptor)
        AvroInstanceRecordingSerializer.seen.clear()

        val bytes = avro.encodeToByteArray(writerSchema, AvroInstanceWriter.serializer(), AvroInstanceWriter("a", listOf("b"), mapOf("k" to "c")))
        AvroInstanceRecordingSerializer.seen shouldHaveSize 3

        avro.decodeFromByteArray(writerSchema, AvroInstanceReader.serializer(), bytes) shouldBe
            AvroInstanceReader("a", listOf("b"), mapOf("k" to "c"), "default")
        // the three fields written, then the field missing from the writer schema, read from its default
        AvroInstanceRecordingSerializer.seen shouldHaveSize 7
        AvroInstanceRecordingSerializer.seen.forEach { it shouldBeSameInstanceAs avro }
    }
    "Supports null to true should make the descriptor nullable" {
        class CustomSerializer : BasicSerializer() {
            override val supportsNull: Boolean
                get() = true
        }
        CustomSerializer().descriptor.isNullable shouldBe true
    }
    "descriptor should not be nullable by default" {
        class CustomSerializer : BasicSerializer()
        CustomSerializer().descriptor.isNullable shouldBe false
    }
})

/** Records the [Avro] instance of every encoder and decoder it is called with. */
private object AvroInstanceRecordingSerializer : AvroSerializer<String>("AvroInstanceRecording") {
    val seen = mutableListOf<Avro>()

    override fun getSchema(context: SchemaSupplierContext): CoreSchema = AvroSchema.StringSchema()

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: String,
    ) {
        seen += encoder.avro
        encoder.encodeString(value)
    }

    override fun deserializeAvro(decoder: AvroDecoder): String {
        seen += decoder.avro
        return decoder.decodeString()
    }
}

@Serializable
@SerialName("AvroInstance")
private data class AvroInstanceWriter(
    @Serializable(with = AvroInstanceRecordingSerializer::class) val direct: String,
    val inList: List<
        @Serializable(with = AvroInstanceRecordingSerializer::class)
        String
    >,
    val inMap: Map<
        String,
        @Serializable(with = AvroInstanceRecordingSerializer::class)
        String
    >,
)

@Serializable
@SerialName("AvroInstance")
private data class AvroInstanceReader(
    @Serializable(with = AvroInstanceRecordingSerializer::class) val direct: String,
    val inList: List<
        @Serializable(with = AvroInstanceRecordingSerializer::class)
        String
    >,
    val inMap: Map<
        String,
        @Serializable(with = AvroInstanceRecordingSerializer::class)
        String
    >,
    @AvroDefault("default") @Serializable(with = AvroInstanceRecordingSerializer::class) val missing: String,
)

private abstract class BasicSerializer : AvroSerializer<Any>("basic") {
    override fun serializeAvro(encoder: AvroEncoder, value: Any) {
        TODO("Not yet implemented")
    }

    override fun deserializeAvro(decoder: AvroDecoder): Any {
        TODO("Not yet implemented")
    }

    override fun getSchema(context: SchemaSupplierContext): CoreSchema {
        TODO("Not yet implemented")
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class CustomEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()
}

@OptIn(ExperimentalSerializationApi::class)
private class CustomDecoder : AbstractDecoder() {
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }
}