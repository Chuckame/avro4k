package com.github.avrokotlin.avro4k

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToByteArray
import org.apache.avro.SchemaNormalization
import java.time.Instant

internal class AvroSingleObjectTest : StringSpec({
    val orderEvent =
        OrderEvent(
            OrderId("123"),
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
            42.0
        )
    val schema = Avro.apacheSchema(OrderEvent.serializer())
    val schemas = mapOf(SchemaNormalization.parsingFingerprint64(schema) to schema.toAvro4k())
    val avroSingleObject = AvroSingleObject(schemas::get)

    "support writing avro single object" {
        // write with avro4k
        val bytes = avroSingleObject.encodeToByteArray(orderEvent)

        // check
        bytes[0] shouldBe 0xC3.toByte()
        bytes[1] shouldBe 1
        bytes.sliceArray(2..9) shouldBe SchemaNormalization.parsingFingerprint("CRC-64-AVRO", schema)
        bytes.sliceArray(10 until bytes.size) shouldBe Avro.encodeToByteArray(orderEvent)
    }

    "support reading avro single object" {
        // write with apache avro lib
        val bytes =
            byteArrayOf(0xC3.toByte(), 1) +
                SchemaNormalization.parsingFingerprint("CRC-64-AVRO", schema) +
                Avro.encodeToByteArray(orderEvent)

        val decoded = avroSingleObject.decodeFromByteArray<OrderEvent>(bytes)

        decoded shouldBe orderEvent
    }

    "writes the same CRC-64-AVRO fingerprint bytes as Apache's SchemaNormalization" {
        // A record with doc, aliases, nested named types, unions and collections (all stripped or kept as the Parsing
        // Canonical Form says), an enum root and a primitive root
        val wideValue =
            Wide(
                kind = Kind.A,
                otherKind = null,
                nullable = "x",
                nested = listOf(mapOf("k" to 1)),
                fixed = byteArrayOf(1, 2, 3, 4),
                inner = Inner(1.0, Inner(2.0, null)),
                withDefault = 3
            )
        val cases =
            listOf(
                Triple(Avro.apacheSchema(Wide.serializer()), Wide.serializer(), wideValue),
                Triple(Avro.apacheSchema(Kind.serializer()), Kind.serializer(), Kind.B),
                Triple(Avro.apacheSchema(String.serializer()), String.serializer(), "text")
            )
        for ((apacheSchema, serializer, value) in cases) {
            withClue(apacheSchema) {
                @Suppress("UNCHECKED_CAST")
                val bytes = avroSingleObject.encodeWith(apacheSchema, serializer as KSerializer<Any>, value)
                bytes.sliceArray(2..9) shouldBe SchemaNormalization.parsingFingerprint("CRC-64-AVRO", apacheSchema)
            }
        }
    }
}) {
    @Serializable
    private data class OrderEvent(
        val orderId: OrderId,
        @Contextual val date: Instant,
        val amount: Double,
    )

    @Serializable
    @JvmInline
    private value class OrderId(val value: String)

    @Serializable
    @SerialName("fingerprint.Kind")
    private enum class Kind { A, B }

    @Serializable
    @SerialName("fingerprint.Wide")
    @AvroDoc("dropped by the canonical form")
    @AvroAlias("fingerprint.OldWide")
    private data class Wide(
        val kind: Kind,
        val otherKind: Kind?,
        val nullable: String?,
        val nested: List<Map<String, Int>>,
        @AvroFixed(4) val fixed: ByteArray,
        val inner: Inner,
        @AvroDefault("7") val withDefault: Long,
    )

    @Serializable
    @SerialName("other.Inner")
    private data class Inner(val value: Double, val next: Inner?)
}