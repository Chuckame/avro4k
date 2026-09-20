package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.benchmark.internal.RandomUtils
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import com.github.avrokotlin.benchmark.internal.WorkloadEquivalence
import kotlinx.benchmark.*
import java.util.concurrent.TimeUnit


@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
internal abstract class SerializationSimpleBenchmark {
    /**
     * Number of [com.github.avrokotlin.benchmark.internal.SimpleDataClass] records contained in the encoded payload.
     * Kept as a `var` with a default value so that the field is also usable outside of the JMH harness.
     */
    @Param("1", "25", "500")
    @JvmField
    final var recordCount: Int = 25

    lateinit var clients: SimpleDatasClass
    val schema = Avro.schema<SimpleDatasClass>()

    @Setup
    fun initTestData() {
        clients = SimpleDatasClass.create(recordCount, RandomUtils())
        // Before anything else: prove the three libraries still describe this workload the same way
        // and put the same bytes on the wire. Runs for every benchmark class, once per trial, and
        // throws rather than letting a broken comparison disappear from the report.
        WorkloadEquivalence.verifySimple(clients)
        setup()
        prepareBinaryData()
    }

    /** Runs after the data exists, so a library can convert it to its own model here. */
    abstract fun setup()

    abstract fun prepareBinaryData()
}
