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
 * Isolates the union branch resolution chain on encode, and the plain string encode/decode.
 *
 * Both records hold [STRING_STORM_FIELDS] string fields of [STRING_STORM_LENGTH] characters, and
 * every value is non-null. The only difference is nullability:
 * - [writeNonNull] writes into plain STRING fields: no union, so `AbstractAvroEncoder.encodeString`
 *   goes straight to `encodeStringUnchecked`.
 * - [writeNullable] writes into `["null","string"]` fields, so every one of the 12 values walks the
 *   `trySelectTypeNameFromUnion` chain (STRING is the first of the 9 candidates, so this measures
 *   the cheapest possible union hit - the worst case is 9 attempts).
 *
 * The delta between the two is therefore the union resolution, and [writeNonNull] alone is the
 * string encoding cost. There is no size parameter: the field count is baked into the descriptor
 * and cannot be parameterised without code generation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class StringFieldMicroBenchmark {
    lateinit var nonNull: StringStorm
    lateinit var nonNullSchema: Schema
    lateinit var nonNullData: ByteArray

    lateinit var nullable: NullableStringStorm
    lateinit var nullableSchema: Schema
    lateinit var nullableData: ByteArray

    /** Allocated once so that the write methods only measure the encoding, not the sink construction. */
    lateinit var sink: Sink

    @Setup
    fun setup() {
        nonNullSchema = Avro.schema<StringStorm>()
        nullableSchema = Avro.schema<NullableStringStorm>()
        nonNull = stringStorm()
        nullable = nullableStringStorm()
        nonNullData = Avro.encodeToByteArray(nonNullSchema, nonNull)
        nullableData = Avro.encodeToByteArray(nullableSchema, nullable)
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    @Benchmark
    fun readNonNull(): StringStorm = Avro.decodeFromByteArray<StringStorm>(nonNullSchema, nonNullData)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeNonNull() {
        Avro.encodeToSink(nonNullSchema, nonNull, sink)
        sink.flush()
    }

    @Benchmark
    fun readNullable(): NullableStringStorm = Avro.decodeFromByteArray<NullableStringStorm>(nullableSchema, nullableData)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeNullable() {
        Avro.encodeToSink(nullableSchema, nullable, sink)
        sink.flush()
    }
}
