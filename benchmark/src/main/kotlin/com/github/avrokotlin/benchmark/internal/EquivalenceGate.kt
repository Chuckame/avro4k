package com.github.avrokotlin.benchmark.internal

import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory

/**
 * Proves, before anything is measured, that the libraries being compared are doing the same job.
 *
 * A benchmark that compares three libraries is only meaningful if all three describe the data the
 * same way and put the same bytes on the wire. Until A5 nothing checked that, and the consequence
 * was not a wrong number but a *missing* one: nine read benchmarks threw in `@Setup`, JMH dropped
 * them, and the report simply had no row for them. This gate turns that class of problem into a
 * loud failure at setup time.
 *
 * For each library it asserts:
 *
 * 1. **Same schema** — the schema that library derives for *its own* model is structurally
 *    equivalent to `Avro.schema<T>()`, the canonical writer schema. See [SchemaEquivalence] for
 *    exactly what that comparison does and does not catch.
 * 2. **Same bytes** — encoding the same logical data produces a byte array identical to avro4k's.
 * 3. **Nothing lost on read** — decoding avro4k's bytes with that library and re-encoding the result
 *    reproduces avro4k's bytes. Without this a library could decode half a record, return fast, and
 *    look like the winner.
 *
 * Where a library provably cannot satisfy one of these, the deviation is declared in code as a
 * [SchemaExemption] or a [ByteExemption] *with its reason*, and a schema exemption that no longer
 * matches anything is itself a failure — so an exemption cannot quietly outlive the limitation that
 * justified it. A [ByteExemption] downgrades byte identity to *decoded-datum* equality, which still
 * proves every value is the same and narrows the difference to framing or map entry order.
 */
internal object EquivalenceGate {
    fun verify(
        workload: String,
        canonicalSchema: Schema,
        canonicalBytes: ByteArray,
        libraries: List<ComparedLibrary>,
    ) {
        libraries.forEach { library ->
            val label = "$workload/${library.label}"
            SchemaEquivalence.assertEquivalent(
                label = label,
                canonical = canonicalSchema,
                candidate = library.schema,
                namespaceAliases = library.namespaceAliases,
                exemptions = library.schemaExemptions,
            )
            assertEncoding("$label write", canonicalSchema, canonicalBytes, library.encode(), library.byteExemption)
            assertEncoding(
                "$label read/re-encode",
                canonicalSchema,
                canonicalBytes,
                library.decodeAndReEncode(canonicalBytes),
                library.byteExemption,
            )
        }
    }

    private fun assertEncoding(
        label: String,
        canonicalSchema: Schema,
        expected: ByteArray,
        actual: ByteArray,
        exemption: ByteExemption?,
    ) {
        if (exemption == null) {
            ByteEquivalence.assertSameBytes(label, expected, actual)
            return
        }
        if (expected.contentEquals(actual)) return
        val expectedDatum = decode(canonicalSchema, expected)
        val actualDatum = decode(canonicalSchema, actual)
        if (expectedDatum != actualDatum) {
            throw AssertionError(
                "[$label] does not even decode to the same data as avro4k's encoding.\n" +
                    "  This library is exempt from byte identity because: ${exemption.reason}\n" +
                    "  That exemption only covers a difference in framing or map entry order; the decoded\n" +
                    "  values differ, which it does not cover."
            )
        }
    }

    private fun decode(schema: Schema, bytes: ByteArray): GenericRecord =
        GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(bytes, null))
}

/** One library's side of a workload comparison. */
internal class ComparedLibrary(
    val label: String,
    /** The schema this library derives for its own copy of the model. */
    val schema: Schema,
    /** Candidate namespace -> canonical namespace, for the package this library keeps its model in. */
    val namespaceAliases: Map<String, String> = emptyMap(),
    val schemaExemptions: List<SchemaExemption> = emptyList(),
    val byteExemption: ByteExemption? = null,
    /** Encodes this library's copy of the shared data. */
    val encode: () -> ByteArray,
    /** Decodes avro4k's bytes with this library, then re-encodes the result with it. */
    val decodeAndReEncode: (ByteArray) -> ByteArray,
)

/**
 * A declared, reasoned difference at one exact path of the schema — for example
 * `Clients.clients[].gender<1>`, the second branch of the `gender` union.
 *
 * The path must match the difference exactly. An exemption that matches nothing fails the gate, so
 * it cannot survive the limitation that justified it.
 */
internal class SchemaExemption(val path: String, val reason: String)

/** A declared, reasoned inability to reproduce avro4k's bytes exactly. */
internal class ByteExemption(val reason: String)
