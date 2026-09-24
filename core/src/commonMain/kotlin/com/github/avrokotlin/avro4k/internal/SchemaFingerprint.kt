package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.internal.schema.getName

/*
 * The Parsing Canonical Form of a schema and its CRC-64-AVRO fingerprint, in pure Kotlin over the common
 * `AvroSchema` model: the replacement of Apache's `SchemaNormalization.toParsingForm`, `parsingFingerprint64` and
 * `parsingFingerprint("CRC-64-AVRO", …)`.
 *
 * Spec: https://avro.apache.org/docs/1.12.1/specification/#parsing-canonical-form-for-schemas and
 * https://avro.apache.org/docs/1.12.1/specification/#schema-fingerprints. Where Apache's output and the spec text could
 * differ, Apache's output wins: single-object encoding and schema registries need byte compatibility with the JVM
 * ecosystem. The rules and the (non-)deviations are listed in docs/plans/notes/m3-10.md.
 *
 * Cold code: a fingerprint walks the whole schema. Callers memoize per schema instance (`AvroSingleObject` will, once it
 * uses this fingerprint instead of Apache's).
 */

/**
 * The Parsing Canonical Form of this schema, exactly as Apache's `SchemaNormalization.toParsingForm` writes it:
 * - PRIMITIVES: a primitive, logical types or props included, is its bare type name (`"string"`);
 * - FULLNAMES: named types are written with their full name, the namespace folded into it (the model's names are
 *   already resolved, so `Name.fullName` is the folded name);
 * - STRIP / ORDER: only `name`, `type`, `fields`, `symbols`, `items`, `values`, `size` are kept, in that order (`name`
 *   before `type` for a named type and a field); doc, aliases, defaults, `order`, logical types and props are dropped;
 * - STRINGS: names and symbols are written as-is, without JSON escaping, like Apache (they cannot contain characters
 *   needing an escape in a valid schema);
 * - INTEGERS: a fixed's size is written as a plain decimal integer;
 * - WHITESPACE: none;
 * - a named type is written in full on its first occurrence only, afterwards (recursive references included) as its
 *   quoted full name. "Already written" is keyed by the **full name**, as in Apache, not by instance: two distinct but
 *   equal record instances with the same name must produce the same form as one shared instance.
 */
internal fun AvroSchema.toParsingCanonicalForm(): String {
    val out = StringBuilder()
    appendParsingCanonicalForm(this, HashSet(), out)
    return out.toString()
}

/** The CRC-64-AVRO fingerprint of this schema's Parsing Canonical Form: Apache's `SchemaNormalization.parsingFingerprint64`. */
internal fun AvroSchema.parsingFingerprint64(): Long = crc64Avro(toParsingCanonicalForm())

/**
 * The 8-byte little-endian encoding of [parsingFingerprint64], as single-object encoding writes it after its `C3 01`
 * marker: a drop-in for Apache's `SchemaNormalization.parsingFingerprint("CRC-64-AVRO", schema)`. Allocates the array
 * on each call; a caller writing to a `kotlinx.io.Sink` can also memoize the `Long` and use `writeLongLe`.
 */
internal fun AvroSchema.parsingFingerprintCrc64AvroBytes(): ByteArray = parsingFingerprint64().toLittleEndianBytes()

/** The 8 bytes of this `Long`, least significant first: Apache's `SchemaNormalization.fingerprint("CRC-64-AVRO", …)` layout. */
internal fun Long.toLittleEndianBytes(): ByteArray {
    val bytes = ByteArray(8)
    var value = this
    for (i in 0 until 8) {
        bytes[i] = value.toByte()
        value = value ushr 8
    }
    return bytes
}

private fun appendParsingCanonicalForm(
    schema: AvroSchema,
    writtenNames: HashSet<String>,
    out: StringBuilder,
) {
    when (schema) {
        is UnionSchema -> {
            out.append('[')
            val branches = schema.types
            for (i in branches.indices) {
                if (i > 0) out.append(',')
                appendParsingCanonicalForm(branches[i], writtenNames, out)
            }
            out.append(']')
        }

        is ArraySchema -> {
            out.append("{\"type\":\"array\",\"items\":")
            appendParsingCanonicalForm(schema.elementSchema, writtenNames, out)
            out.append('}')
        }

        is MapSchema -> {
            out.append("{\"type\":\"map\",\"values\":")
            appendParsingCanonicalForm(schema.valueSchema, writtenNames, out)
            out.append('}')
        }

        is NamedSchema -> appendNamed(schema, writtenNames, out)

        // boolean, int, long, float, double, bytes, string, null
        else -> out.append('"').append(schema.type.getName()).append('"')
    }
}

private fun appendNamed(
    schema: NamedSchema,
    writtenNames: HashSet<String>,
    out: StringBuilder,
) {
    val fullName = schema.fullName
    // Registered before walking the fields, so that a recursive reference is written as the name
    if (!writtenNames.add(fullName)) {
        out.append('"').append(fullName).append('"')
        return
    }
    out.append("{\"name\":\"").append(fullName).append("\",\"type\":\"").append(schema.type.getName()).append('"')
    when (schema) {
        is RecordSchema -> {
            out.append(",\"fields\":[")
            val fields = schema.fields
            for (i in fields.indices) {
                if (i > 0) out.append(',')
                val field = fields[i]
                out.append("{\"name\":\"").append(field.name).append("\",\"type\":")
                appendParsingCanonicalForm(field.schema, writtenNames, out)
                out.append('}')
            }
            out.append(']')
        }

        is EnumSchema -> {
            out.append(",\"symbols\":[")
            val symbols = schema.symbols
            for (i in symbols.indices) {
                if (i > 0) out.append(',')
                out.append('"').append(symbols[i]).append('"')
            }
            out.append(']')
        }

        is FixedSchema -> out.append(",\"size\":").append(schema.size)
    }
    out.append('}')
}

// ---- CRC-64-AVRO ----

/** The spec's `EMPTY`: the fingerprint of an empty byte string, and the Rabin polynomial. */
internal const val CRC64_AVRO_EMPTY: Long = -0x3ea2dec55b28586bL // 0xc15d213aa4d7a795

/**
 * The 256-entry table of the spec's fingerprint algorithm, computed once.
 *
 * **Publication, on every platform.** It is a top-level `val`, fully filled by [buildCrc64AvroTable] before the one
 * write that stores it, and never written afterwards (it is private, and nothing here mutates it).
 * - JVM: it lives in the file's facade class and is assigned in its static initializer; class initialization runs
 *   under the JVM's initialization lock and happens-before any use of the class by any thread (JLS 12.4.2), so every
 *   reader sees the filled array.
 * - Kotlin/Native: a top-level property is initialized on first access of its file, under the runtime's
 *   per-file initialization guard (other threads wait for the initializer to finish), so no thread can observe the
 *   array before it is filled.
 * - JS: single-threaded.
 */
private val CRC64_AVRO_TABLE: LongArray = buildCrc64AvroTable()

private fun buildCrc64AvroTable(): LongArray {
    val table = LongArray(256)
    for (i in 0 until 256) {
        var fp = i.toLong()
        for (j in 0 until 8) {
            fp = (fp ushr 1) xor (CRC64_AVRO_EMPTY and -(fp and 1L))
        }
        table[i] = fp
    }
    return table
}

/** The CRC-64-AVRO fingerprint of [bytes]: Apache's `SchemaNormalization.fingerprint64`. */
internal fun crc64Avro(bytes: ByteArray): Long {
    val table = CRC64_AVRO_TABLE
    var fp = CRC64_AVRO_EMPTY
    for (b in bytes) {
        fp = (fp ushr 8) xor table[(fp xor b.toLong()).toInt() and 0xff]
    }
    return fp
}

/**
 * The CRC-64-AVRO fingerprint of the UTF-8 bytes of [text], computed while encoding: no byte array is allocated.
 *
 * The encoding is the JVM's `String.getBytes(UTF_8)`, byte for byte, on every platform: an unpaired surrogate is
 * written as `?` (0x3F), where Kotlin's `encodeToByteArray()` writes `?` on the JVM but U+FFFD on JS and native.
 * A valid schema has no such character in its canonical form (names are ASCII), but the fingerprint of whatever the
 * model holds must not depend on the platform.
 */
internal fun crc64Avro(text: String): Long {
    val table = CRC64_AVRO_TABLE
    var fp = CRC64_AVRO_EMPTY
    var i = 0
    val length = text.length
    while (i < length) {
        val c = text[i++].code
        if (c < 0x80) {
            fp = (fp ushr 8) xor table[(fp.toInt() xor c) and 0xff]
        } else if (c < 0x800) {
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0xc0 or (c shr 6))) and 0xff]
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0x80 or (c and 0x3f))) and 0xff]
        } else if (c < 0xd800 || c > 0xdfff) {
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0xe0 or (c shr 12))) and 0xff]
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0x80 or ((c shr 6) and 0x3f))) and 0xff]
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0x80 or (c and 0x3f))) and 0xff]
        } else if (c <= 0xdbff && i < length && text[i].code in 0xdc00..0xdfff) {
            val codePoint = 0x10000 + ((c - 0xd800) shl 10) + (text[i++].code - 0xdc00)
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0xf0 or (codePoint shr 18))) and 0xff]
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0x80 or ((codePoint shr 12) and 0x3f))) and 0xff]
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0x80 or ((codePoint shr 6) and 0x3f))) and 0xff]
            fp = (fp ushr 8) xor table[(fp.toInt() xor (0x80 or (codePoint and 0x3f))) and 0xff]
        } else {
            // unpaired surrogate: '?', like the JVM's UTF-8 encoder
            fp = (fp ushr 8) xor table[(fp.toInt() xor '?'.code) and 0xff]
        }
    }
    return fp
}