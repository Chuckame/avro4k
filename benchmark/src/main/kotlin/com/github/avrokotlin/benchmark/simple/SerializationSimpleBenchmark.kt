package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import kotlinx.benchmark.*
import java.util.concurrent.TimeUnit


@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
internal abstract class SerializationSimpleBenchmark {
   lateinit var clients: SimpleDatasClass
   val schema = Avro.default.schema(SimpleDatasClass.serializer())

   @Setup
   fun initTestData() {
      setup()
      clients = SimpleDatasClass.create(25)
      prepareBinaryData()
      println(schema.toString(true))
   }

   abstract fun setup()

   abstract fun prepareBinaryData()
}