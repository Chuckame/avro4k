package com.github.avrokotlin.avro4k.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

/**
 * The equality-keyed cache's contract, on every platform (JVM and native: the copy-on-write table in equality mode; JS: a
 * strong `HashMap`). Moved from `jvmTest` in M3-06; the JVM-only GC and concurrency checks are in `WeakKeyCacheJvmTest`.
 */
class WeakKeyCacheTest {
    private data class Key(val name: String)

    @Test
    fun `computes a value once per distinct key and returns it on every hit`() {
        val cache = WeakKeyCache<String, Any>()
        var computations = 0
        val compute = {
            computations++
            Any()
        }

        val a = cache.getOrPut("a", compute)
        val b = cache.getOrPut("b", compute)

        computations shouldBe 2
        repeat(1_000) {
            cache.getOrPut("a", compute) shouldBeSameInstanceAs a
            cache.getOrPut("b", compute) shouldBeSameInstanceAs b
        }
        computations shouldBe 2
    }

    @Test
    fun `keys are compared by equality rather than by identity`() {
        val cache = WeakKeyCache<Key, Any>()
        val first = Key("same")
        val second = Key("same")
        first shouldNotBeSameInstanceAs second

        val value = cache.getOrPut(first) { Any() }

        cache.getOrPut(second) { error("an equal key should hit") } shouldBeSameInstanceAs value
        cache.getOrNull(second) shouldBeSameInstanceAs value
        cache.size shouldBe 1
        cache.getOrNull(first) shouldBeSameInstanceAs value // keeps `first` reachable up to here
    }

    @Test
    fun `every key keeps its own value while the cache grows`() {
        val cache = WeakKeyCache<Key, String>()
        // Far beyond the initial capacity, so that the table is rebuilt several times.
        val keys = List(2_000) { Key("key-$it") }

        keys.forEach { cache.getOrPut(it) { it.name } }

        // Looked up with equal-but-distinct instances.
        keys.forEach { key -> cache.getOrPut(key.copy()) { error("should hit ${key.name}") } shouldBe key.name }
        cache.size shouldBe keys.size
    }

    @Test
    fun `the computation can itself use the cache`() {
        val cache = WeakKeyCache<Key, String>()
        // Held strongly: on JVM and native the cache only references its keys weakly.
        val outer = Key("outer")
        val inner = Key("inner")

        val value = cache.getOrPut(outer) { "outer+" + cache.getOrPut(inner) { "inner" } }

        value shouldBe "outer+inner"
        cache.getOrPut(inner) { error("should hit") } shouldBe "inner"
        cache.getOrPut(outer) { error("should hit") } shouldBe "outer+inner"
    }

    @Test
    fun `a failed computation stores nothing and is recomputed`() {
        val cache = WeakKeyCache<Key, String>()

        shouldThrow<IllegalStateException> { cache.getOrPut(Key("a")) { error("boom") } }

        cache.getOrNull(Key("a")).shouldBeNull()
        cache.getOrPut(Key("a")) { "computed" } shouldBe "computed"
    }

    @Test
    fun `putIfAbsent inserts only when no equal key is present`() {
        val cache = WeakKeyCache<Key, String>()
        val key = Key("a") // held strongly: the stored key is the first instance

        cache.putIfAbsent(key, "first").shouldBeNull()
        cache.putIfAbsent(Key("a"), "second") shouldBe "first"

        cache.getOrNull(Key("a")) shouldBe "first"
        cache.size shouldBe 1
        cache.getOrNull(key) shouldBe "first" // keeps `key` reachable up to here
    }
}