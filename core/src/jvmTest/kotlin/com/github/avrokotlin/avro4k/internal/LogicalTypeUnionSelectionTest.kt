package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeWith
import com.github.avrokotlin.avro4k.internal.schema.hasValidLogicalType
import com.github.avrokotlin.avro4k.internal.schema.knownLogicalTypeName
import com.github.avrokotlin.avro4k.toAvro4k
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.UUID

/**
 * The encoders pick a union branch by logical type on avro4k's own schema, whose `logicalTypeName` is the raw
 * `logicalType` prop, where Apache's `getLogicalType()` is null for a logical type breaking the specification's rules.
 * The specification says to ignore such a logical type, so such a branch must not be selected, as it was not before.
 */
internal class LogicalTypeUnionSelectionTest : StringSpec({
    "a logical type is valid exactly when Apache keeps it" {
        val schemas =
            listOf(
                """{"type":"string","logicalType":"uuid"}""",
                """{"type":"fixed","name":"F16","size":16,"logicalType":"uuid"}""",
                """{"type":"fixed","name":"F8","size":8,"logicalType":"uuid"}""",
                """{"type":"bytes","logicalType":"uuid"}""",
                """{"type":"fixed","name":"D12","size":12,"logicalType":"duration"}""",
                """{"type":"fixed","name":"D8","size":8,"logicalType":"duration"}""",
                """{"type":"bytes","logicalType":"duration"}""",
                """{"type":"bytes","logicalType":"big-decimal"}""",
                """{"type":"string","logicalType":"big-decimal"}""",
                """{"type":"bytes","logicalType":"decimal","precision":4,"scale":2}""",
                """{"type":"bytes","logicalType":"decimal","precision":4}""",
                """{"type":"bytes","logicalType":"decimal"}""",
                """{"type":"bytes","logicalType":"decimal","precision":0}""",
                """{"type":"bytes","logicalType":"decimal","precision":-1}""",
                """{"type":"bytes","logicalType":"decimal","precision":4,"scale":5}""",
                """{"type":"bytes","logicalType":"decimal","precision":4,"scale":-1}""",
                """{"type":"bytes","logicalType":"decimal","precision":"4"}""",
                """{"type":"bytes","logicalType":"decimal","precision":4.0}""",
                """{"type":"bytes","logicalType":"decimal","precision":4,"scale":"2"}""",
                """{"type":"bytes","logicalType":"decimal","precision":3000000000}""",
                """{"type":"fixed","name":"X4","size":4,"logicalType":"decimal","precision":9}""",
                """{"type":"fixed","name":"X4b","size":4,"logicalType":"decimal","precision":10}""",
                """{"type":"string","logicalType":"decimal","precision":4}""",
                """{"type":"int","logicalType":"date"}""",
                """{"type":"string","logicalType":"date"}""",
                """{"type":"int","logicalType":"time-millis"}""",
                """{"type":"long","logicalType":"time-millis"}""",
                """{"type":"long","logicalType":"time-micros"}""",
                """{"type":"long","logicalType":"timestamp-millis"}""",
                """{"type":"int","logicalType":"timestamp-micros"}""",
                """{"type":"long","logicalType":"timestamp-nanos"}""",
                """{"type":"long","logicalType":"local-timestamp-millis"}""",
                """{"type":"long","logicalType":"local-timestamp-micros"}""",
                """{"type":"string","logicalType":"local-timestamp-nanos"}""",
                """{"type":"string","logicalType":"uuid","avro.java.string":"String"}""",
                """{"type":"string","logicalType":"uuid","java-class":"java.util.UUID"}""",
                """{"type":"int","logicalType":"date","java-key-class":1}""",
                """{"type":"long","logicalType":"timestamp-millis","java-element-class":"X"}"""
            )
        for (json in schemas) {
            val apache = Schema.Parser().parse(json)
            withClue(json) {
                apache.toAvro4k().hasValidLogicalType() shouldBe (LogicalTypes.fromSchemaIgnoreInvalid(apache) != null)
                // the name the serializers compare, as Apache's getLogicalType() of a parsed schema gives it
                apache.toAvro4k().knownLogicalTypeName shouldBe LogicalTypes.fromSchemaIgnoreInvalid(apache)?.name
            }
        }
    }

    "a logical type avro4k resolves by name, unknown to Apache, stays valid" {
        Schema.Parser().parse("""{"type":"int","logicalType":"char"}""").toAvro4k().hasValidLogicalType() shouldBe true
        Schema.create(Schema.Type.INT).toAvro4k().hasValidLogicalType() shouldBe true
    }

    "a logical type unknown to Apache is no known logical type, as Apache's getLogicalType() of a parsed schema" {
        val char = Schema.Parser().parse("""{"type":"int","logicalType":"char"}""")
        char.toAvro4k().knownLogicalTypeName shouldBe null
        char.logicalType shouldBe null
        Schema.Parser().parse("""{"type":"long","logicalType":"custom"}""").toAvro4k().knownLogicalTypeName shouldBe null
    }

    "a union branch whose logical type is invalid is not selected by its logical type" {
        // uuid needs a fixed of 16 bytes: the fixed(8) branch is a plain fixed, so the UUID falls back to the string branch
        val uuidUnion = Schema.Parser().parse("""[{"type":"fixed","name":"F8","size":8,"logicalType":"uuid"},"string"]""")
        val uuid = UUID.randomUUID()
        Avro.encodeWith<UUID>(uuidUnion, uuid) shouldBe apacheBytes(uuidUnion, 1, uuid.toString())

        // decimal without a precision is plain bytes: the BigDecimal falls back to the string branch
        val decimalUnion = Schema.Parser().parse("""[{"type":"bytes","logicalType":"decimal"},"string"]""")
        Avro.encodeWith<BigDecimal>(decimalUnion, BigDecimal("1.5")) shouldBe apacheBytes(decimalUnion, 1, "1.5")
    }

    "a union branch whose logical type is valid is selected by its logical type" {
        val uuidUnion = Schema.Parser().parse("""["string",{"type":"fixed","name":"F16","size":16,"logicalType":"uuid"}]""")
        val uuid = UUID(1, 2)
        val fixed = org.apache.avro.generic.GenericData.Fixed(uuidUnion.types[1], java.nio.ByteBuffer.allocate(16).putLong(1).putLong(2).array())
        Avro.encodeWith<UUID>(uuidUnion, uuid) shouldBe apacheBytes(uuidUnion, 1, fixed)
    }
})

/** The bytes Apache writes for [value] in the union branch [branch] (checked against Apache's own branch resolution). */
private fun apacheBytes(
    union: Schema,
    branch: Int,
    value: Any,
): ByteArray {
    val out = ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    encoder.writeIndex(branch)
    GenericDatumWriter<Any>(union.types[branch]).write(value, encoder)
    encoder.flush()
    return out.toByteArray()
}