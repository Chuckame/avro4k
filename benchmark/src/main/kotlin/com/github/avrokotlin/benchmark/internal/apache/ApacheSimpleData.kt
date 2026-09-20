package com.github.avrokotlin.benchmark.internal.apache

import com.github.avrokotlin.benchmark.internal.SimpleDataClass as KotlinSimpleDataClass
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass as KotlinSimpleDatasClass
import org.apache.avro.reflect.Nullable

/**
 * Apache Avro's copy of the `simple` model. Field for field identical to
 * [KotlinSimpleDataClass], including the `Byte` and `Short` that Avro has no type for: both
 * libraries encode them as `int`, and `ReflectData`'s own schema carries the
 * `java-class: java.lang.Byte` hint that lets it narrow the decoded `Integer` back to a `Byte`.
 *
 * The only difference is mutability: `ReflectData` builds a record with its no-arg constructor and
 * populates it by reflective field writes.
 */
internal class SimpleDatasClass {
    var data: List<SimpleDataClass> = emptyList()
}

internal class SimpleDataClass {
    var bool: Boolean = false
    var byte: Byte = 0
    var short: Short = 0

    @field:Nullable
    var int: Int? = null
    var long: Long = 0
    var float: Float = 0f
    var double: Double = 0.0

    @field:Nullable
    var string: String? = null
    var bytes: ByteArray = ByteArray(0)
}

internal fun KotlinSimpleDatasClass.toApache(): SimpleDatasClass = SimpleDatasClass().also { target ->
    target.data = data.map { it.toApache() }
}

private fun KotlinSimpleDataClass.toApache(): SimpleDataClass = SimpleDataClass().also { target ->
    target.bool = bool
    target.byte = byte
    target.short = short
    target.int = int
    target.long = long
    target.float = float
    target.double = double
    target.string = string
    target.bytes = bytes
}
