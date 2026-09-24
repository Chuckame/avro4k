package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroProp
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.CoreSchema
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.internal.apacheToAvro4k
import com.github.avrokotlin.avro4k.internal.avro4kToApache
import com.github.avrokotlin.avro4k.internal.isValidDefaultFor
import com.github.avrokotlin.avro4k.internal.resolveDefaultBranch
import com.github.avrokotlin.avro4k.internal.toFieldDefault
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import com.github.avrokotlin.avro4k.toCoreSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.apache.avro.LogicalType
import org.apache.avro.Schema
import java.math.BigDecimal

/**
 * The schema generation builds [AvroSchema]s since M3-09, and `Avro.schema()` returns their Apache form. The generated
 * schemas themselves are pinned by the `schema/` suite; these tests pin what the switch had to preserve or add.
 */
internal class SchemaGenerationTest : StringSpec({
    "both forms of a generated schema come from one cache entry, and are paired node by node" {
        val avro = Avro {}
        val descriptor = WithEnums.serializer().descriptor
        val apache = avro.apacheSchema(descriptor)
        val avro4k = avro.avroSchema(descriptor)

        avro.avroSchema(descriptor) shouldBeSameInstanceAs avro4k
        apacheToAvro4k(apache) shouldBeSameInstanceAs avro4k
        avro4kToApache(avro4k) shouldBeSameInstanceAs apache
        val enumField = (avro4k as AvroSchema.RecordSchema).fields[0].schema
        avro4kToApache(enumField) shouldBeSameInstanceAs apache.fields[0].schema()
    }

    "a named type used twice is a single instance" {
        val schema = Avro.avroSchema(WithEnums.serializer().descriptor) as AvroSchema.RecordSchema
        schema.fields[1].schema shouldBeSameInstanceAs schema.fields[0].schema
        (schema.fields[2].schema as AvroSchema.UnionSchema).types[1] shouldBeSameInstanceAs schema.fields[0].schema
        // A fixed is created anew by its serializer's getSchema() at each use: the one of `same.B` is the one of `same.A`
        val fixedOfA = (schema.fields[3].schema as AvroSchema.RecordSchema).fields.single().schema
        val fixedOfB = (schema.fields[4].schema as AvroSchema.RecordSchema).fields.single().schema
        fixedOfA.fullName shouldBe "same.hash"
        fixedOfB shouldBeSameInstanceAs fixedOfA
    }

    "two different named types with the same full name are rejected, as json cannot tell them apart" {
        shouldThrow<SerializationException> { Avro.apacheSchema<ConflictingFixed>() }
            .message shouldContain "Two different named schemas have the same full name"
    }

    "the Apache form keeps the logical types that Apache does not know, as the former generation set them" {
        Avro.apacheSchema<Char>().logicalType.name shouldBe CHAR_LOGICAL_TYPE_NAME
        Avro.apacheSchema<Char?>().types[1].logicalType.name shouldBe CHAR_LOGICAL_TYPE_NAME
        Avro.apacheSchema(CustomLogicalTypeSerializer).logicalType.name shouldBe "my-logical-type"
        // Known ones are Apache's own classes, as parsed
        Avro.apacheSchema<WithDecimal>().getField("value").schema().logicalType.javaClass.simpleName shouldBe "Decimal"
    }

    "names and props are validated like Apache's schema builders did" {
        shouldThrow<SerializationException> { Avro.apacheSchema<InvalidRecordName>() }.message shouldBe "Illegal character in: invalid-name"
        shouldThrow<SerializationException> { Avro.apacheSchema<InvalidFieldName>() }.message shouldBe "Illegal initial character: 1field"
        shouldThrow<SerializationException> { Avro.apacheSchema<ReservedFieldProp>() }.message shouldBe "Can't set reserved property: order"
        shouldThrow<SerializationException> { Avro.apacheSchema<OverwrittenProp>() }.message shouldBe "Can't overwrite property: key"
        Avro.apacheSchema<SameProp>().getProp("key") shouldBe "value"
    }

    "a recursive record still being built is named in an error message, as it cannot be rendered yet" {
        shouldThrow<SerializationException> { Avro.apacheSchema<RecursiveMapKey>() }
            .message shouldContain "Actual generated map key schema: ${RecursiveMapKey.serializer().descriptor.serialName}"
    }

    "the default value rules agree on both schema models" {
        val defaults = listOf("null", "\"a\"", "\"é\"", "\"€\"", "1", "1.5", "true", "[]", "[1]", "{}", "{\"a\":1}", "\"B\"")
            .map { Json.parseToJsonElement(it) }
        val avro4k = Avro.avroSchema(DefaultRules.serializer().descriptor) as AvroSchema.RecordSchema
        val apache = Avro.apacheSchema<DefaultRules>()
        avro4k.fields.forEachIndexed { index, field ->
            val apacheFieldSchema = apache.fields[index].schema()
            defaults.forEach { default ->
                withClue("${field.name} with $default") {
                    default.isValidDefaultFor(field.schema) shouldBe default.isValidDefaultFor(apacheFieldSchema)
                    default.isValidDefaultFor(field.schema, allowAnyCodePoint = true) shouldBe
                        default.isValidDefaultFor(apacheFieldSchema, allowAnyCodePoint = true)
                    if (default.isValidDefaultFor(apacheFieldSchema)) {
                        default.resolveDefaultBranch(field.schema).type.name shouldBe default.resolveDefaultBranch(apacheFieldSchema).type.name
                    }
                }
            }
            listOf("null", "a", "5", "1.0").forEach { annotation ->
                withClue("${field.name} with @AvroDefault($annotation)") {
                    runCatching { AvroDefault(annotation).toFieldDefault(field.schema) }.toString() shouldBe
                        runCatching { AvroDefault(annotation).toFieldDefault(apacheFieldSchema) }.toString()
                }
            }
        }
    }
}) {
    enum class Suit { HEARTS, SPADES }

    @Serializable
    private data class WithEnums(
        val first: Suit,
        val second: Suit,
        val nullable: Suit?,
        val a: SameFixedA,
        val b: SameFixedB,
    )

    @Serializable
    @SerialName("same.A")
    private data class SameFixedA(
        @AvroFixed(4) val hash: ByteArray,
    )

    @Serializable
    @SerialName("same.B")
    private data class SameFixedB(
        @AvroFixed(4) val hash: ByteArray,
    )

    @Serializable
    @SerialName("Conflicting")
    private data class ConflictingFixed(
        val a: FixedHolderA,
        val b: FixedHolderB,
    )

    @Serializable
    @SerialName("ns.A")
    private data class FixedHolderA(
        @AvroFixed(4) val hash: ByteArray,
    )

    @Serializable
    @SerialName("ns.B")
    private data class FixedHolderB(
        @AvroFixed(8) val hash: ByteArray,
    )

    @Serializable
    private data class WithDecimal(
        @AvroDecimal(scale = 2, precision = 8) @Contextual val value: BigDecimal,
    )

    @Serializable
    @SerialName("invalid-name")
    private data class InvalidRecordName(val a: Int)

    @Serializable
    private data class InvalidFieldName(
        @SerialName("1field") val a: Int,
    )

    @Serializable
    private data class ReservedFieldProp(
        @AvroProp("order", "descending") val a: Int,
    )

    @Serializable
    @AvroProp("key", "value")
    @AvroProp("key", "other")
    private data class OverwrittenProp(val a: Int)

    @Serializable
    @AvroProp("key", "value")
    @AvroProp("key", "value")
    private data class SameProp(val a: Int)

    @Serializable
    private data class RecursiveMapKey(val map: Map<RecursiveMapKey, Int>)

    @Serializable
    private data class DefaultRules(
        val string: String?,
        val int: Int,
        val char: Char?,
        val bytes: ByteArray,
        @AvroFixed(2) val fixed: ByteArray?,
        val list: List<Int>,
        val map: Map<String, Long>,
        val suit: Suit,
        val record: Inner?,
        val floating: Double,
        val boolean: Boolean,
    )

    @Serializable
    private data class Inner(
        val a: Int,
        @AvroDefault("x") val b: String,
    )

    private object CustomLogicalTypeSerializer : AvroSerializer<String>("CustomLogicalType") {
        override fun getSchema(context: SchemaSupplierContext): CoreSchema =
            Schema.create(Schema.Type.STRING).also { LogicalType("my-logical-type").addToSchema(it) }.toCoreSchema()

        override fun serializeAvro(
            encoder: AvroEncoder,
            value: String,
        ) = encoder.encodeString(value)

        override fun deserializeAvro(decoder: AvroDecoder): String = decoder.decodeString()
    }
}

private fun JsonElement.resolveDefaultBranch(schema: AvroSchema) = schema.resolveDefaultBranch(this)

private fun JsonElement.resolveDefaultBranch(schema: Schema) = schema.resolveDefaultBranch(this)