package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema
import java.util.concurrent.TimeUnit

/**
 * Isolates schema inference (`Avro.schema`) from the cache lookup that normally hides it.
 *
 * The schema cache is per-[Avro]-instance and weakly keyed by descriptor, so "cold" cannot be
 * measured on the shared [Avro.Default] instance: the second invocation would already be a cache
 * hit. The honest way out is a **fresh [Avro] instance per invocation**, which necessarily also
 * pays for constructing that instance - hence [newAvroInstance], which measures *only* the
 * construction and nothing else. `coldInference - newAvroInstance` is the inference cost; read
 * those two together and never quote [coldInference] on its own.
 *
 * The descriptor is the depth-8 nested record, so inference has 8 record types to visit.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class SchemaInferenceMicroBenchmark {
    lateinit var descriptor: SerialDescriptor

    @Setup
    fun setup() {
        descriptor = Nested8.serializer().descriptor
        // Warm the shared instance's cache so that `warmCacheHit` only ever measures the lookup.
        Avro.schema(descriptor)
    }

    /** A cache hit on the shared instance: a weak-keyed map lookup and nothing else. */
    @Benchmark
    fun warmCacheHit(): Schema = Avro.schema(descriptor)

    /** A cold cache: full inference through `ValueVisitor`, plus the instance construction. */
    @Benchmark
    fun coldInference(): Schema = Avro { }.schema(descriptor)

    /** The control for [coldInference]: the instance construction on its own. */
    @Benchmark
    fun newAvroInstance(): Avro = Avro { }
}
