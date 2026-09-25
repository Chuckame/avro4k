package com.github.avrokotlin.avro4k.internal

import kotlin.reflect.KClass

/**
 * The name of a class in an error message: its qualified name where the platform keeps one at runtime, its simple name
 * otherwise.
 */
internal expect val KClass<*>.nameForMessages: String