package com.github.avrokotlin.benchmark.common.internal

import kotlinx.serialization.Serializable

/**
 * A copy of `benchmark/`'s `lists` model. One generator feeds every entry here, where `benchmark/` restarts its seed per
 * entry and so repeats the same values: the values differ, their encoded sizes do not.
 */
@Serializable
data class ListWrapperDataClass(val index: Int, val entries: List<StatsEntry>) {
    companion object {
        internal fun create(
            size: Int,
            entriesSize: Int,
            random: RandomData,
        ) = ListWrapperDataClass(random.nextInt(), List(size) { StatsEntry.create(entriesSize, random) })
    }
}

@Serializable
data class StatsEntry(val elementId: Long, val values: List<Long>) {
    companion object {
        internal fun create(
            entriesSize: Int,
            random: RandomData,
        ) = StatsEntry(random.nextLong(), List(entriesSize) { random.nextLong() })
    }
}

@Serializable
data class ListWrapperDatasClass(
    val data: List<ListWrapperDataClass>,
) {
    companion object {
        internal fun create(
            size: Int,
            wrapperSize: Int,
            entriesSize: Int,
            random: RandomData,
        ) = ListWrapperDatasClass(data = List(size) { ListWrapperDataClass.create(wrapperSize, entriesSize, random) })
    }
}
