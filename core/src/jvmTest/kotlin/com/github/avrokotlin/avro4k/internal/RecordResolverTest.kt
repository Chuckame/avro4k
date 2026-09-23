package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema
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
    val exactSchema = Avro.schema(exactDescriptor)
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
        val copy = Schema.Parser().parse(exactSchema.toString())

        // Identity matching in the inline cache must not shortcut the weak-key cache, which matches by equality.
        resolver.resolveFields(exactSchema, exactDescriptor) shouldBeSameInstanceAs resolver.resolveFields(copy, exactDescriptor)
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

    "is safe under concurrent use of a single resolver" {
        val resolver = RecordResolver(Avro)
        val pairs: List<Pair<Schema, SerialDescriptor>> =
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

private fun recordSchema(name: String, fields: String): Schema = Schema.Parser().parse("""{"type":"record","name":"$name","fields":[$fields]}""")

/** A writer schema with [leading] extra fields the class descriptor does not know about, so they get skipped. */
private fun evolvedSchema(leading: Int): Schema =
    recordSchema(
        "Evolved$leading",
        (0 until leading).joinToString("") { """{"name":"x$it","type":"int"},""" } + """{"name":"a","type":"int"},{"name":"b","type":"string"}"""
    )