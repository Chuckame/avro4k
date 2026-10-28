package com.github.avrokotlin.benchmark.common.internal

import kotlinx.serialization.Serializable

/** A copy of `benchmark/`'s `simple` model, which only uses common types. */
@Serializable
data class SimpleDataClass(
    val bool: Boolean,
    val byte: Byte,
    val short: Short,
    val int: Int?,
    val long: Long,
    val float: Float,
    val double: Double,
    val string: String?,
    val bytes: ByteArray,
) {
    companion object {
        internal fun create(random: RandomData) =
            SimpleDataClass(
                bool = random.nextBoolean(),
                byte = random.nextInt(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte(),
                short = random.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                int = random.nextInt(),
                long = random.nextLong(),
                float = random.nextFloat(),
                double = random.nextDouble(),
                string = random.alphanumeric(25),
                bytes = random.bytes(50),
            )
    }
}

@Serializable
data class SimpleDatasClass(
    val data: List<SimpleDataClass>,
) {
    companion object {
        internal fun create(
            size: Int,
            random: RandomData,
        ) = SimpleDatasClass(
            data = List(size) { SimpleDataClass.create(random) }
        )
    }
}
