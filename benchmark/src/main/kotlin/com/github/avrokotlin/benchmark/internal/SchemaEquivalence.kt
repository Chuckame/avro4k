package com.github.avrokotlin.benchmark.internal

import org.apache.avro.Schema

/**
 * Structural comparison of two Avro schemas, used by [EquivalenceGate] to prove that every
 * library in the comparison describes *the same* record layout as avro4k does.
 *
 * ## Why not [Schema.equals] or [org.apache.avro.SchemaNormalization.toParsingForm]
 *
 * - [Schema.equals] compares *everything*, including reflection hints (`java-class`,
 *   `avro.java.string`, …) and field defaults. Apache `ReflectData` emits those hints for every
 *   collection, array and boxed primitive and never emits a field default, while avro4k emits
 *   `"default": []` / `{}` / `null` and no hints. Two schemas that encode byte-for-byte identically
 *   therefore never compare equal, so `Schema.equals` is unusable here.
 * - `SchemaNormalization.toParsingForm` goes too far the other way: it **strips logical types**, so
 *   a `decimal` silently degrading to plain `bytes`, or a `timestamp-millis` to a plain `long`,
 *   would compare equal. That is exactly the drift this gate exists to catch.
 *
 * ## What this comparison catches
 *
 * - a different Avro [Schema.Type] anywhere in the tree;
 * - a different `logicalType` **property string** — compared as a raw prop, not via
 *   [Schema.getLogicalType], so a logical type that is not registered in Avro's global
 *   [org.apache.avro.LogicalTypes] registry (avro4k's `char`, for instance) is still compared;
 * - record fields: count, names, **and order** (Avro binary encoding is positional, so order is
 *   part of the wire format);
 * - enum symbols and their order, and the enum default symbol;
 * - `fixed` sizes;
 * - union branch count and **branch order** (the branch index is what goes on the wire);
 * - named-type simple names, and namespaces after applying [namespaceAliases];
 * - every schema/field property that is not in [IGNORED_PROPS].
 *
 * ## What it deliberately does not catch
 *
 * - **Field defaults.** avro4k emits them, `ReflectData` does not. They do not affect the binary
 *   encoding when writer and reader schema are the same, which is the case for every benchmark
 *   here; they only matter for schema resolution against a *different* reader schema.
 * - **The reflection hint props in [IGNORED_PROPS].** They steer a library's object mapping
 *   (`java-class: "[B"` makes `ReflectData` hand back a `byte[]` rather than a `ByteBuffer`) and
 *   have no effect on the bytes.
 * - **`doc` and `aliases`.**
 * - **The model package**, to the extent it is declared in [namespaceAliases]: each library needs
 *   its own copy of the model in its own package, so the record full names cannot be identical.
 *   Any namespace difference *not* declared in the alias map is still a failure.
 *
 * The byte-identity half of [EquivalenceGate] is what covers the gaps: anything this comparison
 * tolerates but that would change the wire format shows up there as a differing offset.
 */
internal object SchemaEquivalence {
    /**
     * Object-mapping hints. They tell a library which Java type to materialise for a given Avro
     * type and never change the encoding.
     */
    private val IGNORED_PROPS = setOf(
        // org.apache.avro.specific.SpecificData.CLASS_PROP / KEY_CLASS_PROP / ELEMENT_PROP
        "java-class",
        "java-key-class",
        "java-element-class",
        // org.apache.avro.generic.GenericData.STRING_PROP
        "avro.java.string",
    )

    private const val LOGICAL_TYPE_PROP = "logicalType"

    /**
     * @param namespaceAliases candidate namespace -> canonical namespace. Declares, explicitly, the
     * packages in which a library keeps its own copy of the model.
     * @param exemptions differences this library provably cannot avoid, each with its reason. An
     * exemption that matches no difference fails too, so it cannot outlive its justification.
     */
    fun assertEquivalent(
        label: String,
        canonical: Schema,
        candidate: Schema,
        namespaceAliases: Map<String, String> = emptyMap(),
        exemptions: List<SchemaExemption> = emptyList(),
    ) {
        val differences = mutableListOf<Difference>()
        Comparison(namespaceAliases, differences).compare(canonical.name, canonical, candidate)

        val exemptedPaths = exemptions.map { it.path }.toSet()
        val unexplained = differences.filter { it.path !in exemptedPaths }
        val stale = exemptions.filter { exemption -> differences.none { it.path == exemption.path } }

        if (unexplained.isEmpty() && stale.isEmpty()) return
        throw AssertionError(
            buildString {
                append("[$label] schema is not equivalent to the canonical avro4k schema.")
                if (unexplained.isNotEmpty()) {
                    append("\n\n${unexplained.size} undeclared difference(s):")
                    unexplained.forEach { append("\n  - ").append(it.path).append(": ").append(it.detail) }
                    append("\n\nIf one of these is a limitation of the library rather than a bug in the model,")
                    append("\ndeclare it as a SchemaExemption with its reason - do not widen the comparison.")
                }
                if (stale.isNotEmpty()) {
                    append("\n\n${stale.size} stale exemption(s) - the schemas now agree here, so remove them:")
                    stale.forEach { append("\n  - ").append(it.path).append(" (declared because: ").append(it.reason).append(")") }
                }
                append("\n\ncanonical (avro4k):\n").append(canonical.toString(true))
                append("\n\ncandidate ($label):\n").append(candidate.toString(true))
            }
        )
    }

    private class Difference(val path: String, val detail: String)

    private class Comparison(
        private val namespaceAliases: Map<String, String>,
        private val differences: MutableList<Difference>,
    ) {
        /** Guards against infinite recursion on recursive schemas. */
        private val seen = mutableSetOf<Pair<String, String>>()

        fun compare(path: String, canonical: Schema, candidate: Schema) {
            if (canonical.type != candidate.type) {
                differences += Difference(path, "type ${canonical.type} != ${candidate.type}")
                return
            }
            compareLogicalType(path, canonical, candidate)
            compareProps(path, canonical, candidate)

            when (canonical.type) {
                Schema.Type.RECORD -> {
                    if (!compareName(path, canonical, candidate)) return
                    if (!seen.add(canonical.fullName to candidate.fullName)) return
                    compareFields(path, canonical, candidate)
                }

                Schema.Type.ENUM -> {
                    if (!compareName(path, canonical, candidate)) return
                    if (canonical.enumSymbols != candidate.enumSymbols) {
                        differences += Difference(path, "enum symbols ${canonical.enumSymbols} != ${candidate.enumSymbols}")
                    }
                    if (canonical.enumDefault != candidate.enumDefault) {
                        differences += Difference(path, "enum default ${canonical.enumDefault} != ${candidate.enumDefault}")
                    }
                }

                Schema.Type.FIXED -> {
                    if (!compareName(path, canonical, candidate)) return
                    if (canonical.fixedSize != candidate.fixedSize) {
                        differences += Difference(path, "fixed size ${canonical.fixedSize} != ${candidate.fixedSize}")
                    }
                }

                Schema.Type.ARRAY -> compare("$path[]", canonical.elementType, candidate.elementType)
                Schema.Type.MAP -> compare("$path{}", canonical.valueType, candidate.valueType)
                Schema.Type.UNION -> compareUnion(path, canonical, candidate)
                else -> Unit
            }
        }

        private fun compareFields(path: String, canonical: Schema, candidate: Schema) {
            val canonicalNames = canonical.fields.map { it.name() }
            val candidateNames = candidate.fields.map { it.name() }
            if (canonicalNames != candidateNames) {
                differences += Difference(
                    path,
                    "field names/order $canonicalNames != $candidateNames" +
                        describeFieldDelta(canonicalNames, candidateNames),
                )
                return
            }
            canonical.fields.forEachIndexed { index, canonicalField ->
                val candidateField = candidate.fields[index]
                comparePropsOf(
                    "$path.${canonicalField.name()}",
                    canonicalField.objectProps,
                    candidateField.objectProps,
                )
                compare("$path.${canonicalField.name()}", canonicalField.schema(), candidateField.schema())
            }
        }

        private fun describeFieldDelta(canonicalNames: List<String>, candidateNames: List<String>): String {
            val missing = canonicalNames - candidateNames.toSet()
            val extra = candidateNames - canonicalNames.toSet()
            return when {
                missing.isEmpty() && extra.isEmpty() -> " (same fields, different order)"
                else -> " (missing: $missing, unexpected: $extra)"
            }
        }

        private fun compareUnion(path: String, canonical: Schema, candidate: Schema) {
            if (canonical.types.size != candidate.types.size) {
                differences += Difference(
                    path,
                    "union has ${canonical.types.size} branch(es) ${canonical.types.map { it.fullName }} " +
                        "but candidate has ${candidate.types.size} ${candidate.types.map { it.fullName }}",
                )
                return
            }
            canonical.types.forEachIndexed { index, canonicalBranch ->
                compare("$path<$index>", canonicalBranch, candidate.types[index])
            }
        }

        /** @return false when the names differ, so the caller stops descending into a mismatched type. */
        private fun compareName(path: String, canonical: Schema, candidate: Schema): Boolean {
            val aliasedNamespace = namespaceAliases[candidate.namespace] ?: candidate.namespace
            val aliasedFullName = if (aliasedNamespace.isNullOrEmpty()) {
                candidate.name
            } else {
                "$aliasedNamespace.${candidate.name}"
            }
            if (canonical.fullName != aliasedFullName) {
                differences += Difference(
                    path,
                    "name ${canonical.fullName} != ${candidate.fullName}" +
                        if (aliasedFullName != candidate.fullName) " (aliased to $aliasedFullName)" else "",
                )
                return false
            }
            return true
        }

        private fun compareLogicalType(path: String, canonical: Schema, candidate: Schema) {
            // Deliberately the raw prop and not Schema.getLogicalType(): a logical type that is not
            // registered in Avro's global registry (avro4k's `char`) parses to a null LogicalType but
            // is still on the wire description, and dropping it is exactly the drift to catch.
            val canonicalLogical = canonical.getProp(LOGICAL_TYPE_PROP)
            val candidateLogical = candidate.getProp(LOGICAL_TYPE_PROP)
            if (canonicalLogical != candidateLogical) {
                differences += Difference(
                    path,
                    "logicalType ${canonicalLogical ?: "<none>"} != ${candidateLogical ?: "<none>"}",
                )
            }
        }

        private fun compareProps(path: String, canonical: Schema, candidate: Schema) {
            comparePropsOf(path, canonical.objectProps, candidate.objectProps)
        }

        private fun comparePropsOf(path: String, canonical: Map<String, Any>, candidate: Map<String, Any>) {
            val canonicalProps = canonical.filterKeys { it !in IGNORED_PROPS && it != LOGICAL_TYPE_PROP }
            val candidateProps = candidate.filterKeys { it !in IGNORED_PROPS && it != LOGICAL_TYPE_PROP }
            (canonicalProps.keys + candidateProps.keys).sorted().forEach { key ->
                val canonicalValue = canonicalProps[key]
                val candidateValue = candidateProps[key]
                if (canonicalValue != candidateValue) {
                    differences += Difference(
                        path,
                        "property '$key' ${canonicalValue ?: "<absent>"} != ${candidateValue ?: "<absent>"}",
                    )
                }
            }
        }
    }
}
