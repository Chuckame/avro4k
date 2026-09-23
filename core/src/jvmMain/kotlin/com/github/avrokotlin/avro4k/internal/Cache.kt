package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

@InternalAvro4kApi
public interface Cache<K : Any, V : Any> {
    /**
     * Returns the cached value, or computes, stores, and returns it.
     *
     * The value can be re-computed at any time.
     */
    public fun getOrPut(key: K, compute: () -> V): V
}

/**
 * Cache for associating derived data with objects you don't own
 * without preventing their garbage collection.
 *
 * **Reclamation is lazy, and bounded rather than eager.** Entries whose key has been collected are only
 * swept during the put path of [getOrPut] (see there for why). A cache that reaches a steady state and
 * stops putting therefore keeps its already-stale entries — the dead [Key.Stored] wrapper *and* its value,
 * which for a `SerializationWorkflow` is not tiny — until the next put, possibly forever. That retention is
 * bounded by the number of puts ever made on the instance (one entry per distinct key ever inserted), which
 * is why it is accepted here: the keys are schemas and serial descriptors, a small and effectively fixed set
 * per application. The *keys* themselves are still only weakly referenced, so they stay collectable.
 */
@InternalAvro4kApi
public class WeakKeyCache<K : Any, V : Any> : Cache<K, V> {
    private val queue = ReferenceQueue<K>()
    private val map = ConcurrentHashMap<Key<K>, V>()

    override fun getOrPut(key: K, compute: () -> V): V {
        // "get" phase. Stale entries are never matched here: a Stored key whose referent has been collected
        // compares unequal to every Lookup and to every other Stored key, so skipping the purge on a hit is
        // not observable.
        val hash = key.hashCode()
        map[Key.Lookup(hash, key)]?.let { return it }

        // "put" phase. The map can only ever grow here, so purging here keeps the growth bounded by the number
        // of distinct keys ever inserted, while keeping the (overwhelmingly more frequent) hit path free of the
        // synchronized ReferenceQueue.poll(). The trade-off is that entries that go stale after the last put are
        // retained until the next one — see the class KDoc.
        removeStaleEntries()
        return map.computeIfAbsent(Key.Stored(hash, key, queue)) { compute() }
    }

    private fun removeStaleEntries() {
        var ref = queue.poll() as Key.Stored<K>?
        while (ref != null) {
            map.remove(ref)
            ref = queue.poll() as Key.Stored<K>?
        }
    }

    private sealed interface Key<K : Any> {
        class Stored<K : Any>(val hash: Int, value: K, queue: ReferenceQueue<K>) : Key<K>, WeakReference<K>(value, queue) {
            override fun hashCode(): Int = hash

            override fun equals(other: Any?): Boolean {
                // The stale entries removed in removeStaleEntries() should be the same instance
                if (this === other) return true

                // If not removed, Stored keys are only compared against Stored keys during the "put" phase of getOrPut()
                other as Stored<*>

                val a = get()
                val b = other.get()
                // Stale (null) entries are being removed, so they should not match
                if (a == null || b == null) return false

                return a == b
            }
        }

        /**
         * Strong reference to the key to ensure the key isn't garbage collected during lookup and to
         * prevent a WeakReference to be created and referenced for potential queuing just for lookup.
         */
        class Lookup<K : Any>(val hash: Int, val value: K) : Key<K> {
            override fun hashCode(): Int = hash

            override fun equals(other: Any?): Boolean {
                // Lookup keys are only compared against Stored keys during the "get" phase of getOrPut()
                other as Stored<*>

                // Stale (null) entries are being removed, so they should not match
                val otherValue = other.get() ?: return false

                return value == otherValue
            }
        }
    }
}