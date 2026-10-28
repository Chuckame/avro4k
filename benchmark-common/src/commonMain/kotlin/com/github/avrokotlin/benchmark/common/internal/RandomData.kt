package com.github.avrokotlin.benchmark.common.internal

import kotlin.random.Random

/**
 * The benchmark data generator. A seeded [Random] is the same pure Kotlin algorithm on every platform, so each platform
 * encodes the same values to the same bytes, and a row measures the same work everywhere.
 *
 * Only integer draws, and doubles built from them, are used: a [Float] computed on JS is a double rounded late, which
 * could change a value from one platform to another.
 */
internal class RandomData(seed: Long = SEED) {
    private val random = Random(seed)

    fun alphabetic(count: Int): String = fromAlphabet(count, LETTERS)

    fun alphanumeric(count: Int): String = fromAlphabet(count, LETTERS_AND_DIGITS)

    private fun fromAlphabet(
        count: Int,
        alphabet: String,
    ): String = buildString(count) { repeat(count) { append(alphabet[random.nextInt(alphabet.length)]) } }

    fun bytes(count: Int): ByteArray = random.nextBytes(count)

    fun nextBoolean(): Boolean = random.nextBoolean()

    fun nextInt(): Int = random.nextInt()

    fun nextInt(until: Int): Int = random.nextInt(until)

    fun nextInt(
        from: Int,
        until: Int,
    ): Int = random.nextInt(from, until)

    fun nextLong(): Long = random.nextLong()

    fun nextDouble(): Double = random.nextDouble()

    fun nextDouble(
        from: Double,
        until: Double,
    ): Double = random.nextDouble(from, until)

    /** In `[0, 1)`, a multiple of 2^-24: exact as a [Float] on every platform. */
    fun nextFloat(): Float = random.nextFloat()

    /** In `[0, until)`, a multiple of 1/1024: exact as a [Float] on every platform for any `until` below 16384. */
    fun nextFloat(until: Int): Float = random.nextInt(until * 1024) / 1024f

    private companion object {
        // The seed of `benchmark/`'s generator
        const val SEED = 139793881379292435L
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val LETTERS_AND_DIGITS = LETTERS + "0123456789"
    }
}
