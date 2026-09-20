package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
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
 * Isolates `ReorderingCompositeEncoder`, which buffers every field as a capturing lambda plus a
 * `BufferedCall` holder in an array, and replays them in writer order on `endStructure`.
 *
 * It is only instantiated when `RecordResolver.computeEncodingWorkflow` classifies the descriptor
 * against the writer schema as `EncodingWorkflow.NonContiguous`, i.e. when the descriptor's
 * element order is not the writer schema's field order. The three parameter values walk that
 * classification on the exact same 8-field record:
 * - `matching`: the inferred schema, so `EncodingWorkflow.ExactMatch` - the control.
 * - `swapped`: only the last two fields are swapped - the *minimal* mismatch.
 * - `reversed`: every field moves - the maximal mismatch.
 *
 * `swapped` and `reversed` are expected to cost the same, because the encoder buffers *all* the
 * fields as soon as it is used at all; that cliff, and its distance from `matching`, is the whole
 * point of this benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class FieldOrderMicroBenchmark {
    @Param("matching", "swapped", "reversed")
    @JvmField
    final var fieldOrder: String = "matching"

    lateinit var value: FieldOrderRecord

    /** The writer schema, whose field order may differ from the descriptor's element order. */
    lateinit var writerSchema: Schema
    lateinit var data: ByteArray

    /** Allocated once so that [write] only measures the encoding, not the sink construction. */
    lateinit var sink: Sink

    @Setup
    fun setup() {
        val inferred = Avro.schema<FieldOrderRecord>()
        val lastIndex = inferred.fields.size - 1
        writerSchema = when (fieldOrder) {
            "matching" -> inferred
            "swapped" -> inferred.withFieldOrder((0 until lastIndex - 1) + lastIndex + (lastIndex - 1))
            "reversed" -> inferred.withFieldOrder(inferred.fields.indices.reversed())
            else -> throw IllegalArgumentException("Unsupported field order: $fieldOrder")
        }
        value = fieldOrderRecord()
        data = Avro.encodeToByteArray(writerSchema, FieldOrderRecord.serializer(), value)
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    @Benchmark
    fun read(): FieldOrderRecord = Avro.decodeFromByteArray(writerSchema, FieldOrderRecord.serializer(), data)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun write() {
        Avro.encodeToSink(writerSchema, FieldOrderRecord.serializer(), value, sink)
        sink.flush()
    }
}

/**
 * Rebuilds [this] record schema with its fields in the given positional order.
 *
 * The public avro4k API still speaks `org.apache.avro.Schema`, so building the mismatching writer
 * schema through the Apache API is the supported way to get one. The [Schema.Field] instances have
 * to be recreated: a field belongs to exactly one record schema and cannot be shared.
 */
private fun Schema.withFieldOrder(newOrder: Iterable<Int>): Schema =
    Schema.createRecord(
        name,
        doc,
        namespace,
        isError,
        newOrder.map { fields[it] }.map { Schema.Field(it.name(), it.schema(), it.doc(), it.defaultVal()) }
    )
