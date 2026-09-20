package com.github.avrokotlin.benchmark.internal.apache

import com.github.avrokotlin.benchmark.internal.ListWrapperDataClass as KotlinListWrapperDataClass
import com.github.avrokotlin.benchmark.internal.ListWrapperDatasClass as KotlinListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.StatsEntry as KotlinStatsEntry

/**
 * Apache Avro's copy of the `lists` model.
 *
 * This workload is the one that already round-tripped through `ReflectData` before A5, because the
 * `noarg` compiler plugin gives every `@Serializable` class a no-arg constructor and Avro's
 * `Unsafe`-backed field access happens to be able to write Kotlin's final backing fields. That is
 * an implementation detail of the JDK that could stop holding, so this workload gets the same
 * explicitly mutable model as the other two rather than relying on it.
 */
internal class ListWrapperDatasClass {
    var data: List<ListWrapperDataClass> = emptyList()
}

internal class ListWrapperDataClass {
    var index: Int = 0
    var entries: List<StatsEntry> = emptyList()
}

internal class StatsEntry {
    var elementId: Long = 0
    var values: List<Long> = emptyList()
}

internal fun KotlinListWrapperDatasClass.toApache(): ListWrapperDatasClass = ListWrapperDatasClass().also { target ->
    target.data = data.map { it.toApache() }
}

private fun KotlinListWrapperDataClass.toApache(): ListWrapperDataClass = ListWrapperDataClass().also { target ->
    target.index = index
    target.entries = entries.map { it.toApache() }
}

private fun KotlinStatsEntry.toApache(): StatsEntry = StatsEntry().also { target ->
    target.elementId = elementId
    target.values = values
}
