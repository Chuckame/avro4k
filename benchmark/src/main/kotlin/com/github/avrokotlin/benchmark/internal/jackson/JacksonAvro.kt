package com.github.avrokotlin.benchmark.internal.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.dataformat.avro.AvroMapper
import com.fasterxml.jackson.dataformat.avro.jsr310.AvroJavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

internal object JacksonAvro {
    private const val MODEL_NAMESPACE = "com.github.avrokotlin.benchmark.internal.jackson"
    private const val CANONICAL_NAMESPACE = "com.github.avrokotlin.benchmark.internal"

    /**
     * Declares, for [com.github.avrokotlin.benchmark.internal.SchemaEquivalence], the one package in
     * which Jackson keeps its copy of a model. Only the `complex` workload needs a copy; `simple`
     * and `lists` use avro4k's own model, which Jackson round-trips as-is.
     */
    val NAMESPACE_ALIASES = mapOf(MODEL_NAMESPACE to CANONICAL_NAMESPACE)

    /**
     * The mapper every Jackson benchmark and the equivalence gate share, so that what is verified is
     * what is measured.
     *
     * - `SORT_PROPERTIES_ALPHABETICALLY` off: Avro's encoding is positional, so the property order
     *   has to stay the declaration order avro4k uses.
     * - `AUTO_CLOSE_TARGET` off: the benchmarks hoist one `OutputStream` into `@Setup` and write to
     *   it on every operation, so it must survive the writer closing its generator.
     */
    fun mapper(): AvroMapper = AvroMapper().apply {
        disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        registerKotlinModule()
        registerModule(AvroJavaTimeModule())
    }
}
