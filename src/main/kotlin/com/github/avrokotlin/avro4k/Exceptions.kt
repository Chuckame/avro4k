package com.github.avrokotlin.avro4k

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder


@ExperimentalSerializationApi
public class AvroSchemaGenerationException(message: String) : SerializationException(message)

internal fun Decoder.IllegalIndexedAccessError() = UnsupportedOperationException("${this::class.qualifiedName} does not support indexed access. Use sequential decoding instead.")
