package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroDecimalLogicalType
import com.github.avrokotlin.avro4k.ScalePrecision
import com.github.avrokotlin.avro4k.decoder.ExtendedDecoder
import com.github.avrokotlin.avro4k.encoder.ExtendedEncoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import org.apache.avro.Conversions
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.apache.avro.generic.GenericFixed
import org.apache.avro.util.Utf8
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = BigDecimal::class)
class BigDecimalSerializer : AvroSerializer<BigDecimal>() {

   private val defaultScalePrecision = ScalePrecision()
   private val defaultLogicalDecimal = AvroDecimalLogicalType()

   @OptIn(InternalSerializationApi::class)
   override val descriptor = buildSerialDescriptor(BigDecimal::class.qualifiedName!!, StructureKind.OBJECT) {
      annotations = listOf(
         defaultScalePrecision,
         defaultLogicalDecimal,
      )
   }

   override fun encodeAvroValue(schema: Schema, encoder: ExtendedEncoder, obj: BigDecimal) {

      // we support encoding big decimals in three ways - fixed, bytes or as a String, depending on the schema passed in
      // the scale and precision should come from the schema and the rounding mode from the implicit

      val converter = Conversions.DecimalConversion()
      val rm = RoundingMode.UNNECESSARY

      return when (schema.type) {
         Schema.Type.STRING -> encoder.encodeString(obj.toString())
         Schema.Type.BYTES -> {
            when (val logical = schema.logicalType) {
               is LogicalTypes.Decimal -> encoder.encodeByteArray(converter.toBytes(obj.setScale(logical.scale, rm),
                  schema,
                  logical))
               else -> throw SerializationException("Cannot encode BigDecimal to FIXED for logical type $logical")
            }
         }
         Schema.Type.FIXED -> {
            when (val logical = schema.logicalType) {
               is LogicalTypes.Decimal -> encoder.encodeFixed(converter.toFixed(obj.setScale(logical.scale, rm),
                  schema,
                  logical))
               else -> throw SerializationException("Cannot encode BigDecimal to FIXED for logical type $logical")
            }

         }
         else -> throw SerializationException("Cannot encode BigDecimal as ${schema.type}")
      }
   }

   override fun decodeAvroValue(schema: Schema, decoder: ExtendedDecoder): BigDecimal {

      fun logical() = when (val l = schema.logicalType) {
         is LogicalTypes.Decimal -> l
         else -> throw SerializationException("Cannot decode to BigDecimal when field schema [$schema] does not define Decimal logical type [$l]")
      }

      return when (val v = decoder.decodeAny()) {
         is Utf8 -> BigDecimal(decoder.decodeString())
         is ByteArray -> Conversions.DecimalConversion().fromBytes(ByteBuffer.wrap(v), schema, logical())
         is ByteBuffer -> Conversions.DecimalConversion().fromBytes(v, schema, logical())
         is GenericFixed -> Conversions.DecimalConversion().fromFixed(v, schema, logical())
         else -> throw SerializationException("Unsupported BigDecimal type [$v]")
      }
   }
}