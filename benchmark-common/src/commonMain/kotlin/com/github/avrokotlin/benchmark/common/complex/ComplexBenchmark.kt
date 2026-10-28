package com.github.avrokotlin.benchmark.common.complex

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.benchmark.common.internal.Clients
import com.github.avrokotlin.benchmark.common.internal.ClientsGenerator
import com.github.avrokotlin.benchmark.common.internal.RandomData
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class ComplexBenchmark {
    /** Number of [com.github.avrokotlin.benchmark.common.internal.Client] records in the encoded payload. */
    @Param("1", "15")
    var clientCount: Int = 15

    lateinit var value: Clients
    lateinit var data: ByteArray
    val schema: AvroSchema = Avro.schema<Clients>()

    @Setup
    fun setup() {
        value = ClientsGenerator.generate(clientCount, RandomData())
        data = Avro.encodeToByteArray(schema, value)
    }

    @Benchmark
    fun read(): Clients = Avro.decodeFromByteArray<Clients>(schema, data)

    @Benchmark
    fun write(): ByteArray = Avro.encodeToByteArray(schema, value)
}
