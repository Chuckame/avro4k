package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.decodeFromGenericData
import com.github.avrokotlin.avro4k.encodeToGenericData
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import com.github.avrokotlin.avro4k.toApacheSchema
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.avro.Schema

/**
 * The generic data encoders and decoders give a custom serializer the [Avro] instance they run with, as the binary ones
 * do: at the top level, in a record, an array and a map, and for a missing field's default.
 */
internal class GenericDataAvroInstanceTest : StringSpec({
    "the generic data encoder and decoder give the Avro instance they run with, at the top level" {
        val avro = Avro {}
        val schema = Schema.create(Schema.Type.STRING)
        AvroInstanceRecordingSerializer.seen.clear()

        val genericValue = avro.encodeToGenericData(schema, AvroInstanceRecordingSerializer, "a")
        avro.decodeFromGenericData(schema, AvroInstanceRecordingSerializer, genericValue) shouldBe "a"

        AvroInstanceRecordingSerializer.seen shouldHaveSize 2
        AvroInstanceRecordingSerializer.seen.forEach { it shouldBeSameInstanceAs avro }
    }
    "the generic data encoder and decoder give the Avro instance they run with, nested and for a missing field's default" {
        val avro = Avro {}
        val writerSchema = avro.schema(AvroInstanceWriter.serializer().descriptor).toApacheSchema()
        AvroInstanceRecordingSerializer.seen.clear()

        val genericValue = avro.encodeToGenericData(writerSchema, AvroInstanceWriter.serializer(), AvroInstanceWriter("a", listOf("b"), mapOf("k" to "c")))
        AvroInstanceRecordingSerializer.seen shouldHaveSize 3

        avro.decodeFromGenericData(writerSchema, AvroInstanceReader.serializer(), genericValue) shouldBe
            AvroInstanceReader("a", listOf("b"), mapOf("k" to "c"), "default")
        // the three fields written, then the field missing from the writer schema, read from its default
        AvroInstanceRecordingSerializer.seen shouldHaveSize 7
        AvroInstanceRecordingSerializer.seen.forEach { it shouldBeSameInstanceAs avro }
    }
}) {
    /** Records the [Avro] instance of every encoder and decoder it is called with. */
    private object AvroInstanceRecordingSerializer : AvroSerializer<String>("AvroInstanceRecording") {
        val seen = mutableListOf<Avro>()

        override fun getSchema(context: SchemaSupplierContext): AvroSchema = AvroSchema.StringSchema()

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
}