# Backlog: generic map decoder writer schema, and `benchmarkAlloc` failing silently

**Outcome:** done (both items)
**Commits:** `8001e57` (map decoder), and the commit that adds this note (benchmarkAlloc)

## Item 1: `MapGenericDecoder.currentWriterSchema`

### What changed

`core/.../internal/decoder/generic/CollectionGenericDecoders.kt`, `MapGenericDecoder`:

- A `positionedOnKey` flag now drives `currentWriterSchema`. In key position it is `STRING_SCHEMA`; in
  value position it is the map's `valueType`.
- The flag is set **by element index** (`index % 2 == 0`) in `decodeSerializableElement` and
  `decodeInlineElement`, i.e. *before* the element is read. It is also set in `advance()` when the
  element is read, which covers the primitive `decode*Element` methods: `AbstractDecoder` marks those
  `final`, so they cannot be intercepted.
  Why both: a serializer can read `currentWriterSchema` before it decodes (a custom serializer choosing
  its strategy, `decodeResolving*`) or after it decodes (`beginStructure` calls `decodeValue()` and
  *then* reads `currentWriterSchema` for a nested map or a plain collection). The state after the
  value's `advance()` is the same as the state before the next key's, so the iterator position alone
  cannot give the right answer for both.
- **Union unwrapping (needed, not in the original brief):** the generic tree decoder does not resolve
  unions, so a nullable map (a nullable map value, a nullable record field) gets its `["null", map]`
  union as `writerSchema`. Returning `writerSchema.valueType` then throws
  `AvroRuntimeException: Not a map`. That is a **regression**: the old code never read `writerSchema`,
  so it never threw there. The new test "nullable nested maps" caught it. The fix picks the union's
  `MAP` branch once, at construction (`mapSchema`).
- Removed the `@Suppress("unused")` and the "preserved bug" comment.

### Tests (`core/src/test/.../encoding/GenericDataMapEncodingTest.kt`, 8 new, 13 in the class)

Round-trips through `decodeFromGenericData`: nested maps (3 levels), nullable nested maps, list values,
record values, sealed-interface (polymorphic) values, logical-typed values (`BigDecimal` in a value
class). Also: plain `java.util.ArrayList` values holding `BigDecimal`s (a plain list carries no schema,
so the array decoder takes it from the map's value position). And a `SchemaCapturingSerializer` that
records `(decoder as AvroDecoder).currentWriterSchema` before decoding. It asserts `string` for keys and
the map's value schema for values.

### Mutation verification

- **Getter reverted to `STRING_SCHEMA`** (the old behaviour): 3 tests fail.
  - "reports the key schema then the value schema": expected `[int, int]`, got `[string, string]`.
  - "logical-typed values": `Decoded value 'HeapByteBuffer' … for STRING kind`, so the BigDecimal
    serializer decoded bytes as a string.
  - "plain lists of logical-typed elements": `AvroRuntimeException: Not an array: "string"`.
- **Index hook removed from `decodeSerializableElement`** (only `advance()` sets the flag): 2 tests fail
  ("reports the key schema…" gets `[string, int]`, and "logical-typed values"). So the hook is load-bearing.
- The nested-map, list, record and polymorphic round-trips pass under the old behaviour too. Nested maps
  of ints and `GenericArray`s never consult the parent's writer schema, and polymorphic values that are
  records are `GenericContainer`s that carry their own schema. They are there as regression guards.

### Verification

`./gradlew :core:test`: 717 tests, 0 failures. `:core:spotlessCheck` passed. `./gradlew apiDump`
produced no diff.

## Item 2: `:benchmark:benchmarkAlloc` fails silently

### Root cause (not the build cache)

The README blamed the Gradle build cache. The real cause is the **configuration cache**. The
kotlinx-benchmark plugin (0.4.17, `JvmTasks.kt`) fills `mainBenchmarkJar` with:

```kotlin
from(project.provider { compileClasspath.map { when { it.isDirectory -> it; it.exists() -> zipTree(it); else -> files() } } })
```

With the configuration cache on, that provider is evaluated when the cache entry is stored, i.e.
before `compileKotlin` runs. On a fresh or cleaned `benchmark/build/`, `build/classes/kotlin/main` does
not exist yet, so it is dropped. The jar then contains JMH's generated stubs and all dependencies, but
**no benchmark class**. Every fork dies with `NoClassDefFoundError`/`ClassNotFoundException`.
Reproduced on this worktree:

- `rm -rf benchmark/build && ./gradlew :benchmark:mainBenchmarkJar` → jar has 0 entries for
  `micro/SchemaInferenceMicroBenchmark.class`.
- `./gradlew --no-configuration-cache :benchmark:mainBenchmarkJar` → 1 entry.

The very first `benchmarkAlloc` run in this fresh worktree hit this, even with `--no-build-cache`: 3/3
runs failed with `ClassNotFoundException`, and the task reported `BUILD SUCCESSFUL`. A reused cache
entry keeps producing the broken jar, so the problem is sticky rather than a one-off.

### What changed

`benchmark/build.gradle.kts`, task `benchmarkAlloc`:

1. **Classpath no longer uses the jar.** It now matches the plugin's own `benchmark` exec task:
   `mainBenchmarkCompile` output, `build/benchmarks/main/resources` (JMH's `BenchmarkList`), then
   `sourceSets["main"].runtimeClasspath`. This removes the root cause for this task.
2. **Fails loudly.** `doLast` parses the tee'd report:
   - It tracks each run (`# Benchmark:` plus `# Parameters:`). If any JMH failure marker appears
     (`<failure`, `<forked VM failed`, `<failed to invoke the VM`, `<binary link had failed`,
     `<host VM has been interrupted`, taken from the JMH 1.37 sources), it throws a `GradleException`
     that names the failed runs.
   - It throws if there is no `gc.alloc.rate.norm` **result-table row**. The regex is
     `^\S+:gc\.alloc\.rate\.norm\s`, because per-iteration lines also contain the string but are indented.
   - It throws if there are fewer rows than runs. This safety net covers failure modes whose marker is
     not in the list.
   - It echoes only the table rows now, not every line containing `gc.alloc.rate.norm`.

`benchmark/README.md`: the `[!WARNING]` is now a `[!NOTE]` saying the task fails on an empty or partial
run. The "Running it" paragraph no longer says the task uses the uber-jar. The async-profiler section
(which does still use the jar) now builds it with `--no-configuration-cache` and explains why. I dropped
the `--no-build-cache` advice: it does not prevent the problem, and `benchmarkAlloc` no longer depends
on the jar.

### Verification runs (one at a time, all `--no-build-cache`, `-PallocWarmups=1 -PallocIterations=1 -PallocTime=200ms`)

| Case | Command delta | Outcome |
|---|---|---|
| a. passing | `-Pbench=SchemaInferenceMicroBenchmark` | `BUILD SUCCESSFUL`, 3 rows (coldInference ≈13.4 kB/op, newAvroInstance ≈2.7 kB/op, warmCacheHit ≈0.08 B/op). Also passes after `rm -rf benchmark/build`. |
| b. all forks die | + `-PallocJmhArgs='-jvmArgsAppend -Xmx1k'` | `BUILD FAILED`: "3 of 3 JMH benchmark run(s) failed" plus the 3 names |
| b'. partial | `-Pbench=FieldOrderMicroBenchmark.read -Pparams='fieldOrder=matching,bogus'` | `BUILD FAILED`: "1 of 2 JMH benchmark run(s) failed … FieldOrderMicroBenchmark.read (fieldOrder = bogus)", and the `matching` row is still echoed |
| c. no match | `-Pbench=DoesNotExistAnywhere` | `BUILD FAILED`: JMH prints "No matching benchmarks. Miss-spelled regexp?" and exits 1, so JavaExec fails. This already failed before the change. |

### Surprises

- **JBR ignores unknown `-XX` flags.** `java -XX:+UnknownFlag -version` succeeds on
  `jbr-21.0.11`, so that flag does not kill a fork there. `-Xmx1k` does ("Too small maximum heap").
- **JMH prints `<failure>` mid-line** (`# Warmup Iteration   1: <failure>`) when a benchmark throws.
  The first version matched markers with `startsWith` and missed it. The rows-vs-runs safety net still
  failed the build, but without naming the run. The check now uses `contains`.

## Follow-ups

- Nothing else in the repo runs from `mainBenchmarkJar` except the manual async-profiler recipe in the
  README. If that recipe becomes a task, give it the same classpath as `benchmarkAlloc` instead of the jar.
- `ArrayGenericDecoder` has the same union blind spot. For a *plain* `Collection` (not a
  `GenericArray`) in a nullable position, `writerSchema` is the union, and `elementType` throws if
  anything reads it. This is pre-existing and not made worse by item 1. The fix would be the same
  "pick the union's branch" as `MapGenericDecoder.mapSchema`. Low priority: generic-data API is
  deprecated.
