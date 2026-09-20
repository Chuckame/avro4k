package com.github.avrokotlin.benchmark.lists

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.benchmark.internal.ListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.WorkloadEquivalence
import kotlinx.benchmark.*
import java.util.concurrent.TimeUnit


@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
internal abstract class SerializationListsBenchmark {
    /**
     * Number of [com.github.avrokotlin.benchmark.internal.StatsEntry] per wrapper, each entry holding
     * [VALUES_PER_ENTRY] longs. The outer list is kept at [WRAPPER_COUNT] on purpose: this workload is
     * already the slowest one, so the size scaling is driven by this single dimension.
     * Kept as a `var` with a default value so that the field is also usable outside of the JMH harness.
     */
    @Param("100", "10000")
    @JvmField
    final var entryCount: Int = 10000

    lateinit var lists: ListWrapperDatasClass
    val schema = Avro.schema<ListWrapperDatasClass>()

    @Setup
    fun initTestData() {
        lists = ListWrapperDatasClass.create(WRAPPER_COUNT, entryCount, VALUES_PER_ENTRY)
        // Before anything else: prove the three libraries still describe this workload the same way
        // and put the same bytes on the wire. Runs for every benchmark class, once per trial, and
        // throws rather than letting a broken comparison disappear from the report.
        WorkloadEquivalence.verifyLists(lists)
        setup()
        prepareBinaryData()
    }

    /** Runs after the data exists, so a library can convert it to its own model here. */
    abstract fun setup()

    abstract fun prepareBinaryData()

    private companion object {
        const val WRAPPER_COUNT = 1
        const val VALUES_PER_ENTRY = 100
    }
}
