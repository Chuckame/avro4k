package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.apacheToAvro4k
import com.github.avrokotlin.avro4k.internal.avro4kToApache
import org.apache.avro.Schema

// The conversion itself lives in core until M3-14 (docs/plans/notes/m3-05.md), so that core can convert at its own
// public boundaries meanwhile.

/**
 * Converts this Apache Avro schema to avro4k's multiplatform [AvroSchema].
 *
 * The result is cached per schema instance (by identity), so only the first conversion of a given instance walks it.
 * Each node of the result converts back to the node of this schema it comes from: `toAvro4k().toApacheSchema()` returns
 * this very instance, and so does every sub-schema. The cached results are shared: they must not be mutated
 * (`addProp`…).
 *
 * Field default values are taken from their json, except that a number is converted to its schema's type, like Apache Avro's
 * [Schema.Field.defaultVal] does: an integer literal like `36` as the default of a `float` field becomes `36.0`.
 * The field's sort order, when not the default ascending one, is kept as the field's `order` prop, like in the schema's json.
 */
public fun Schema.toAvro4k(): AvroSchema = apacheToAvro4k(this)

/**
 * Converts this [AvroSchema] to an Apache Avro schema, through its json representation.
 *
 * The result is cached per schema instance (by identity), so only the first conversion of a given instance walks the
 * schema and parses json. Each node of the result converts back to the node of this schema it comes from:
 * `toApacheSchema().toAvro4k()` returns this very instance, and so does every sub-schema. The cached results are shared:
 * they must not be mutated (`addProp`…).
 */
public fun AvroSchema.toApacheSchema(): Schema = avro4kToApache(this)