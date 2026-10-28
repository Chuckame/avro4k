package com.github.avrokotlin.benchmark.common.lists

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.benchmark.common.internal.ListWrapperDatasClass
import com.github.avrokotlin.benchmark.common.internal.RandomData
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class ListsBenchmark {
    /**
     * Number of [com.github.avrokotlin.benchmark.common.internal.StatsEntry] in the one wrapper, each holding
     * [VALUES_PER_ENTRY] longs, as in `benchmark/`.
     */
    @Param("100")
    var entryCount: Int = 100

    lateinit var value: ListWrapperDatasClass
    lateinit var data: ByteArray
    val schema: AvroSchema = Avro.schema<ListWrapperDatasClass>()

    @Setup
    fun setup() {
        value = ListWrapperDatasClass.create(WRAPPER_COUNT, entryCount, VALUES_PER_ENTRY, RandomData())
        data = Avro.encodeToByteArray(schema, value)
    }

    @Benchmark
    fun read(): ListWrapperDatasClass = Avro.decodeFromByteArray<ListWrapperDatasClass>(schema, data)

    @Benchmark
    fun write(): ByteArray = Avro.encodeToByteArray(schema, value)

    private companion object {
        const val WRAPPER_COUNT = 1
        const val VALUES_PER_ENTRY = 100
    }
}
