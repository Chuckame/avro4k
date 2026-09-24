package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToSink
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
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Isolates the collection handling: the collection-serializer wrapper allocated once per
 * collection, and the array block decoding.
 *
 * [readLongs]/[writeLongs] use the cheapest possible element type, so the per-element work is
 * nothing but a varint. [readRecords]/[writeRecords] use a two-field record, so they add one
 * begin/endStructure per element on top of the very same array handling: the difference between
 * the two families attributes the per-element structure cost, and the shape of each family over
 * [size] separates the fixed per-collection cost from the per-element one.
 *
 * [readTwoCollections] decodes [size] records that each hold two small collections, so the collection
 * handling runs twice per record with two alternating collection serializers.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class CollectionMicroBenchmark {
    @Param("10", "1000", "100000")
    @JvmField
    final var size: Int = 10

    lateinit var longs: LongList
    lateinit var longsSchema: CoreSchema
    lateinit var longsData: ByteArray

    lateinit var records: SmallRecordList
    lateinit var recordsSchema: CoreSchema
    lateinit var recordsData: ByteArray

    lateinit var twoCollections: TwoCollectionsRecordList
    lateinit var twoCollectionsSchema: CoreSchema
    lateinit var twoCollectionsData: ByteArray

    /** Allocated once so that the write methods only measure the encoding, not the sink construction. */
    lateinit var sink: Sink

    @Setup
    fun setup() {
        longsSchema = Avro.coreSchema<LongList>()
        recordsSchema = Avro.coreSchema<SmallRecordList>()
        longs = longList(size)
        records = smallRecordList(size)
        longsData = Avro.encodeWith(longsSchema, longs)
        recordsData = Avro.encodeWith(recordsSchema, records)
        twoCollectionsSchema = Avro.coreSchema<TwoCollectionsRecordList>()
        twoCollections = twoCollectionsRecordList(size)
        twoCollectionsData = Avro.encodeWith(twoCollectionsSchema, twoCollections)
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    @Benchmark
    fun readLongs(): LongList = Avro.decodeFromByteArray<LongList>(longsSchema, longsData)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeLongs() {
        Avro.encodeToSink(longsSchema, longs, sink)
        sink.flush()
    }

    @Benchmark
    fun readRecords(): SmallRecordList = Avro.decodeFromByteArray<SmallRecordList>(recordsSchema, recordsData)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeRecords() {
        Avro.encodeToSink(recordsSchema, records, sink)
        sink.flush()
    }

    @Benchmark
    fun readTwoCollections(): TwoCollectionsRecordList =
        Avro.decodeFromByteArray<TwoCollectionsRecordList>(twoCollectionsSchema, twoCollectionsData)
}
