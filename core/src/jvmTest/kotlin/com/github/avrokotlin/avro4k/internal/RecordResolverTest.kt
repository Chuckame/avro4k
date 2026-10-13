package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.toAvro4k
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import org.apache.avro.Schema
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Guards the inline `(writerSchema, classDescriptor)` cache sitting in front of the weak-key cache:
 * it must never hand back the workflow resolved for another pair, whatever the interleaving.
 */
internal class RecordResolverTest : StringSpec({
    val exactDescriptor = TwoFields.serializer().descriptor
    val reorderedDescriptor = TwoFieldsReordered.serializer().descriptor
    val exactSchema = Avro.avroSchema(exactDescriptor)
    val reorderedSchema = recordSchema("ReorderedWriter", """{"name":"b","type":"string"},{"name":"a","type":"int"}""")

    "returns the very same workflow instance for repeated calls with the same pair" {
        val resolver = RecordResolver(Avro)

        val first = resolver.resolveFields(exactSchema, exactDescriptor)

        repeat(1_000) {
            resolver.resolveFields(exactSchema, exactDescriptor) shouldBeSameInstanceAs first
        }
    }

    "alternating two descriptors against the same schema never returns the wrong workflow" {
        val resolver = RecordResolver(Avro)

        repeat(1_000) {
            resolver.resolveFields(exactSchema, exactDescriptor).encoding shouldBe EncodingWorkflow.ExactMatch

            val reordered = resolver.resolveFields(exactSchema, reorderedDescriptor)
            (reordered.encoding as EncodingWorkflow.NonContiguous).descriptorToWriterFieldIndex.toList() shouldBe listOf(1, 0)
            reordered.decoding.map { (it as DecodingStep.DeserializeWriterField).elementIndex } shouldBe listOf(1, 0)
        }
    }

    "alternating two schemas against the same descriptor never returns the wrong workflow" {
        val resolver = RecordResolver(Avro)

        repeat(1_000) {
            resolver.resolveFields(exactSchema, exactDescriptor).encoding shouldBe EncodingWorkflow.ExactMatch

            val reordered = resolver.resolveFields(reorderedSchema, exactDescriptor)
            (reordered.encoding as EncodingWorkflow.NonContiguous).descriptorToWriterFieldIndex.toList() shouldBe listOf(1, 0)
            reordered.decoding.map { (it as DecodingStep.DeserializeWriterField).elementIndex } shouldBe listOf(1, 0)
        }
    }

    "two structurally equal but distinct schema instances resolve to the same workflow" {
        val resolver = RecordResolver(Avro)
        val copy = recordSchema("TwoFields", """{"name":"a","type":"int"},{"name":"b","type":"string"}""")
        copy shouldBe exactSchema

        // Identity matching in the inline cache and in the identity layer must not shortcut the equality layer behind
        // them: an equal copy (a writer schema parsed per call) gets the workflow already resolved for the first one.
        val original = resolver.resolveFields(exactSchema, exactDescriptor)
        resolver.resolveFields(copy, exactDescriptor) shouldBeSameInstanceAs original
        resolver.resolveFields(exactSchema, exactDescriptor) shouldBeSameInstanceAs original
    }

    "keeps resolving correctly with far more distinct pairs than the inline cache capacity" {
        val resolver = RecordResolver(Avro)
        // Each schema carries a different number of leading writer-only fields, so the resolved workflow has a
        // distinct shape per schema: a wrong hit in the inline cache is immediately visible.
        val schemas = (0 until 64).map { leading -> leading to evolvedSchema(leading) }

        repeat(5) {
            schemas.forEach { (leading, schema) ->
                val workflow = resolver.resolveFields(schema, exactDescriptor)
                workflow.decoding.size shouldBe leading + 2
                workflow.decoding.count { it is DecodingStep.SkipWriterField } shouldBe leading
            }
        }
    }

    "a resolved workflow does not keep its class descriptor alive, missing elements computed or not" {
        val avro = Avro {}
        val resolver = RecordResolver(avro)
        val writerSchema = recordSchema("FreshRecord", """{"name":"a","type":"int"},{"name":"b","type":"string"}""")

        fun resolveWithFreshDescriptor(computeMissingElements: Boolean): WeakReference<SerialDescriptor> {
            val descriptor =
                buildClassSerialDescriptor("FreshRecord") {
                    element<Int>("a")
                    element<String>("b")
                }
            val workflow = resolver.resolveFields(writerSchema, descriptor)
            if (computeMissingElements) resolver.missingElements(workflow, descriptor).size shouldBe 2
            return WeakReference(descriptor)
        }

        val references = listOf(resolveWithFreshDescriptor(false), resolveWithFreshDescriptor(true))
        // the inline cache holds its last pairs strongly, by design: push them out
        (0 until 64).forEach { resolver.resolveFields(evolvedSchema(it), exactDescriptor) }

        repeat(50) {
            if (references.all { it.get() == null }) return@repeat
            System.gc()
            Thread.sleep(20)
        }
        references.map { it.get() == null } shouldBe listOf(true, true)
    }

    "is safe under concurrent use of a single resolver" {
        val resolver = RecordResolver(Avro)
        val pairs: List<Pair<AvroSchema, SerialDescriptor>> =
            listOf(
                exactSchema to exactDescriptor,
                exactSchema to reorderedDescriptor,
                reorderedSchema to exactDescriptor
            ) + (0 until 30).map { evolvedSchema(it) to exactDescriptor }

        // The expected instance per pair: resolveFields is backed by a real cache, so it is stable forever.
        val expected = pairs.associateWith { (schema, descriptor) -> resolver.resolveFields(schema, descriptor) }

        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks =
                (0 until threadCount).map { threadIndex ->
                    Callable {
                        barrier.await(30, TimeUnit.SECONDS)
                        var cursor = threadIndex
                        repeat(20_000) {
                            // Threads walk the pairs in different strides to maximise inline-cache churn.
                            cursor = (cursor + threadIndex + 1) % pairs.size
                            val pair = pairs[cursor]
                            val actual = resolver.resolveFields(pair.first, pair.second)
                            check(actual === expected.getValue(pair)) {
                                "Resolver returned the workflow of another (schema, descriptor) pair"
                            }
                        }
                    }
                }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}) {
    @Serializable
    @SerialName("TwoFields")
    private data class TwoFields(val a: Int, val b: String)

    @Serializable
    @SerialName("TwoFieldsReordered")
    private data class TwoFieldsReordered(val b: String, val a: Int)
}

private fun recordSchema(name: String, fields: String): AvroSchema = Schema.Parser().parse("""{"type":"record","name":"$name","fields":[$fields]}""").toAvro4k()

/** A writer schema with [leading] extra fields the class descriptor does not know about, so they get skipped. */
private fun evolvedSchema(leading: Int): AvroSchema =
    recordSchema(
        "Evolved$leading",
        (0 until leading).joinToString("") { """{"name":"x$it","type":"int"},""" } + """{"name":"a","type":"int"},{"name":"b","type":"string"}"""
    )