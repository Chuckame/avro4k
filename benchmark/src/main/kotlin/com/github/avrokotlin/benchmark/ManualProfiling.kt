package com.github.avrokotlin.benchmark

import com.github.avrokotlin.benchmark.simple.Avro4kGenericWithApacheAvroSimpleBenchmark

internal object ManualProfilingWrite {
   @JvmStatic
   fun main(vararg args: String) {
      Avro4kGenericWithApacheAvroSimpleBenchmark().apply {
         initTestData()
         for (i in 0 until 1_000_000) {
            if (i % 1_000 == 0) println("Iteration $i")
            write()
            read()
         }
      }
   }
}