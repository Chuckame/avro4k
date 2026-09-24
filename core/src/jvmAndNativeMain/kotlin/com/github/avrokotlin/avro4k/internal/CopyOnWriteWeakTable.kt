package com.github.avrokotlin.avro4k.internal

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Copy-on-write hash table of weakly referenced keys, shared by the JVM and native. It backs both [WeakIdentityKeyCache]
 * (`identity = true`: keys matched by `===` and hashed by [identityHashCode]) and [WeakKeyCache] (`identity = false`:
 * keys matched by `==` and hashed by `hashCode()`). The mode is a constructor constant; everything else is the same code.
 *
 * **Reads** load the current [Table] and walk one bucket's chain: no lock, no allocation, no reference-queue poll. This
 * holds in equality mode too: the key and its hash are passed to [Table.find] as they are, and compared against each
 * entry's referent in place, so there is no lookup wrapper to allocate (unlike a `ConcurrentHashMap` of weak keys,
 * whose `get` needs a key object comparable with the stored `WeakReference`s).
 *
 * **Writes** publish a new [Table] with a compare-and-set, retrying on a lost race (and returning the winner's value if
 * it inserted the same key meanwhile). A put copies the bucket array, so it is O(capacity): the right trade for caches
 * that are written once per key and read on every value.
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
 * key keeps that key alive forever. A stale entry never matches a lookup, in either mode: its referent is `null`.
 *
 * **Only for a bounded set of long-lived keys.** Because a put is O(capacity), a cache fed an unbounded stream of new
 * keys degrades to quadratic inserts. In identity mode, schemas and generated serial descriptors are fine; a key
 * created per call is not (e.g. `serializer<List<Foo>>().descriptor` is a fresh instance on every call — see
 * `docs/plans/notes/b6.md`). In equality mode the bound is on *distinct-by-equality* live keys: a fresh instance equal
 * to a stored one hits instead of inserting.
 *
 * ## Why a hand-written table, and why one for both platforms
 *
 * - **Nothing to reuse under the project's dependency policy** (official Kotlin/JetBrains libraries or pure Kotlin
 *   only). Neither the stdlib nor any kotlinx library has a common concurrent map (tracked by
 *   [KT-78661](https://youtrack.jetbrains.com/issue/KT-78661)), let alone a weak or identity-keyed one; the common
 *   stdlib only offers the atomics used here. Third-party multiplatform maps are excluded by that policy, and the ones
 *   that exist are equality-keyed without weak keys anyway.
 * - **Not `ConcurrentHashMap` on the JVM.** Weak keys need a `WeakReference` wrapper as the map key, so every lookup —
 *   identity or equality — needs a wrapper object per call to compare against it (the pre-M3 `WeakKeyCache` and the
 *   `kmp`-branch design): an allocation on every cache *hit*, which the M1 measurements showed is real once escape
 *   analysis is not there to remove it — and native and JS have no escape analysis. Its `ReferenceQueue` would also
 *   need polling. A JVM-only `ConcurrentHashMap` actual would additionally make the two platforms behave differently
 *   (compute-once vs may-recompute, eager vs deferred sweeping) under the same tests.
 * - **One implementation for JVM and native** (the `jvmAndNative` source set), and one for both key modes: the
 *   concurrency argument above is reviewed once. Only [WeakRef] and [identityHashCode] are per platform.
 * - **Deliberately far simpler than a general concurrent hash table.** A published table is never mutated: no in-place
 *   insertion, no cooperative resizing, no tree bins, no locks. All the concurrency is the single compare-and-set in
 *   [putIfAbsent]. The costs of that simplicity are the O(capacity) put, a possible duplicate computation on a lost race
 *   (allowed by the [Cache] contract) and the deferred reclamation above — all acceptable for write-once, read-mostly
 *   caches, and the reason for the restriction above.
 * - **Revisit** if KT-78661 (or a successor) brings a common concurrent map that can do identity and weak keys.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CopyOnWriteWeakTable<K : Any, V : Any>(
    private val identity: Boolean,
) {
    private val table = AtomicReference(Table.empty<K, V>())

    fun getOrNull(key: K): V? = table.load().find(key, hashOf(key), identity)

    /**
     * Stores [value] for [key] unless a matching key is already present. Returns null if it was inserted (then [key] is
     * the instance the table holds), the existing value otherwise — e.g. the winner's, after a lost race.
     */
    fun putIfAbsent(
        key: K,
        value: V,
    ): V? {
        val hash = hashOf(key)
        while (true) {
            val current = table.load()
            current.find(key, hash, identity)?.let { return it }
            if (table.compareAndSet(current, current.with(key, hash, value))) return null
        }
    }

    /** Never holds a lock while running [compute], so [compute] may call back into the same table. */
    inline fun getOrPut(
        key: K,
        compute: () -> V,
    ): V {
        getOrNull(key)?.let { return it }
        val value = compute()
        return putIfAbsent(key, value) ?: value
    }

    /** Number of entries, including those whose key was collected but not swept yet. For tests. */
    val size: Int get() = table.load().size

    private fun hashOf(key: K): Int = spread(if (identity) identityHashCode(key) else key.hashCode())

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
        fun find(
            key: K,
            hash: Int,
            identity: Boolean,
        ): V? {
            var entry = buckets[hash and (buckets.size - 1)]
            while (entry != null) {
                if (entry.hash == hash) {
                    val stored = entry.key.get()
                    // `stored` is null once collected: never `===` a live key, and skipped before calling `equals`.
                    if (stored === key || (!identity && stored != null && key == stored)) return entry.value
                }
                entry = entry.next
            }
            return null
        }

        /**
         * A new table holding this one's entries plus the given one. `this` is left untouched. Only called after
         * [find] returned null on `this` for the same key, so no live matching entry is ever duplicated.
         */
        fun with(
            key: K,
            hash: Int,
            value: V,
        ): Table<K, V> {
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

            private fun <K : Any, V : Any> Array<Entry<K, V>?>.prepend(
                hash: Int,
                key: WeakRef<K>,
                value: V,
            ) {
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