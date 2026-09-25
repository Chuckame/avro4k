package com.github.avrokotlin.avro4k.internal

import kotlin.reflect.KClass

/**
 * The name of a class in an error message: its qualified name, or `null` for a class that has none (an anonymous or
 * local class), as a string template prints a null qualified name.
 */
internal actual val KClass<*>.nameForMessages: String
    get() = qualifiedName.toString()