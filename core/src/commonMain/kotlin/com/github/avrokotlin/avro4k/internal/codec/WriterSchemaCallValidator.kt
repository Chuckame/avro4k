package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.AvroValidationException
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.getName

/**
 * Checks the codec calls made to write or read **one datum** against its writer schema, one call at a time: the state
 * machine shared by [ValidatingAvroBinaryEncoder] and [ValidatingAvroBinaryDecoder].
 *
 * The state is an explicit stack of what is still expected, the next expectation on top:
 * - a value of a given schema. A record needs no call of its own: when it reaches the top it is replaced by its fields,
 *   first field on top, above a marker that only remembers the record's place for error paths. A union expects its
 *   branch index, then is replaced by the chosen branch;
 * - the string key of a map entry;
 * - the items of an array or a map that has been started. On the encoder side, it counts the items announced by
 *   `writeArrayStart`/`writeMapStart`: each item starts with `startItem()`, and the end is only accepted once every
 *   announced item has started. On the decoder side, it counts the items left in the current block: each value call
 *   opens the next item, and the next block (`arrayNext`/`mapNext`, or `skipArray`/`skipMap` when the collection is
 *   skipped) is only accepted once the block is used up.
 *
 * Primitive kinds must match exactly: like Apache Avro's validating codecs, there is no promotion (an `int` is written
 * with `writeInt`, never `writeLong`). Fixed sizes, enum indexes and union indexes are checked against the schema.
 *
 * Stricter than Apache's validating codecs on purpose, because each of these would corrupt the binary data:
 * - one datum per instance: the datum must be complete at [checkDatumComplete], and a call after its end is rejected
 *   (Apache's grammar loops, so it accepts a second datum and never checks the end);
 * - collections count items, not only `startItem()` calls: an item without its `startItem()`, a `startItem()` without
 *   an item, and on the decoder side a block not read to its end, are rejected.
 *
 * A mismatch throws an [AvroValidationException] naming the call, what was expected instead, and where in the datum.
 * On the decoder side, a value read from the input that the writer schema rules out (an enum or union index out of
 * range, a negative item count) throws an [AvroMalformedDataException] instead, with the same message: the input is
 * corrupted, whatever the serializer does.
 *
 * Per-operation state, so not thread-safe: a new instance is created for each encoded or decoded value.
 */
internal class WriterSchemaCallValidator(
    writerSchema: AvroSchema,
    private val decoding: Boolean,
) {
    // The stack, as parallel arrays indexed by depth: allocated once per validated datum, grown on deep nesting.
    private var kinds = IntArray(INITIAL_DEPTH)
    private var schemas = arrayOfNulls<AvroSchema>(INITIAL_DEPTH)

    /** The field name of a value, or of the record or collection that holds the value at this depth, for error paths. */
    private var labels = arrayOfNulls<String>(INITIAL_DEPTH)

    /** For a collection: the items announced and not started yet (encoder), or left in the current block (decoder). */
    private var remaining = LongArray(INITIAL_DEPTH)

    /** For a collection: the items started so far, for error paths. */
    private var started = LongArray(INITIAL_DEPTH)
    private var size = 0

    init {
        push(VALUE, writerSchema, null)
    }

    // ---- values ----

    /** A primitive value of the given [type] (for a string, also a map key), with nothing else to check. */
    fun primitive(
        type: Type,
        call: String,
    ) {
        expectValueOf(type, call)
        pop()
    }

    /** A fixed value of [length] bytes. */
    fun fixed(
        length: Int,
        call: String,
    ) {
        val schema = expectValueOf(Type.FIXED, call) as FixedSchema
        if (length != schema.size) {
            throw mismatch(call, "a fixed '${schema.fullName}' of ${schema.size} bytes, but got $length bytes")
        }
        pop()
    }

    /** Checks that an enum is expected, before its index is written or read: see [enumIndex]. */
    fun beginEnum(call: String) {
        expectValueOf(Type.ENUM, call)
    }

    /** The enum [index] written or read after [beginEnum]. */
    fun enumIndex(
        index: Int,
        call: String,
    ) {
        val schema = schemas[size - 1] as EnumSchema
        if (index < 0 || index >= schema.symbols.size) {
            throw readValueMismatch(call, "an index of enum '${schema.fullName}' in [0, ${schema.symbols.size}), but got $index")
        }
        pop()
    }

    /** Checks that a union is expected, before its branch index is written or read: see [unionIndex]. */
    fun beginUnion(call: String) {
        expectValueOf(Type.UNION, call)
    }

    /** The branch [index] written or read after [beginUnion]: the chosen branch is expected next. */
    fun unionIndex(
        index: Int,
        call: String,
    ) {
        val top = size - 1
        val schema = schemas[top] as UnionSchema
        if (index < 0 || index >= schema.types.size) {
            throw readValueMismatch(call, "a branch index of ${schema.fullNameForMessages} in [0, ${schema.types.size}), but got $index")
        }
        schemas[top] = schema.types[index]
    }

    // ---- collections ----

    /** Checks that an array ([Type.ARRAY]) or a map ([Type.MAP]) is expected, before it is started. */
    fun beginCollection(
        type: Type,
        call: String,
    ) {
        expectValueOf(type, call)
    }

    /** Encoder: the collection begun by [beginCollection] is started with [count] items announced. */
    fun collectionAnnounced(
        count: Long,
        call: String,
    ) {
        if (count < 0) throw readValueMismatch(call, "a non-negative item count, but got $count")
        val top = size - 1
        kinds[top] = if (schemas[top]!!.type === Type.ARRAY) ARRAY_ITEMS else MAP_ITEMS
        remaining[top] = count
        started[top] = 0
    }

    /** Encoder: `startItem()`, the next announced item is expected (for a map: its key, then its value). */
    fun startItem(call: String) {
        normalize()
        val top = size - 1
        if (top < 0 || (kinds[top] != ARRAY_ITEMS && kinds[top] != MAP_ITEMS) || remaining[top] == 0L) throw mismatch(call)
        remaining[top]--
        started[top]++
        pushItem(top)
    }

    /** Encoder: the end of the array ([Type.ARRAY]) or map ([Type.MAP]) on top, once every announced item has started. */
    fun collectionEnd(
        type: Type,
        call: String,
    ) {
        normalize()
        val top = size - 1
        val kind = if (type === Type.ARRAY) ARRAY_ITEMS else MAP_ITEMS
        if (top < 0 || kinds[top] != kind || remaining[top] != 0L) throw mismatch(call)
        pop()
    }

    /**
     * Decoder: the first block of the collection begun by [beginCollection] has [count] items, read or [skipped]. An
     * empty first block is the whole collection.
     */
    fun firstBlock(
        count: Long,
        skipped: Boolean,
        call: String,
    ) {
        if (count < 0) throw readValueMismatch(call, "a non-negative item count, but got $count")
        val top = size - 1
        if (count == 0L) {
            pop()
            return
        }
        val isArray = schemas[top]!!.type === Type.ARRAY
        kinds[top] =
            when {
                isArray && skipped -> SKIPPED_ARRAY_ITEMS
                isArray -> ARRAY_ITEMS
                skipped -> SKIPPED_MAP_ITEMS
                else -> MAP_ITEMS
            }
        started[top] = 0
        setBlock(top, count)
    }

    /**
     * Decoder: whether the next call must be the next block of the collection on top (`arrayNext`/`mapNext`, or
     * `skipArray`/`skipMap` if it is [skipped]), the current block being used up. Otherwise a `skipArray`/`skipMap` is
     * the start of a new collection.
     */
    fun awaitsNextBlock(
        type: Type,
        skipped: Boolean,
    ): Boolean {
        normalize()
        val top = size - 1
        return top >= 0 && kinds[top] == collectionKind(type, skipped) && remaining[top] == 0L
    }

    /** Decoder: checks that the next block of the collection on top is expected, before it is read: see [nextBlock]. */
    fun beginNextBlock(
        type: Type,
        skipped: Boolean,
        call: String,
    ) {
        if (!awaitsNextBlock(type, skipped)) throw mismatch(call)
    }

    /** Decoder: the next block has [count] items; none ends the collection. */
    fun nextBlock(
        count: Long,
        call: String,
    ) {
        if (count < 0) throw readValueMismatch(call, "a non-negative item count, but got $count")
        val top = size - 1
        if (count == 0L) pop() else setBlock(top, count)
    }

    // ---- end of datum ----

    /** Checks that the datum is complete: nothing more is expected. */
    fun checkDatumComplete() {
        normalize()
        if (size != 0) {
            val path = rawPath()
            throw AvroValidationException("Incomplete datum: expected ${describeTop()} at ${quoted(path)}, but no more calls were made", path)
        }
    }

    // ---- internals ----

    private fun setBlock(
        top: Int,
        count: Long,
    ) {
        // An item that takes no call at all (an array of records without fields) cannot be opened by a call: the block
        // is used up as soon as it is read.
        if (kinds[top] == ARRAY_ITEMS || kinds[top] == SKIPPED_ARRAY_ITEMS) {
            if ((schemas[top] as ArraySchema).elementSchema.takesNoCall()) {
                started[top] += count
                remaining[top] = 0
                return
            }
        }
        remaining[top] = count
    }

    /**
     * The value expected now, which must be of [type] (a map key for a string); for the decoder, opens the next item of
     * the current block first. Leaves the expectation on top: the caller pops or replaces it.
     */
    private fun expectValueOf(
        type: Type,
        call: String,
    ): AvroSchema? {
        normalize()
        var top = size - 1
        if (decoding && top >= 0 && remaining[top] > 0L && isCollection(kinds[top])) {
            remaining[top]--
            started[top]++
            pushItem(top)
            normalize()
            top = size - 1
        }
        if (top >= 0) {
            when (kinds[top]) {
                VALUE -> {
                    val schema = schemas[top]!!
                    if (schema.type === type) return schema
                }

                MAP_KEY -> if (type === Type.STRING) return null
            }
        }
        throw mismatch(call)
    }

    /** Replaces records on top by their fields and drops the markers of completed records, until a call is expected. */
    private fun normalize() {
        while (size > 0) {
            val top = size - 1
            when (kinds[top]) {
                RECORD_END -> pop()

                VALUE -> {
                    val schema = schemas[top]
                    if (schema !is RecordSchema) return
                    kinds[top] = RECORD_END
                    val fields = schema.fields
                    var i = fields.size - 1
                    while (i >= 0) {
                        val field = fields[i]
                        push(VALUE, field.schema, field.name)
                        i--
                    }
                }

                else -> return
            }
        }
    }

    private fun pushItem(collection: Int) {
        val schema = schemas[collection]
        if (schema is MapSchema) {
            push(VALUE, schema.valueSchema, null)
            push(MAP_KEY, null, null)
        } else {
            push(VALUE, (schema as ArraySchema).elementSchema, null)
        }
    }

    private fun push(
        kind: Int,
        schema: AvroSchema?,
        label: String?,
    ) {
        if (size == kinds.size) grow()
        kinds[size] = kind
        schemas[size] = schema
        labels[size] = label
        remaining[size] = 0
        started[size] = 0
        size++
    }

    private fun pop() {
        size--
        schemas[size] = null
        labels[size] = null
    }

    private fun grow() {
        val newSize = kinds.size * 2
        kinds = kinds.copyOf(newSize)
        schemas = schemas.copyOf(newSize)
        labels = labels.copyOf(newSize)
        remaining = remaining.copyOf(newSize)
        started = started.copyOf(newSize)
    }

    private fun mismatch(
        call: String,
        expected: String = describeTop(),
    ): AvroValidationException {
        val path = if (size == 0) "" else rawPath()
        return AvroValidationException(mismatchMessage(call, path, expected), path)
    }

    /**
     * A value written or read that the writer schema rules out: for the encoder, a serializer's mistake like any other
     * [mismatch]; for the decoder, a value read from the input, so the input is malformed.
     */
    private fun readValueMismatch(
        call: String,
        expected: String,
    ): AvroSerializationException {
        if (!decoding) return mismatch(call, expected)
        val path = if (size == 0) "" else rawPath()
        return AvroMalformedDataException(mismatchMessage(call, path, expected))
    }

    private fun mismatchMessage(
        call: String,
        path: String,
        expected: String,
    ): String {
        val where = if (size == 0) "after the end of the datum" else "at ${quoted(path)}"
        return "Invalid call $call $where for the writer schema: expected $expected"
    }

    private fun quoted(path: String) = if (path.isEmpty()) "the root" else "'$path'"

    /** What the top of the stack expects, for messages. */
    private fun describeTop(): String {
        val top = size - 1
        if (top < 0) return "no more calls, as the datum is complete"
        return when (kinds[top]) {
            VALUE -> describeValue(schemas[top]!!)

            MAP_KEY -> "a map key (string)"

            ARRAY_ITEMS, MAP_ITEMS -> {
                val isArray = kinds[top] == ARRAY_ITEMS
                if (decoding) {
                    if (remaining[top] > 0L) {
                        "the next item (${remaining[top]} more in this block)"
                    } else if (isArray) {
                        "arrayNext()"
                    } else {
                        "mapNext()"
                    }
                } else if (remaining[top] > 0L) {
                    "startItem() (${remaining[top]} more items announced)"
                } else if (isArray) {
                    "writeArrayEnd() (all announced items are started)"
                } else {
                    "writeMapEnd() (all announced items are started)"
                }
            }

            SKIPPED_ARRAY_ITEMS, SKIPPED_MAP_ITEMS -> {
                if (remaining[top] > 0L) {
                    "the next skipped item (${remaining[top]} more in this block)"
                } else if (kinds[top] == SKIPPED_ARRAY_ITEMS) {
                    "skipArray()"
                } else {
                    "skipMap()"
                }
            }

            else -> "nothing" // a record marker is never left on top by normalize()
        }
    }

    private fun describeValue(schema: AvroSchema): String =
        when (schema.type) {
            Type.UNION -> "a branch index of ${schema.fullNameForMessages}"
            Type.FIXED -> "a fixed '${schema.fullName}' of ${(schema as FixedSchema).size} bytes"
            Type.ENUM -> "an index of enum '${schema.fullName}'"
            Type.ARRAY -> "the start of an array"
            Type.MAP -> "the start of a map"
            else -> "a value of type ${schema.type.getName()}"
        }

    /**
     * Where the top of the stack is in the datum, like `inner.list[2].name`: the records and collections in progress,
     * from the root, then the field name of the expected value.
     */
    private fun rawPath(): String {
        val path = StringBuilder()
        val top = size - 1
        for (i in 0..top) {
            when (kinds[i]) {
                RECORD_END -> path.appendSegment(labels[i])

                VALUE, MAP_KEY -> if (i == top) path.appendSegment(labels[i])

                else -> {
                    path.appendSegment(labels[i])
                    if (i != top && started[i] > 0) path.append('[').append(started[i] - 1).append(']')
                }
            }
        }
        return path.toString()
    }

    private fun StringBuilder.appendSegment(label: String?) {
        if (label == null) return
        if (isNotEmpty()) append('.')
        append(label)
    }

    private fun isCollection(kind: Int) = kind >= ARRAY_ITEMS

    private fun collectionKind(
        type: Type,
        skipped: Boolean,
    ): Int =
        if (type === Type.ARRAY) {
            if (skipped) SKIPPED_ARRAY_ITEMS else ARRAY_ITEMS
        } else {
            if (skipped) SKIPPED_MAP_ITEMS else MAP_ITEMS
        }

    private companion object {
        const val INITIAL_DEPTH = 16

        // Stack entry kinds. The collection kinds must stay last: see isCollection.
        const val VALUE = 0
        const val MAP_KEY = 1
        const val RECORD_END = 2
        const val ARRAY_ITEMS = 3
        const val MAP_ITEMS = 4
        const val SKIPPED_ARRAY_ITEMS = 5
        const val SKIPPED_MAP_ITEMS = 6
    }
}

/**
 * Whether a value of this schema is written and read without any codec call: only a record whose fields all are, such
 * as a record without fields. The records being walked are compared by identity to stop on recursion (a record that
 * only contains itself through records has no finite value anyway). Cold: called once per decoded array block.
 */
private fun AvroSchema.takesNoCall(visiting: MutableList<RecordSchema>? = null): Boolean {
    if (this !is RecordSchema) return false
    val fields = fields
    if (fields.isEmpty()) return true
    val seen = visiting ?: ArrayList()
    for (i in seen.indices) {
        if (seen[i] === this) return false
    }
    seen.add(this)
    var result = true
    for (i in fields.indices) {
        if (!fields[i].schema.takesNoCall(seen)) {
            result = false
            break
        }
    }
    seen.removeAt(seen.size - 1)
    return result
}