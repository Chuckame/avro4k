package com.github.avrokotlin.avro4k.internal

import kotlin.reflect.KClass

/** The simple name of the class: qualified names do not exist at runtime on JS. */
internal actual val KClass<*>.nameForMessages: String
    get() = simpleName.toString()