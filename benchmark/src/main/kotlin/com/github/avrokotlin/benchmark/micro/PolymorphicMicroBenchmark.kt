package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.encodeToSink
import com.github.avrokotlin.avro4k.schema
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import org.apache.avro.Schema
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Isolates the polymorphic resolve cost: `PolymorphicResolver.getFullNamesAndAliasesToSerialName`
 * allocates a [Pair] and does a cache lookup per polymorphic value, on top of the extra
 * encoder/decoder layer (`PolymorphicDirectEncoder` wrapping an `AvroValueDirectEncoder`).
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
    lateinit var shapesSchema: Schema
    lateinit var shapesData: ByteArray

    lateinit var circles: CircleList
    lateinit var circlesSchema: Schema
    lateinit var circlesData: ByteArray

    /** Allocated once so that the write methods only measure the encoding, not the sink construction. */
    lateinit var sink: Sink

    @Setup
    fun setup() {
        shapesSchema = Avro.schema<ShapeList>()
        circlesSchema = Avro.schema<CircleList>()
        shapes = shapeList(shapeCount)
        circles = circleList(shapeCount)
        shapesData = Avro.encodeToByteArray(shapesSchema, shapes)
        circlesData = Avro.encodeToByteArray(circlesSchema, circles)
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    @Benchmark
    fun readPolymorphic(): ShapeList = Avro.decodeFromByteArray<ShapeList>(shapesSchema, shapesData)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writePolymorphic() {
        Avro.encodeToSink(shapesSchema, shapes, sink)
        sink.flush()
    }

    @Benchmark
    fun readConcrete(): CircleList = Avro.decodeFromByteArray<CircleList>(circlesSchema, circlesData)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeConcrete() {
        Avro.encodeToSink(circlesSchema, circles, sink)
        sink.flush()
    }
}
