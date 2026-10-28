package com.github.avrokotlin.benchmark

import com.github.avrokotlin.benchmark.complex.ApacheAvroSpecificBenchmark
import com.github.avrokotlin.benchmark.complex.Avro4kBenchmark

object ManualProfilingWrite {
    @JvmStatic
    fun main(vararg args: String) {
        Avro4kBenchmark().apply {
            initTestData()
            for (i in 0 until 1_000_000) {
                if (i % 1_000 == 0) println("Iteration $i")
                writeToStream()
            }
        }
    }
}

object ManualProfilingRead {
    @JvmStatic
    fun main(vararg args: String) {
        Avro4kBenchmark().apply {
            args.firstOrNull()?.let { clientCount = it.toInt() }
            initTestData()
            for (i in 0 until 1_000_000) {
                if (i % 1_000 == 0) println("Iteration $i")
                read()
            }
        }
    }
}

/** The same read loop as [ManualProfilingRead] on Apache's specific data model, so both can be profiled alike. */
object ManualProfilingReadApacheSpecific {
    @JvmStatic
    fun main(vararg args: String) {
        ApacheAvroSpecificBenchmark().apply {
            args.firstOrNull()?.let { clientCount = it.toInt() }
            initTestData()
            for (i in 0 until 1_000_000) {
                if (i % 1_000 == 0) println("Iteration $i")
                read()
            }
        }
    }
}