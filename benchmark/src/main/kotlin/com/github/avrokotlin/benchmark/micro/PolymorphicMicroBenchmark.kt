package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
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
import java.util.concurrent.TimeUnit

/**
 * Isolates the polymorphic resolve cost: `PolymorphicResolver.getFullNamesAndAliasesToSerialName`
 * allocates a [Pair] and does a cache lookup per polymorphic value, on top of the extra
 * encoder/decoder layer (`PolymorphicEncoder` wrapping an `AvroValueEncoder`).
 *
 * [readConcrete]/[writeConcrete] are the monomorphic control: the same [Circle] payload in the
 * same array, but typed as the concrete class, so no union index and no polymorphic resolution.
 * Every element of the polymorphic variant is a [Circle] too, so the two payloads differ only by
 * the union index byte and the delta is the resolution itself.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class PolymorphicMicroBenchmark {
    @Param("10", "1000")
    @JvmField
    final var shapeCount: Int = 10

    lateinit var shapes: ShapeList
    lateinit var shapesSchema: CoreSchema
    lateinit var shapesData: ByteArray

    lateinit var circles: CircleList
    lateinit var circlesSchema: CoreSchema
    lateinit var circlesData: ByteArray


    @Setup
    fun setup() {
        shapesSchema = Avro.coreSchema<ShapeList>()
        circlesSchema = Avro.coreSchema<CircleList>()
        shapes = shapeList(shapeCount)
        circles = circleList(shapeCount)
        shapesData = Avro.encodeWith(shapesSchema, shapes)
        circlesData = Avro.encodeWith(circlesSchema, circles)
    }

    @Benchmark
    fun readPolymorphic(): ShapeList = Avro.decodeFromByteArray<ShapeList>(shapesSchema, shapesData)

    @Benchmark
    fun writePolymorphic(): ByteArray = Avro.encodeToByteArray(shapesSchema, shapes)

    @Benchmark
    fun readConcrete(): CircleList = Avro.decodeFromByteArray<CircleList>(circlesSchema, circlesData)

    @Benchmark
    fun writeConcrete(): ByteArray = Avro.encodeToByteArray(circlesSchema, circles)
}
