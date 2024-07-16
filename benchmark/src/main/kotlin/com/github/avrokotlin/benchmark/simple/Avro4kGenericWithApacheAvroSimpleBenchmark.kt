package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import kotlinx.benchmark.Benchmark
import org.apache.avro.Conversions
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.Encoder
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayInputStream
import java.io.OutputStream

internal class Avro4kGenericWithApacheAvroSimpleBenchmark : SerializationSimpleBenchmark() {
    lateinit var writer: DatumWriter<GenericRecord>
    lateinit var encoder: Encoder
    lateinit var reader: DatumReader<GenericRecord>

    lateinit var data: ByteArray
    var writeMode = false

    override fun setup() {
        GenericData.get().addLogicalTypeConversion(Conversions.DecimalConversion())
//        GenericData.get().addLogicalTypeConversion(TimeConversions.DateConversion())
//        GenericData.get().addLogicalTypeConversion(TimeConversions.TimestampMillisConversion())

        writer = GenericData.get().createDatumWriter(schema) as DatumWriter<GenericRecord>
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)

        reader = GenericData.get().createDatumReader(schema) as DatumReader<GenericRecord>
    }

    override fun prepareBinaryData() {
        data = Avro.default.encodeToByteArray(SimpleDatasClass.serializer(), clients)
    }

    @Benchmark
    fun read() {
        val decoder = DecoderFactory.get().directBinaryDecoder(ByteArrayInputStream(data), null)
        val genericData = reader.read(null, decoder)
        Avro.default.fromRecord(SimpleDatasClass.serializer(), genericData)
    }

    @Benchmark
    fun write() {
        val genericData = Avro.default.toRecord(SimpleDatasClass.serializer(), schema, clients)
        writer.write(genericData, encoder)
    }
}