package com.github.avrokotlin.avro4k.internal

/**
 * A fixed, identity-keyed lookup table: [get] scans the keys comparing them with `===`, never with `equals`.
 *
 * Meant for a handful of entries on a hot path, where a linear scan over an array beats hashing and never allocates.
 * Immutable once built, so it can be shared across threads.
 */
internal class IdentityLookup<K : Any, V : Any>(entries: List<Pair<K, V>>) {
    private val keys: Array<Any> = Array(entries.size) { entries[it].first }
    private val values: Array<Any> = Array(entries.size) { entries[it].second }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Any): V? {
        for (i in keys.indices) {
            if (keys[i] === key) return values[i] as V
        }
        return null
    }
}