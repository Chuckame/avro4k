buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // A7: `SpecificCompiler` has no schema CLI entry point in 1.12 (`SpecificCompiler.main` only
        // compiles protocols), and `avro-tools`, which does, is a ~70 MB shaded uber-jar. Calling the
        // compiler API directly from `generateSpecificRecords` is both smaller and one process less.
        classpath("org.apache.avro:avro-compiler:1.12.1")
    }
}

plugins {
    java
    kotlin("jvm")
    id("org.jetbrains.kotlinx.benchmark") version "0.4.17"
    kotlin("plugin.allopen")
    kotlin("plugin.serialization")
    kotlin("plugin.noarg")
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

noArg {
    annotation("kotlinx.serialization.Serializable")
}

benchmark {
    configurations {
        named("main") {
            reportFormat = "text"
        }
        register("simple-read") {
            include("^com.github.avrokotlin.benchmark.simple.+.read$")
        }
        register("simple-write") {
            include("^com.github.avrokotlin.benchmark.simple.+.write$")
        }
        register("complex-read") {
            include("^com.github.avrokotlin.benchmark.complex.+.read$")
        }
        register("complex-write") {
            include("^com.github.avrokotlin.benchmark.complex.+.write$")
        }
        register("avro4k") {
            include("Avro4k")
        }
        register("avro4k-read") {
            include("Avro4k.+read")
        }
        register("avro4k-write") {
            include("Avro4k.+write")
        }
        register("lists-read") {
            include("^com.github.avrokotlin.benchmark.lists.+.read$")
        }
        register("lists-write") {
            include("^com.github.avrokotlin.benchmark.lists.+.write$")
        }
        // A6: Apache's generic read path with its fast reader on and off, on its own. The fast
        // reader is the only Apache read configuration that has an on/off switch, and it exists for
        // `GenericData`/`SpecificData` only - never for `ReflectData` - so it lives in its own
        // benchmark classes and is runnable without paying for the whole end-to-end matrix.
        register("fastreader") {
            include("ApacheAvroGenericFastReader")
        }
        // A7: Apache's specific data model - generated `SpecificRecord` classes plus `SpecificData`,
        // the fastest configuration Apache offers - runnable on its own.
        register("specific") {
            include("ApacheAvroSpecific")
        }
        // The micro-benchmark suite: attribution instruments for a single avro4k internal each,
        // deliberately runnable on its own without paying for the ~25 min end-to-end matrix.
        register("micro") {
            include("^com.github.avrokotlin.benchmark.micro.+$")
        }
    }
    targets {
        register("main")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.17")

    val jacksonVersion = "2.21.3"
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-avro:$jacksonVersion")
    implementation("org.slf4j:slf4j-simple:2.0.18")

    implementation(project(":core"))
}

kotlin {
    compilerOptions {
        optIn = listOf(
            "com.github.avrokotlin.avro4k.ExperimentalAvro4kApi",
        )
    }
}

// ---------------------------------------------------------------------------
// A7: Apache `SpecificData` + generated `SpecificRecord` classes
//
// The fastest data model Apache offers, and the one performance-minded Apache users deploy. It
// needs generated classes, so the benchmark module generates them - from avro4k's *own* canonical
// schema, so the comparison stays like-for-like.
//
// The chain, and why it is this shape:
//
//   Avro.schema<T>()  --(:benchmark:regenerateAvroSchemas)-->  src/main/avro/*.avsc
//                     --(:benchmark:generateSpecificRecords)-->  build/generated/.../java
//
// The `.avsc` files are checked in so that a clean build needs no bootstrap step (generating them
// requires compiling the Kotlin model, which is in the same source set as the generated Java - a
// cycle if it were wired into the compile chain). They are **derived, never hand-edited**:
// `regenerateAvroSchemas` rewrites them from avro4k, and `CanonicalSchemas.kt` says so.
//
// Drift is not trusted to discipline. Every `apache-specific` benchmark trial compares the
// generated classes' `SCHEMA$` against `Avro.schema<T>()` through the A5 equivalence gate, so an
// `.avsc` that falls behind avro4k fails loudly in `@Setup` with the exact differing path.
//
// Rejected: a third-party Avro Gradle plugin (a whole plugin, and its own conventions, for one
// `SpecificCompiler` invocation), and checking in the generated Java (it would be indistinguishable
// from hand-written code in review, and nothing would stop someone editing it).
// ---------------------------------------------------------------------------
val avroSchemaDir = layout.projectDirectory.dir("src/main/avro")
val specificRecordDir = layout.buildDirectory.dir("generated/sources/avro/main/java")

val generateSpecificRecords = tasks.register("generateSpecificRecords") {
    group = "build"
    description = "Compiles the .avsc files under src/main/avro into Apache SpecificRecord classes."

    val schemaFiles = avroSchemaDir.asFile.listFiles { f: File -> f.name.endsWith(".avsc") }
        ?.sortedBy { it.name }
        .orEmpty()
    val outputDir = specificRecordDir
    inputs.files(schemaFiles).withPropertyName("schemas")
    outputs.dir(outputDir).withPropertyName("generatedSources")
    onlyIf { schemaFiles.isNotEmpty() }

    doLast {
        val target = outputDir.get().asFile
        // Stale generated sources are worse than none: a removed or renamed record would keep
        // compiling against the previous generation.
        target.deleteRecursively()
        target.mkdirs()
        schemaFiles.forEach { file ->
            val compiler = org.apache.avro.compiler.specific.SpecificCompiler(
                org.apache.avro.Schema.Parser().parse(file)
            )
            // `StringType.String` rather than the compiler's `CharSequence` default: this is the
            // setting that makes the decoded specific model comparable to avro4k's (which decodes
            // into `String`), and it *costs* Apache - `Utf8` is cheaper to produce than `String`.
            // See docs/plans/notes/a7.md. It adds an `avro.java.string` property, one of the four
            // object-mapping hints the A5 schema comparison deliberately ignores, and it does not
            // reach the wire.
            compiler.setStringType(org.apache.avro.generic.GenericData.StringType.String)
            compiler.compileToDestination(file, target)
        }
    }
}

sourceSets.named("main") {
    java.srcDir(specificRecordDir)
}

tasks.named("compileJava") { dependsOn(generateSpecificRecords) }
tasks.named("compileKotlin") { dependsOn(generateSpecificRecords) }

/**
 * Rewrites the checked-in `.avsc` files from `Avro.schema<T>()`. Run it after changing a benchmark
 * model; the equivalence gate fails every `apache-specific` row until you do.
 */
tasks.register<JavaExec>("regenerateAvroSchemas") {
    group = "build"
    description = "Rewrites benchmark/src/main/avro/*.avsc from avro4k's canonical schemas."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.github.avrokotlin.benchmark.internal.specific.CanonicalSchemas"
    args(avroSchemaDir.asFile.absolutePath)
    outputs.upToDateWhen { false }
}
repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Allocation profiling
//
// `gc.alloc.rate.norm` (bytes allocated per operation) is the primary acceptance
// metric for performance work on avro4k: it is portable, whereas throughput on the
// JVM is flattered by the JIT and by escape analysis that JS and native do not have.
// See README.md > "Allocation profiling".
// ---------------------------------------------------------------------------
// The kotlinx-benchmark plugin registers `mainBenchmarkJar` from its own `afterEvaluate`
// hook, so the task only exists once the project has been evaluated.
afterEvaluate {
    val jmhJar = tasks.named<org.gradle.jvm.tasks.Jar>("mainBenchmarkJar")

    tasks.register<JavaExec>("benchmarkAlloc") {
        group = "benchmark"
        description = "Runs a subset of the JMH benchmarks with the GC profiler (-prof gc) and reports gc.alloc.rate.norm (B/op)."

        // The uber-jar built by the kotlinx-benchmark plugin already bundles JMH, the
        // benchmark classes and all their dependencies, so it is the whole classpath.
        classpath(jmhJar.map { it.archiveFile })
        mainClass = "org.openjdk.jmh.Main"

        // Selection: which benchmarks, and which @Param combinations.
        val benchFilter = providers.gradleProperty("bench").orElse("Avro4k")
        val benchParams = providers.gradleProperty("params").orElse("")
        // Iteration settings: allocation per operation converges far faster than throughput,
        // so the defaults here are much shorter than the ones the benchmark classes declare.
        val forks = providers.gradleProperty("allocForks").orElse("1")
        val warmupIterations = providers.gradleProperty("allocWarmups").orElse("2")
        val measurementIterations = providers.gradleProperty("allocIterations").orElse("3")
        val iterationTime = providers.gradleProperty("allocTime").orElse("1s")
        val extraArgs = providers.gradleProperty("allocJmhArgs").orElse("")

        val reportFile = layout.buildDirectory.file("reports/benchmarks/alloc/main-alloc.txt")
        outputs.file(reportFile)
        // A benchmark is never up-to-date: the whole point is to measure again.
        outputs.upToDateWhen { false }

        argumentProviders.add(
            org.gradle.process.CommandLineArgumentProvider {
                buildList {
                    add("-prof")
                    add("gc")
                    add("-f")
                    add(forks.get())
                    add("-wi")
                    add(warmupIterations.get())
                    add("-w")
                    add(iterationTime.get())
                    add("-i")
                    add(measurementIterations.get())
                    add("-r")
                    add(iterationTime.get())
                    // `-Pparams=recordCount=1;otherParam=a,b` -> `-p recordCount=1 -p otherParam=a,b`
                    benchParams.get().split(';').filter { it.isNotBlank() }.forEach {
                        add("-p")
                        add(it.trim())
                    }
                    extraArgs.get().split(' ').filter { it.isNotBlank() }.forEach { add(it) }
                    // Trailing positional argument: the benchmark-name regex.
                    add(benchFilter.get())
                }
            }
        )

        doFirst {
            val target = reportFile.get().asFile
            target.parentFile.mkdirs()
            // Deliberately unbuffered: Gradle does not necessarily close this stream, so any
            // buffered tail would be missing from the file by the time `doLast` reads it.
            val fileStream = target.outputStream()
            // Tee, so the run is visible live *and* captured for later inspection.
            (this as JavaExec).standardOutput =
                object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        System.out.write(b)
                        fileStream.write(b)
                    }

                    override fun flush() {
                        System.out.flush()
                        fileStream.flush()
                    }

                    override fun close() {
                        fileStream.close()
                    }
                }
        }

        doLast {
            val target = reportFile.get().asFile
            val allocLines = target.readLines().filter { it.contains("gc.alloc.rate.norm") }
            logger.lifecycle("")
            logger.lifecycle("Allocation report: ${target.absolutePath}")
            if (allocLines.isEmpty()) {
                logger.warn("No gc.alloc.rate.norm line found - did the gc profiler actually run?")
            } else {
                allocLines.forEach { logger.lifecycle(it) }
            }
        }
    }
}
