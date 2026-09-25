package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.internal.encoder.ReorderingCompositeEncoder
import com.github.avrokotlin.avro4k.internal.schema.asSchemaList
import com.github.avrokotlin.avro4k.internal.schema.fields
import com.github.avrokotlin.avro4k.isNullable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Capacity of the per-[RecordResolver] inline cache. Sized to comfortably hold the record types of a deeply
 * nested payload (the deepest nesting exercised by the benchmarks is 8) plus their collection wrappers, while
 * staying small enough that a miss-free lookup is a handful of reference comparisons.
 */
private const val INLINE_CACHE_CAPACITY = 16

private val EMPTY_JSON_ARRAY = JsonArray(emptyList())
private val EMPTY_JSON_OBJECT = JsonObject(emptyMap())

internal class RecordResolver(
    private val avro: Avro,
) {
    /**
     * For a class descriptor + writerSchema, it returns a map of the field index to the schema field.
     *
     * Note: We use the descriptor in the key as we could have multiple descriptors for the same record schema, and multiple record schemas for the same descriptor.
     *
     * Both levels are [IdentityFirstCache]s: an identity layer answers the usual case (the same instance again) with
     * reference comparisons only, and an equality layer behind it catches equal-but-distinct instances.
     * - Outer (writer schema) level: a writer schema is usually a long-lived instance (generated once per descriptor, or
     *   converted once per Apache schema instance), but a caller may also hand in a **fresh but equal** schema on every
     *   call (parsed per message, or returned by a registry lookup that re-parses). Such an instance hits the equality
     *   layer ([AvroSchema.equals], a structural walk, only on an identity miss; a record's hash is its name's), so its
     *   workflow is not resolved again, and it is never remembered by identity: only the instance that missed both
     *   layers becomes the canonical key. Identity alone would re-resolve the workflow and insert into the copy-on-write
     *   identity table on every such call.
     * - Inner (descriptor) level: record descriptors are mostly stable instances, but a generic record (`Box<T>`)
     *   obtained through a fresh top-level serializer is an equal-but-distinct instance per call.
     *
     * Equality is the model's: a record's aliases and doc take no part in it, so two writer schemas that differ only
     * by their aliases share one workflow, the one resolved for the first of them.
     *
     * `internal` for tests.
     */
    internal val fieldCache = IdentityFirstCache<AvroSchema, IdentityFirstCache<SerialDescriptor, SerializationWorkflow>>()

    /**
     * Inline (first-level) cache in front of [fieldCache], holding the last [INLINE_CACHE_CAPACITY] resolved
     * `(writerSchema, classDescriptor)` pairs, matched by **identity**.
     *
     * [resolveFields] is called once per record *instance*, so decoding a 100k-element array of records used to
     * perform 200k [fieldCache] lookups (two per record; when `WeakKeyCache` was JVM-only, each one was a
     * `ReferenceQueue.poll()`, a `Key.Lookup` allocation and a `ConcurrentHashMap` lookup) for a workflow that was already
     * resolved for the first element. A homogeneous collection or a nested record tree only ever cycles through a handful of pairs, so a tiny
     * identity-keyed cache turns almost all of those calls into a few reference comparisons and no allocation.
     *
     * Concurrency: the array is immutable once published, replaced wholesale by a copy-on-write assignment, and
     * only ever *read* on the hot path, so there is no locking and no write contention between threads. The field
     * is `@Volatile` for safe publication: array elements are not final fields, so a plain field would let a racing
     * thread observe a partially-initialized [Memo]. Losing a concurrent update is harmless — it only costs the
     * loser a future miss, which falls through to [fieldCache], the real cache.
     *
     * Note this pins up to [INLINE_CACHE_CAPACITY] schemas/descriptors strongly (per [Avro] instance), unlike
     * [fieldCache] which references its keys weakly. That is a bounded, small amount of retention.
     */
    @Volatile
    private var inlineCache: Array<Memo> = emptyArray()

    /**
     * Immutable holder: schema, descriptor and workflow are published together or not at all. Storing them in
     * three separate fields could tear and hand back the workflow of another type, which would silently corrupt
     * the encoded/decoded data.
     */
    private class Memo(
        val writerSchema: AvroSchema,
        val classDescriptor: SerialDescriptor,
        val workflow: SerializationWorkflow,
    )

    /**
     * Maps the class fields to the schema fields.
     * For encoding:
     * - prepares the fields to be encoded in the order they are in the writer schema
     * -
     *
     * For decoding:
     * - prepares the fields to be decoded in the order they are in the writer schema
     * - prepares the default values for the fields that are not in the writer schema
     * - prepares the fields to be skipped as they are in the writer schema but not in the class descriptor
     * - fails if a field is not optional, without a default value and not in the writer schema
     */
    fun resolveFields(
        writerSchema: AvroSchema,
        classDescriptor: SerialDescriptor,
    ): SerializationWorkflow {
        val memos = inlineCache
        for (index in memos.indices) {
            val memo = memos[index]
            // Identity is enough: the same instances always resolve to the same workflow. It also avoids
            // AvroSchema.equals(), which is a deep structural comparison.
            if (memo.writerSchema === writerSchema && memo.classDescriptor === classDescriptor) {
                return memo.workflow
            }
        }
        // Both getOrPut are inline: no lambda is allocated, on a hit or a miss.
        val workflow =
            fieldCache.getOrPut(writerSchema) { IdentityFirstCache() }
                .getOrPut(classDescriptor) {
                    loadCache(classDescriptor, writerSchema)
                }
        memoize(Memo(writerSchema, classDescriptor, workflow))
        return workflow
    }

    /**
     * Copy-on-write insertion of [memo] at the front of [inlineCache], dropping the oldest entry once
     * [INLINE_CACHE_CAPACITY] is reached. Only runs on a miss, so the copy is off the hot path.
     */
    private fun memoize(memo: Memo) {
        val current = inlineCache
        val newSize = minOf(current.size + 1, INLINE_CACHE_CAPACITY)
        inlineCache = Array(newSize) { if (it == 0) memo else current[it - 1] }
    }

    /**
     * Here the different steps to get the schema field corresponding to the serial descriptor element:
     * - class field name -> schema field name
     * - class field name -> schema field aliases
     * - class field aliases -> schema field name
     * - class field aliases -> schema field aliases
     * - if field is optional, returns null
     * - if still not found, [SerializationException] thrown
     */
    private fun loadCache(
        classDescriptor: SerialDescriptor,
        writerSchema: AvroSchema,
    ): SerializationWorkflow {
        val writerRecordSchema = writerSchema as? RecordSchema ?: throw SerializationException("Expected a record writer schema, but had $writerSchema")
        return SerializationWorkflow(
            computeDecodingSteps(classDescriptor, writerRecordSchema),
            computeEncodingWorkflow(classDescriptor, writerRecordSchema),
            // computed at most a few times on a race, and then only read: no lock
            lazy(LazyThreadSafetyMode.PUBLICATION) { computeMissingElementSteps(classDescriptor) }
        )
    }

    /**
     * The step of every element of [classDescriptor] when its value is missing, whatever the writer schema: see
     * [SerializationWorkflow.missingElements].
     */
    private fun computeMissingElementSteps(classDescriptor: SerialDescriptor): Array<DecodingStep> {
        val readerFields = avro.avroSchema(classDescriptor).fields
        return Array(classDescriptor.elementsCount) { elementIndex ->
            resolveMissingElement(classDescriptor, elementIndex, readerFields[elementIndex])
        }
    }

    private fun computeDecodingSteps(
        classDescriptor: SerialDescriptor,
        writerSchema: RecordSchema,
    ): Array<DecodingStep> {
        val decodingSteps = mutableListOf<DecodingStep>()
        val writerFields = writerSchema.fields

        val writerFieldIndexToElementIndex = IntArray(writerFields.size) { -1 }

        for (elementIndex in 0 until classDescriptor.elementsCount) {
            val writerFieldIndex = writerSchema.findFieldIndexMatchingWithElement(classDescriptor, elementIndex)
            if (writerFieldIndex < 0) continue

            if (writerFieldIndexToElementIndex[writerFieldIndex] >= 0) {
                throw IllegalStateException(
                    "The descriptor $classDescriptor has multiple elements matching to the same writer field '${writerFields[writerFieldIndex].name}'. " +
                        "This is not allowed as it would lead to ambiguous decoding."
                )
            }
            writerFieldIndexToElementIndex[writerFieldIndex] = elementIndex
        }

        val visitedElements = BooleanArray(classDescriptor.elementsCount) { false }

        writerFields.forEachIndexed { writerFieldIndex, field ->
            val elementIndex = writerFieldIndexToElementIndex[writerFieldIndex]
            decodingSteps +=
                if (elementIndex >= 0) {
                    visitedElements[elementIndex] = true
                    DecodingStep.DeserializeWriterField(
                        elementIndex = elementIndex,
                        writerFieldIndex = writerFieldIndex,
                        schema = field.schema
                    )
                } else {
                    DecodingStep.SkipWriterField(writerFieldIndex, field.schema)
                }
        }

        if (visitedElements.any { !it }) {
            // TODO we should not need to resolve the whole record's schema, but just extract the fields names, aliases and their order.
            //  However, the schema resolution also handle the union schema ordering based on the default value, which we also need here.
            val readerFields = avro.avroSchema(classDescriptor).fields

            // iterate over remaining elements in the class descriptor that are not in the writer schema
            // so it needs to set default values or skip them if they are optional
            visitedElements.forEachIndexed { elementIndex, visited ->
                if (visited) return@forEachIndexed

                decodingSteps += resolveMissingElement(classDescriptor, elementIndex, readerFields[elementIndex])
            }
        }
        return decodingSteps.toTypedArray()
    }

    /**
     * The step of an element whose value is missing, in this order: its `@AvroDefault` (the json its schema has, see
     * [toFieldDefault]), its kotlin default, the implicit `null` or empty collection when configured, or a failure. This is
     * the resolution of a reader field missing from the writer schema, and of a field missing from a record default value.
     */
    private fun resolveMissingElement(
        classDescriptor: SerialDescriptor,
        elementIndex: Int,
        readerField: RecordSchema.Field,
    ): DecodingStep {
        val readerDefaultAnnotation = classDescriptor.findElementAnnotation<AvroDefault>(elementIndex)
        val readerFieldSchema = readerField.schema
        return if (readerDefaultAnnotation != null) {
            val defaultValue = readerDefaultAnnotation.toFieldDefault(readerFieldSchema)
            DecodingStep.GetDefaultValue(
                elementIndex = elementIndex,
                schema = readerFieldSchema.resolveDefaultBranch(defaultValue),
                defaultValue = defaultValue
            )
        } else if (classDescriptor.isElementOptional(elementIndex)) {
            // There is already a kotlin default value for this element, so we can skip it.
            // We don't want to put an implicit null/empty collection here as it would override the kotlin default.
            DecodingStep.IgnoreOptionalElement(elementIndex)
        } else if (avro.configuration.implicitNulls && readerFieldSchema.isNullable) {
            DecodingStep.GetDefaultValue(
                elementIndex = elementIndex,
                schema = readerFieldSchema.asSchemaList().first { it.type === AvroSchema.Type.NULL },
                defaultValue = JsonNull
            )
        } else if (avro.configuration.implicitEmptyCollections && readerFieldSchema.isTypeOf(AvroSchema.Type.ARRAY)) {
            DecodingStep.GetDefaultValue(
                elementIndex = elementIndex,
                schema = readerFieldSchema.asSchemaList().first { it.type === AvroSchema.Type.ARRAY },
                defaultValue = EMPTY_JSON_ARRAY
            )
        } else if (avro.configuration.implicitEmptyCollections && readerFieldSchema.isTypeOf(AvroSchema.Type.MAP)) {
            DecodingStep.GetDefaultValue(
                elementIndex = elementIndex,
                schema = readerFieldSchema.asSchemaList().first { it.type === AvroSchema.Type.MAP },
                defaultValue = EMPTY_JSON_OBJECT
            )
        } else {
            DecodingStep.MissingElementValueFailure(elementIndex)
        }
    }

    private fun computeEncodingWorkflow(
        classDescriptor: SerialDescriptor,
        writerSchema: RecordSchema,
    ): EncodingWorkflow {
        // Encoding steps are ordered regarding the class descriptor and not the writer schema.
        // Because kotlinx-serialization doesn't provide a way to encode non-sequentially elements.
        val missingWriterFieldsIndexes = mutableListOf<Int>()
        val visitedWriterFields = BooleanArray(writerSchema.fields.size) { false }
        val descriptorToWriterFieldIndex = IntArray(classDescriptor.elementsCount) { ReorderingCompositeEncoder.SKIP_ELEMENT_INDEX }

        var expectedNextWriterIndex = 0

        for (elementIndex in 0 until classDescriptor.elementsCount) {
            val writerFieldIndex = writerSchema.findFieldIndexMatchingWithElement(classDescriptor, elementIndex)
            if (writerFieldIndex < 0) continue

            if (visitedWriterFields[writerFieldIndex]) {
                throw IllegalStateException(
                    "The descriptor $classDescriptor has multiple elements matching to the same writer field '${writerSchema.fields[writerFieldIndex].name}'. " +
                        "This is not allowed as it would lead to ambiguous encoding."
                )
            }
            visitedWriterFields[writerFieldIndex] = true
            descriptorToWriterFieldIndex[elementIndex] = writerFieldIndex
            if (expectedNextWriterIndex != -1) {
                if (writerFieldIndex != expectedNextWriterIndex) {
                    expectedNextWriterIndex = -1
                } else {
                    expectedNextWriterIndex++
                }
            }
        }

        visitedWriterFields.forEachIndexed { writerFieldIndex, visited ->
            if (!visited) {
                missingWriterFieldsIndexes += writerFieldIndex
            }
        }

        return if (missingWriterFieldsIndexes.isNotEmpty()) {
            EncodingWorkflow.MissingWriterFields(missingWriterFieldsIndexes)
        } else if (expectedNextWriterIndex == -1) {
            EncodingWorkflow.NonContiguous(descriptorToWriterFieldIndex)
        } else if (classDescriptor.elementsCount != writerSchema.fields.size) {
            EncodingWorkflow.ContiguousWithSkips(descriptorToWriterFieldIndex.map { it == ReorderingCompositeEncoder.SKIP_ELEMENT_INDEX }.toBooleanArray())
        } else {
            EncodingWorkflow.ExactMatch
        }
    }

    /**
     * The index of the writer field matching the element, or -1: by the element's name (after the naming strategy), then by
     * its aliases, each looked up among the writer fields' names and aliases ([RecordSchema.getFieldIndexOrNull], a hash
     * lookup). Runs once per (writer schema, descriptor) pair, when its workflow is resolved, never per value.
     */
    private fun RecordSchema.findFieldIndexMatchingWithElement(
        classDescriptor: SerialDescriptor,
        elementIndex: Int,
    ): Int {
        getFieldIndexOrNull(avro.configuration.fieldNamingStrategy.resolve(classDescriptor, elementIndex))?.let { return it }
        for (alias in classDescriptor.getElementAliases(elementIndex)) {
            getFieldIndexOrNull(alias)?.let { return it }
        }
        return -1
    }

    private fun AvroSchema.isTypeOf(expectedType: AvroSchema.Type): Boolean = asSchemaList().any { it.type === expectedType }
}

internal class SerializationWorkflow(
    /**
     * Decoding steps are ordered regarding the writer schema and not the class descriptor.
     */
    val decoding: Array<DecodingStep>,
    /**
     * Encoding steps are ordered regarding the class descriptor and not the writer schema.
     */
    val encoding: EncodingWorkflow,
    /**
     * The step of each element, by element index, when its value is missing: a [DecodingStep.GetDefaultValue],
     * [DecodingStep.IgnoreOptionalElement] or [DecodingStep.MissingElementValueFailure]. Used for the fields a record default
     * value leaves out, which take their own default, as the specification reads a record default. Computed on first use,
     * as only default values need it.
     */
    missingElements: Lazy<Array<DecodingStep>>,
) {
    val missingElements: Array<DecodingStep> by missingElements
}

internal sealed interface EncodingWorkflow {
    /**
     * The descriptor elements exactly matches the writer schema fields as a 1-to-1 mapping.
     */
    data object ExactMatch : EncodingWorkflow

    class ContiguousWithSkips(
        val fieldsToSkip: BooleanArray,
    ) : EncodingWorkflow

    class NonContiguous(
        val descriptorToWriterFieldIndex: IntArray,
    ) : EncodingWorkflow

    class MissingWriterFields(
        val missingWriterFields: List<Int>,
    ) : EncodingWorkflow
}

internal sealed interface DecodingStep {
    /**
     * This is a flag indicating that the element is deserializable.
     */
    sealed interface ValidatedDecodingStep : DecodingStep {
        val elementIndex: Int
        val schema: AvroSchema
    }

    /**
     * The element is present in the writer schema and the class descriptor.
     */
    data class DeserializeWriterField(
        override val elementIndex: Int,
        val writerFieldIndex: Int,
        override val schema: AvroSchema,
    ) : DecodingStep, ValidatedDecodingStep

    /**
     * The element is present in the class descriptor but not in the writer schema, so the default value is used.
     * Also:
     * - if the [com.github.avrokotlin.avro4k.AvroConfiguration.implicitNulls] is enabled, the default value is `null`.
     * - if the [com.github.avrokotlin.avro4k.AvroConfiguration.implicitEmptyCollections] is enabled, the default value is an empty array or map.
     */
    data class GetDefaultValue(
        override val elementIndex: Int,
        /**
         * The reader field's schema, or its branch matching [defaultValue] when it is a union.
         */
        override val schema: AvroSchema,
        /**
         * Decoded by [com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder] each time the element is read.
         */
        val defaultValue: JsonElement,
    ) : DecodingStep, ValidatedDecodingStep

    /**
     * The element is present in the writer schema but not in the class descriptor, so it is skipped.
     */
    data class SkipWriterField(
        val writerFieldIndex: Int,
        val schema: AvroSchema,
    ) : DecodingStep

    /**
     * The element is present in the class descriptor but not in the writer schema.
     * Also, the element don't have a default value but is optional, so we can skip the element.
     */
    data class IgnoreOptionalElement(
        val elementIndex: Int,
    ) : DecodingStep

    /**
     * The element is present in the class descriptor but not in the writer schema.
     * It does not have any default value and is not optional, so it fails as it cannot get any value for it.
     */
    data class MissingElementValueFailure(
        val elementIndex: Int,
    ) : DecodingStep
}