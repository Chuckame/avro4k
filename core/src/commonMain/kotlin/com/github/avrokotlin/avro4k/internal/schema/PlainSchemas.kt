package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema

/**
 * The primitive schemas without props that the encoders and decoders use on their own (a map key is always a string),
 * shared as schemas are immutable.
 *
 * Never put them in a schema tree given to users (parsed, generated or made nullable): the Apache interop pairs each
 * node of a tree with the node at the same position of its Apache conversion, which needs one instance per position.
 */
internal object PlainSchemas {
    val STRING = AvroSchema.StringSchema()
}