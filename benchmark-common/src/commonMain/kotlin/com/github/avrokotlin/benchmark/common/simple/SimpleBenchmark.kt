package com.github.avrokotlin.benchmark.common.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.benchmark.common.internal.RandomData
import com.github.avrokotlin.benchmark.common.internal.SimpleDatasClass
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class SimpleBenchmark {
    /** Number of [com.github.avrokotlin.benchmark.common.internal.SimpleDataClass] records in the encoded payload. */
    @Param("1", "25")
    var recordCount: Int = 25

    lateinit var value: SimpleDatasClass
    lateinit var data: ByteArray
    val schema: AvroSchema = Avro.schema<SimpleDatasClass>()

    @Setup
    fun setup() {
        value = SimpleDatasClass.create(recordCount, RandomData())
        data = Avro.encodeToByteArray(schema, value)
    }

    @Benchmark
    fun read(): SimpleDatasClass = Avro.decodeFromByteArray<SimpleDatasClass>(schema, data)

    @Benchmark
    fun write(): ByteArray = Avro.encodeToByteArray(schema, value)
}
