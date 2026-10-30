package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.benchmark.internal.CoreSchema
import com.github.avrokotlin.benchmark.internal.coreSchema
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.serialization.KSerializer
import java.util.concurrent.TimeUnit

/**
 * Isolates the per-structure cost of records: the encoder/decoder object that
 * `AbstractAvroBinaryDecoder.beginStructure` (and its encoder counterpart) allocates for every
 * structure, and the `RecordResolver.resolveFields` lookup that runs once per record *instance*.
 *
 * Each level of the payload carries exactly one field, so the encoded payload is ~1 byte whatever
 * the depth and the curve over `depth` is the per-record overhead, isolated from the data.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class NestedRecordMicroBenchmark {
    @Param("1", "3", "8")
    @JvmField
    final var depth: Int = 1

    /**
     * The serializer is resolved once in [setup] and stored erased, so that the measured methods
     * have a single call site whatever the depth. JMH forks per parameter combination, so the call
     * site stays monomorphic within a fork.
     */
    lateinit var serializer: KSerializer<Any>
    lateinit var schema: CoreSchema
    lateinit var value: Any
    lateinit var data: ByteArray


    @Suppress("UNCHECKED_CAST")
    @Setup
    fun setup() {
        val (serializer, value) = when (depth) {
            1 -> Nested1.serializer() to NestedRecords.depth1
            3 -> Nested3.serializer() to NestedRecords.depth3
            8 -> Nested8.serializer() to NestedRecords.depth8
            else -> throw IllegalArgumentException("Unsupported depth: $depth")
        }
        this.serializer = serializer as KSerializer<Any>
        this.value = value
        schema = Avro.coreSchema(this.serializer.descriptor)
        data = Avro.encodeWith(schema, this.serializer, this.value)
    }

    @Benchmark
    fun read(): Any = Avro.decodeFromByteArray(schema, serializer, data)

    @Benchmark
    fun write(): ByteArray = Avro.encodeToByteArray(schema, serializer, value)
}
