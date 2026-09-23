package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Copy-on-write hash table of weakly referenced keys, compared by identity, shared by the JVM and native.
 *
 * **Reads** load the current [Table] and walk one bucket's chain: no lock, no allocation, no reference-queue poll.
 *
 * **Writes** compute the value outside of any lock, then publish a new [Table] with a compare-and-set, retrying on a
 * lost race (and returning the winner's value if it inserted the same key meanwhile). A put copies the bucket array,
 * so it is O(capacity): the right trade for caches that are written once per key and read on every value.
 *
 * **Visibility.** A [Table], its bucket array and its [Entry]s are fully built *before* the compare-and-set that
 * publishes them, and never mutated afterwards. Both platforms give the atomic a happens-before edge from the
 * successful compare-and-set to every [AtomicReference.load] that observes it — on the JVM through the volatile
 * semantics of `java.util.concurrent.atomic.AtomicReference` (JLS 17.4); on Kotlin/Native because the stdlib
 * `AtomicReference` stores a [kotlin.concurrent.Volatile] variable, whose contract is that a thread reading the value
 * "sees not only that value, but all side effects that led to writing that value". So a reader that sees a table sees
 * all of its contents. This argument does **not** rely on the JVM's final-field guarantee, which Kotlin/Native does not have.
 *
 * **Reclamation.** Keys are only weakly referenced, so they stay collectable. An entry whose key was collected keeps
 * its slot (and its *value*, strongly) until the table next fills up: the rebuild that would grow it first drops the
 * stale entries, and only grows if the live ones still need the room. Retention is therefore bounded by the table's
 * capacity, without any sweep on the read path. As with any weak-keyed map, a value that strongly references its own
 * key keeps that key alive forever.
 */
@InternalAvro4kApi
@OptIn(ExperimentalAtomicApi::class)
public actual class WeakIdentityKeyCache<K : Any, V : Any> actual constructor() : Cache<K, V> {
    private val table = AtomicReference(Table.empty<K, V>())

    actual override fun getOrPut(key: K, compute: () -> V): V {
        val hash = spread(identityHashCode(key))
        table.load().find(key, hash)?.let { return it }

        val value = compute()
        while (true) {
            val current = table.load()
            current.find(key, hash)?.let { return it }
            if (table.compareAndSet(current, current.with(key, hash, value))) return value
        }
    }

    /** Number of entries, including those whose key was collected but not swept yet. For tests. */
    internal val size: Int get() = table.load().size

    private class Entry<K : Any, V : Any>(
        val hash: Int,
        val key: WeakRef<K>,
        val value: V,
        val next: Entry<K, V>?,
    )

    private class Table<K : Any, V : Any>(
        /** Power-of-two sized; never mutated once the table is published. */
        private val buckets: Array<Entry<K, V>?>,
        /** Entries in [buckets], stale ones included. */
        val size: Int,
    ) {
        fun find(key: K, hash: Int): V? {
            var entry = buckets[hash and (buckets.size - 1)]
            while (entry != null) {
                if (entry.hash == hash && entry.key.get() === key) return entry.value
                entry = entry.next
            }
            return null
        }

        /** A new table holding this one's entries plus the given one. `this` is left untouched. */
        fun with(key: K, hash: Int, value: V): Table<K, V> {
            if (size + 1 <= threshold(buckets.size)) {
                val newBuckets = buckets.copyOf()
                newBuckets.prepend(hash, WeakRef(key), value)
                return Table(newBuckets, size + 1)
            }

            // Full: drop the entries whose key was collected, then grow only if the live ones still need the room.
            var liveCount = 0
            forEachLiveEntry { liveCount++ }
            var capacity = buckets.size
            while (liveCount + 1 > threshold(capacity)) capacity *= 2

            val newBuckets = newBuckets<K, V>(capacity)
            forEachLiveEntry { newBuckets.prepend(it.hash, it.key, it.value) }
            newBuckets.prepend(hash, WeakRef(key), value)
            return Table(newBuckets, liveCount + 1)
        }

        private inline fun forEachLiveEntry(action: (Entry<K, V>) -> Unit) {
            for (head in buckets) {
                var entry = head
                while (entry != null) {
                    if (entry.key.get() != null) action(entry)
                    entry = entry.next
                }
            }
        }

        companion object {
            private const val INITIAL_CAPACITY = 16

            fun <K : Any, V : Any> empty(): Table<K, V> = Table(newBuckets(INITIAL_CAPACITY), 0)

            private fun threshold(capacity: Int): Int = capacity / 4 * 3

            @Suppress("UNCHECKED_CAST")
            private fun <K : Any, V : Any> newBuckets(capacity: Int): Array<Entry<K, V>?> = arrayOfNulls<Entry<*, *>>(capacity) as Array<Entry<K, V>?>

            private fun <K : Any, V : Any> Array<Entry<K, V>?>.prepend(hash: Int, key: WeakRef<K>, value: V) {
                val index = hash and (size - 1)
                this[index] = Entry(hash, key, value, this[index])
            }
        }
    }
}

/** Mixes the high bits into the low ones, which are the only ones a power-of-two table uses to pick a bucket. */
private fun spread(hash: Int): Int = hash xor (hash ushr 16)

/** A weak reference: [get] returns null once the referent has been garbage collected. */
internal expect class WeakRef<T : Any>(referent: T) {
    fun get(): T?
}

/** The identity hash code of [value], ignoring any `hashCode` override. */
internal expect fun identityHashCode(value: Any): Int