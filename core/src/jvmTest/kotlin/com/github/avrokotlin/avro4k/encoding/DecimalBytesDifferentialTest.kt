package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeWith
import com.github.avrokotlin.avro4k.encodeWith
import com.github.avrokotlin.avro4k.serializer.BigDecimalSerializer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import org.apache.avro.AvroTypeException
import org.apache.avro.Conversions
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer

/**
 * The bytes of the `decimal` and `big-decimal` logical types, written and read by avro4k in plain `java.math`, against
 * Apache's own conversions (`Conversions.DecimalConversion`, `Conversions.BigDecimalConversion`), value by value.
 */
internal class DecimalBytesDifferentialTest : StringSpec({
    val values =
        listOf(
            "0",
            "1",
            "-1",
            "0.01",
            "-0.01",
            "123.45",
            "-123.45",
            "99999999.99",
            "-99999999.99",
            "127",
            "-128",
            "128",
            "-129",
            "1E+3",
            "12.3",
            "-0.5",
            "42"
        ).map(::BigDecimal)

    val decimalSchemas =
        listOf(
            LogicalTypes.decimal(10, 2).addToSchema(Schema.create(Schema.Type.BYTES)),
            LogicalTypes.decimal(10, 2).addToSchema(Schema.createFixed("f8", null, null, 8)),
            LogicalTypes.decimal(20, 4).addToSchema(Schema.createFixed("f16", null, null, 16)),
            LogicalTypes.decimal(38, 6).addToSchema(Schema.create(Schema.Type.BYTES))
        )

    "decimal: the same bytes as Apache's conversion, and the same values back" {
        val conversion = Conversions.DecimalConversion()
        for (schema in decimalSchemas) {
            for (value in values) {
                withClue("$value as $schema") {
                    val bytes = Avro.encodeWith(schema, BigDecimalSerializer, value)
                    val expected =
                        if (schema.type == Schema.Type.FIXED) {
                            conversion.toFixed(value, schema, schema.logicalType).bytes()
                        } else {
                            lengthPrefixed(conversion.toBytes(value, schema, schema.logicalType))
                        }
                    bytes shouldBe expected
                    val apacheValue =
                        if (schema.type == Schema.Type.FIXED) {
                            conversion.fromFixed(GenericData.Fixed(schema, expected), schema, schema.logicalType)
                        } else {
                            conversion.fromBytes(conversion.toBytes(value, schema, schema.logicalType), schema, schema.logicalType)
                        }
                    Avro.decodeWith(schema, BigDecimalSerializer, bytes) shouldBe apacheValue
                }
            }
        }
    }

    "decimal: the same failures as Apache's conversion" {
        val conversion = Conversions.DecimalConversion()
        val schema = LogicalTypes.decimal(4, 2).addToSchema(Schema.create(Schema.Type.BYTES))
        // more digits after the point than the scale (no rounding), then more digits than the precision
        for (value in listOf(BigDecimal("1.234"), BigDecimal("123.4"), BigDecimal("12345"))) {
            withClue(value) {
                val apache = shouldThrow<AvroTypeException> { conversion.toBytes(value, schema, schema.logicalType) }
                // a SerializationException, as every avro4k encoding failure, with Apache's message
                shouldThrow<SerializationException> { Avro.encodeWith(schema, BigDecimalSerializer, value) }.message shouldBe apache.message
            }
        }
    }

    "big-decimal: the same bytes as Apache's conversion, and the same values back" {
        val conversion = Conversions.BigDecimalConversion()
        val schema = LogicalTypes.bigDecimal().addToSchema(Schema.create(Schema.Type.BYTES))
        for (value in values + listOf(BigDecimal("3.14159265358979323846264338327950288"), BigDecimal("-1E-40"))) {
            withClue(value) {
                val converted = conversion.toBytes(value, schema, schema.logicalType)
                val bytes = Avro.encodeWith(schema, BigDecimalSerializer, value)
                bytes shouldBe lengthPrefixed(converted)
                Avro.decodeWith(schema, BigDecimalSerializer, bytes) shouldBe conversion.fromBytes(converted, schema, schema.logicalType)
            }
        }
    }
})

/** The Avro binary encoding of [bytes] as a `bytes` value: its length, then the bytes. */
private fun lengthPrefixed(bytes: ByteBuffer): ByteArray {
    val out = ByteArrayOutputStream()
    val encoder = EncoderFactory.get().directBinaryEncoder(out, null)
    encoder.writeBytes(bytes.duplicate())
    encoder.flush()
    return out.toByteArray()
}