package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.encodeToSink
import com.github.avrokotlin.avro4k.fromJsonString
import com.github.avrokotlin.avro4k.toAvro4k
import com.github.avrokotlin.benchmark.internal.CoreSchema
import com.github.avrokotlin.benchmark.internal.apacheSchema
import com.github.avrokotlin.benchmark.internal.encodeWith
import com.github.avrokotlin.benchmark.internal.toCoreSchema
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
 * A writer schema that is a fresh instance on every call, but equal to the previous ones: what a caller gets when it
 * parses the writer schema per message, or from a schema registry client that re-parses instead of caching instances.
 *
 * The caches on the decode and encode paths are identity-first. A fresh schema misses the identity layer on every call,
 * so it must fall back to an equality lookup that returns the canonical instance, rather than resolving the record again
 * and seeding every node of the fresh schema into the identity tables (which would then grow with every call).
 *
 * The same small record is decoded and encoded three ways:
 * - `*Reused`: the schema is parsed once and the same instance is handed in on every call - the control;
 * - `*FreshApache`: parsed per call with Apache's `Schema.Parser`, then converted with `toAvro4k()` - the path of a
 *   caller that already speaks Apache schemas;
 * - `*FreshAvroSchema`: parsed per call straight into an `AvroSchema` from its json.
 *
 * The `parse*` methods measure the parsing alone, so that a fresh row minus its parse row is the cost the fresh
 * instance adds to the decode or encode itself, which should be close to nothing.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class FreshWriterSchemaMicroBenchmark {
    lateinit var value: SmallRecord

    /** The writer schema's json, parsed again by every fresh variant. */
    lateinit var writerSchemaJson: String

    /** The writer schema parsed once from [writerSchemaJson], the same instance on every call. */
    lateinit var reusedSchema: CoreSchema
    lateinit var data: ByteArray

    /** Allocated once so that the write methods only measure the encoding, not the sink construction. */
    lateinit var sink: Sink

    @Setup
    fun setup() {
        writerSchemaJson = Avro.apacheSchema<SmallRecord>().toString()
        reusedSchema = Schema.Parser().parse(writerSchemaJson).toCoreSchema()
        value = SmallRecord(42L, true)
        data = Avro.encodeWith(reusedSchema, SmallRecord.serializer(), value)
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    @Benchmark
    fun readReused(): SmallRecord = Avro.decodeFromByteArray(reusedSchema, SmallRecord.serializer(), data)

    @Benchmark
    fun readFreshApache(): SmallRecord =
        Avro.decodeFromByteArray(Schema.Parser().parse(writerSchemaJson).toAvro4k(), SmallRecord.serializer(), data)

    @Benchmark
    fun readFreshAvroSchema(): SmallRecord =
        Avro.decodeFromByteArray(AvroSchema.fromJsonString(writerSchemaJson), SmallRecord.serializer(), data)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeReused() {
        Avro.encodeToSink(reusedSchema, SmallRecord.serializer(), value, sink)
        sink.flush()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeFreshApache() {
        Avro.encodeToSink(Schema.Parser().parse(writerSchemaJson).toAvro4k(), SmallRecord.serializer(), value, sink)
        sink.flush()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeFreshAvroSchema() {
        Avro.encodeToSink(AvroSchema.fromJsonString(writerSchemaJson), SmallRecord.serializer(), value, sink)
        sink.flush()
    }

    /** The control for the `*FreshApache` methods: the parse and conversion alone. */
    @Benchmark
    fun parseApache(): AvroSchema = Schema.Parser().parse(writerSchemaJson).toAvro4k()

    /** The control for the `*FreshAvroSchema` methods: the parse alone. */
    @Benchmark
    fun parseAvroSchema(): AvroSchema = AvroSchema.fromJsonString(writerSchemaJson)
}
