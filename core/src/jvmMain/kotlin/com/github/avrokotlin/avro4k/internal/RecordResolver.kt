package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.internal.encoder.ReorderingCompositeEncoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.apache.avro.Schema

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
     * The outer (writer schema) level is equality-keyed until M3-11 makes it identity-keyed. The inner (descriptor) level
     * is an [IdentityFirstCache]: record descriptors are mostly stable instances, but a generic record (`Box<T>`)
     * obtained through a fresh top-level serializer is an equal-but-distinct instance per call (M3-06).
     */
    private val fieldCache: Cache<Schema, IdentityFirstCache<SerialDescriptor, SerializationWorkflow>> = WeakKeyCache()

    /**
     * Inline (first-level) cache in front of [fieldCache], holding the last [INLINE_CACHE_CAPACITY] resolved
     * `(writerSchema, classDescriptor)` pairs, matched by **identity**.
     *
     * [resolveFields] is called once per record *instance*, so decoding a 100k-element array of records used to
     * perform 200k [fieldCache] lookups (two per record; before M3-06 each one was a `ReferenceQueue.poll()`, a
     * `Key.Lookup` allocation and a `ConcurrentHashMap` lookup) for a workflow that was already resolved for the first element.
     * A homogeneous collection or a nested record tree only ever cycles through a handful of pairs, so a tiny
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
        val writerSchema: Schema,
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
        writerSchema: Schema,
        classDescriptor: SerialDescriptor,
    ): SerializationWorkflow {
        val memos = inlineCache
        for (index in memos.indices) {
            val memo = memos[index]
            // Identity is enough: the same instances always resolve to the same workflow. It also avoids
            // Schema.equals(), which is a deep structural comparison.
            if (memo.writerSchema === writerSchema && memo.classDescriptor === classDescriptor) {
                return memo.workflow
            }
        }
        val workflow =
            fieldCache.getOrPut(writerSchema) {
                IdentityFirstCache()
            }.getOrPut(classDescriptor) {
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
        writerSchema: Schema,
    ): SerializationWorkflow {
        return SerializationWorkflow(
            computeDecodingSteps(classDescriptor, writerSchema),
            computeEncodingWorkflow(classDescriptor, writerSchema)
        )
    }

    private fun computeDecodingSteps(
        classDescriptor: SerialDescriptor,
        writerSchema: Schema,
    ): Array<DecodingStep> {
        val decodingSteps = mutableListOf<DecodingStep>()

        val writerFieldIndexToElementIndex = Array<Int?>(writerSchema.fields.size) { null }

        for (elementIndex in 0 until classDescriptor.elementsCount) {
            val writerField =
                writerSchema.findFieldMatchingWithElement(classDescriptor, elementIndex)
                    ?: continue

            if (writerFieldIndexToElementIndex[writerField.pos()] != null) {
                throw IllegalStateException(
                    "The descriptor $classDescriptor has multiple elements matching to the same writer field '$writerField'. " +
                        "This is not allowed as it would lead to ambiguous decoding."
                )
            }
            writerFieldIndexToElementIndex[writerField.pos()] = elementIndex
        }

        val visitedElements = BooleanArray(classDescriptor.elementsCount) { false }

        writerSchema.fields.forEachIndexed { writerFieldIndex, field ->
            val elementIndex = writerFieldIndexToElementIndex[writerFieldIndex]
            decodingSteps +=
                if (elementIndex != null) {
                    visitedElements[elementIndex] = true
                    DecodingStep.DeserializeWriterField(
                        elementIndex = elementIndex,
                        writerFieldIndex = writerFieldIndex,
                        schema = field.schema()
                    )
                } else {
                    DecodingStep.SkipWriterField(writerFieldIndex, field.schema())
                }
        }

        if (visitedElements.any { !it }) {
            // TODO we should not need to resolve the whole record's schema, but just extract the fields names, aliases and their order.
            //  However, the schema resolution also handle the union schema ordering based on the default value, which we also need here.
            val readerSchema = avro.schema(classDescriptor)

            // iterate over remaining elements in the class descriptor that are not in the writer schema
            // so it needs to set default values or skip them if they are optional
            visitedElements.forEachIndexed { elementIndex, visited ->
                if (visited) return@forEachIndexed

                val readerDefaultAnnotation = classDescriptor.findElementAnnotation<AvroDefault>(elementIndex)
                val readerField = readerSchema.fields[elementIndex]

                decodingSteps +=
                    if (readerDefaultAnnotation != null) {
                        val defaultValue = readerDefaultAnnotation.toFieldDefault(readerField.schema())
                        DecodingStep.GetDefaultValue(
                            elementIndex = elementIndex,
                            schema = readerField.schema().resolveDefaultBranch(defaultValue),
                            defaultValue = defaultValue
                        )
                    } else if (classDescriptor.isElementOptional(elementIndex)) {
                        // There is already a kotlin default value for this element, so we can skip it.
                        // We don't want to put an implicit null/empty collection here as it would override the kotlin default.
                        DecodingStep.IgnoreOptionalElement(elementIndex)
                    } else if (avro.configuration.implicitNulls && readerField.schema().isNullable) {
                        DecodingStep.GetDefaultValue(
                            elementIndex = elementIndex,
                            schema = readerField.schema().asSchemaList().first { it.type === Schema.Type.NULL },
                            defaultValue = JsonNull
                        )
                    } else if (avro.configuration.implicitEmptyCollections && readerField.schema().isTypeOf(Schema.Type.ARRAY)) {
                        DecodingStep.GetDefaultValue(
                            elementIndex = elementIndex,
                            schema = readerField.schema().asSchemaList().first { it.type === Schema.Type.ARRAY },
                            defaultValue = EMPTY_JSON_ARRAY
                        )
                    } else if (avro.configuration.implicitEmptyCollections && readerField.schema().isTypeOf(Schema.Type.MAP)) {
                        DecodingStep.GetDefaultValue(
                            elementIndex = elementIndex,
                            schema = readerField.schema().asSchemaList().first { it.type === Schema.Type.MAP },
                            defaultValue = EMPTY_JSON_OBJECT
                        )
                    } else {
                        DecodingStep.MissingElementValueFailure(elementIndex)
                    }
            }
        }
        return decodingSteps.toTypedArray()
    }

    private fun computeEncodingWorkflow(
        classDescriptor: SerialDescriptor,
        writerSchema: Schema,
    ): EncodingWorkflow {
        // Encoding steps are ordered regarding the class descriptor and not the writer schema.
        // Because kotlinx-serialization doesn't provide a way to encode non-sequentially elements.
        val missingWriterFieldsIndexes = mutableListOf<Int>()
        val visitedWriterFields = BooleanArray(writerSchema.fields.size) { false }
        val descriptorToWriterFieldIndex = IntArray(classDescriptor.elementsCount) { ReorderingCompositeEncoder.SKIP_ELEMENT_INDEX }

        var expectedNextWriterIndex = 0

        for (elementIndex in 0 until classDescriptor.elementsCount) {
            val writerField =
                writerSchema.findFieldMatchingWithElement(classDescriptor, elementIndex)
                    ?: continue

            if (visitedWriterFields[writerField.pos()]) {
                throw IllegalStateException(
                    "The descriptor $classDescriptor has multiple elements matching to the same writer field '$writerField'. " +
                        "This is not allowed as it would lead to ambiguous encoding."
                )
            }
            visitedWriterFields[writerField.pos()] = true
            descriptorToWriterFieldIndex[elementIndex] = writerField.pos()
            if (expectedNextWriterIndex != -1) {
                if (writerField.pos() != expectedNextWriterIndex) {
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

    private fun Schema.findFieldMatchingWithElement(classDescriptor: SerialDescriptor, elementIndex: Int): Schema.Field? =
        this.findFieldNamedOrAliasedAs(avro.configuration.fieldNamingStrategy.resolve(classDescriptor, elementIndex))
            ?: classDescriptor.getElementAliases(elementIndex).firstNotNullOfOrNull { this.findFieldNamedOrAliasedAs(it) }

    private fun Schema.isTypeOf(expectedType: Schema.Type): Boolean = asSchemaList().any { it.type === expectedType }
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
)

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
        val schema: Schema
    }

    /**
     * The element is present in the writer schema and the class descriptor.
     */
    data class DeserializeWriterField(
        override val elementIndex: Int,
        val writerFieldIndex: Int,
        override val schema: Schema,
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
        override val schema: Schema,
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
        val schema: Schema,
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

private fun Schema.findFieldNamedOrAliasedAs(name: String): Schema.Field? =
    getField(name) ?: fields.firstOrNull { name in it.aliases() }