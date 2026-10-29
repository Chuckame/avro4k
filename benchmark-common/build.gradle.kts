plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlinx.benchmark") version "0.5.0"
    kotlin("plugin.allopen")
    kotlin("plugin.serialization")
}

// The `simple`, `complex` and `lists` workloads of `benchmark/`, avro4k alone, on the JVM, JS (Node.js) and native.
// `benchmark/` compares avro4k with other libraries on the JVM; this module compares avro4k with itself on the platforms
// where no JIT and no escape analysis hide what a change costs. Not published, and not part of `actionsBeforeCommit`.

allOpen {
    // JMH subclasses the `@State` classes. On the JVM `kotlinx.benchmark.State` is an alias of JMH's annotation.
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.5.0")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

benchmark {
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "text"
        }
        // One short iteration of every row: checks that each target builds, sets up and runs. Its numbers mean nothing.
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "text"
        }
        // Reads and writes on their own: a change usually touches one side only. Not anchored at the start: on JS and
        // native the name matched is the suite's simple name followed by the qualified method name.
        register("read") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "text"
            include("""com\.github\.avrokotlin\.benchmark\.common\.\w+\.\w+\.read$""")
        }
        register("write") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "text"
            include("""com\.github\.avrokotlin\.benchmark\.common\.\w+\.\w+\.write$""")
        }
    }
    targets {
        register("jvm")
        register("js")
        // Built in release mode, the plugin's default for native targets
        register("macosArm64")
    }
}

repositories {
    mavenCentral()
}

// Every task that runs benchmarks, on any target, refuses to on battery or in Low Power Mode, as in `benchmark/`. The
// tasks that only build them (`jvmBenchmarkJar`, `link…`, `compile…`, `…Generate`) do not end with `Benchmark`.
tasks.matching { it.name.endsWith("Benchmark") }.configureEach { dependsOn(":benchmark:checkBenchmarkPower") }
