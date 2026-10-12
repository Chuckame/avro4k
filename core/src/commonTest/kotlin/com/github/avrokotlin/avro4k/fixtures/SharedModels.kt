package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.AvroFixed
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

// Models used by several fixture families.

@Serializable
internal data class Point(
    val x: Int,
    val y: Int,
)

@Serializable
internal enum class Suit { CLUBS, DIAMONDS, HEARTS, SPADES }

@Serializable
internal sealed interface Animal {
    @Serializable
    data class Cat(val name: String) : Animal

    @Serializable
    data class Dog(val age: Int, val goodBoy: Boolean) : Animal

    @Serializable
    data object Fish : Animal
}

@JvmInline
@Serializable
internal value class Md5(
    @AvroFixed(16) val bytes: ByteArray,
)