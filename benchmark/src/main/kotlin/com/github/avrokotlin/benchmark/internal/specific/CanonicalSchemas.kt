package com.github.avrokotlin.benchmark.internal.specific

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.benchmark.internal.Clients
import com.github.avrokotlin.benchmark.internal.ListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import org.apache.avro.Schema
import java.io.File

/**
 * Writes the `.avsc` files under `benchmark/src/main/avro` — the input Avro's `SpecificCompiler` turns into the
 * generated `SpecificRecord` classes the `apache-specific` benchmarks measure.
 *
 * Those files are **derived artefacts, not sources**: every one of them is
 * `Avro.schema<T>().toString()` with a single mechanical edit, the namespace rewrite below. Nothing
 * in them is hand-written, and nothing in them may be hand-edited — run
 * `./gradlew :benchmark:regenerateAvroSchemas` instead.
 *
 * The rewrite exists because the generated Java classes would otherwise collide, name for name,
 * with avro4k's own model: both would be `com.github.avrokotlin.benchmark.internal.Clients`. The
 * equivalence gate is told about the one substituted namespace through
 * [com.github.avrokotlin.benchmark.internal.apache.ApacheAvro.SPECIFIC_NAMESPACE_ALIASES], so any
 * *other* namespace difference is still a failure.
 *
 * This task is a convenience, not the drift detector. Drift is caught at benchmark `@Setup`: the
 * gate compares each generated class' `SCHEMA$` against `Avro.schema<T>()`, so an `.avsc` that
 * falls behind avro4k fails every `apache-specific` trial with the exact differing path.
 */
internal object CanonicalSchemas {
    const val CANONICAL_NAMESPACE = "com.github.avrokotlin.benchmark.internal"
    const val SPECIFIC_NAMESPACE = "com.github.avrokotlin.benchmark.internal.specific"

    private val SCHEMAS: Map<String, () -> Schema> = mapOf(
        "Clients.avsc" to { Avro.schema<Clients>() },
        "SimpleDatasClass.avsc" to { Avro.schema<SimpleDatasClass>() },
        "ListWrapperDatasClass.avsc" to { Avro.schema<ListWrapperDatasClass>() },
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = File(args.single()).apply { mkdirs() }
        SCHEMAS.forEach { (fileName, schema) ->
            val json = schema().toString(true).replace(
                "\"$CANONICAL_NAMESPACE\"",
                "\"$SPECIFIC_NAMESPACE\"",
            )
            File(outputDir, fileName).writeText(json + "\n")
            println("wrote ${File(outputDir, fileName).absolutePath}")
        }
    }
}
