package com.github.avrokotlin.benchmark

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.RandomUtils
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.apache.avro.Schema
import java.util.concurrent.TimeUnit

class PrimitiveStringAvro4kBenchmark : AbstractPrimitiveAvro4kBenchmark<String> (
    Schema.create(Schema.Type.STRING),
    String.serializer(),
) {
    override fun getRandomItem(rand: RandomUtils) =
        rand.randomAlphanumeric(rand.nextInt(1000, 10000))
}

class PrimitiveIntAvro4kBenchmark : AbstractPrimitiveAvro4kBenchmark<Int> (
    Schema.create(Schema.Type.INT),
    Int.serializer(),
    size = 100_000,
) {
    override fun getRandomItem(rand: RandomUtils) =
        rand.nextInt()
}

class PrimitiveLongAvro4kBenchmark : AbstractPrimitiveAvro4kBenchmark<Long> (
    Schema.create(Schema.Type.LONG),
    Long.serializer(),
    size = 100_000,
) {
    override fun getRandomItem(rand: RandomUtils) =
        rand.nextLong()
}

@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
abstract class AbstractPrimitiveAvro4kBenchmark<T>(
    val schema: Schema,
    val serializer: KSerializer<T>,
    val size: Int = 1_000,
) {
    private lateinit var values: List<T>
    private lateinit var bytes: List<ByteArray>

    @Setup
    fun initTestData() {
        val rand = RandomUtils()
        values = buildList {
            repeat(size) {
                add(getRandomItem(rand))
            }
        }
        bytes = values.map {
            Avro.encodeToByteArray(schema, serializer, it)
        }
    }

    abstract fun getRandomItem(rand: RandomUtils): T

    @Benchmark
    fun read() {
        bytes.forEach {
            Avro.decodeFromByteArray(schema,serializer, it)
        }
    }

    @Benchmark
    fun write() {
        values.forEach {
            Avro.encodeToByteArray(schema, serializer, it)
        }
    }
}