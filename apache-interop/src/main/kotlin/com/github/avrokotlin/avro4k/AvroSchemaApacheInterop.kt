package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.apacheToAvro4k
import com.github.avrokotlin.avro4k.internal.avro4kToApache
import org.apache.avro.Schema

// The conversion itself lives in core until core drops its Apache dependency, as core still hands Apache's model to
// Apache's own classes meanwhile (object container files, single-object fingerprints, the Apache codec adapters).

/**
 * Converts this Apache Avro schema to avro4k's multiplatform [AvroSchema].
 *
 * The result is cached per schema instance (by identity), so only the first conversion of a given instance walks it.
 * Each node of the result converts back to the node of this schema it comes from: `toAvro4k().toApacheSchema()` returns
 * this very instance, and so does every sub-schema. The cached results are shared: they must not be mutated
 * (`addProp`…).
 *
 * A schema that was never converted but is **equal** to one already converted (Apache's `equals`, which ignores docs and
 * aliases) returns the conversion of that first, *canonical*, instance, without walking this one: so a schema parsed
 * again for every message costs a hash and an `equals` per call, not a conversion. For such a schema,
 * `toAvro4k().toApacheSchema()` returns the canonical Apache instance, not this one, and its aliases and docs are the
 * canonical instance's.
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
 *
 * A logical type that Apache does not know (avro4k's own `char`, a custom one) is kept as a `LogicalType` of that name,
 * as the Apache schemas avro4k used to generate had it, where Apache's parser alone would only keep the `logicalType` prop.
 */
public fun AvroSchema.toApacheSchema(): Schema = avro4kToApache(this)