# Kotlin/JS `Long` as a JavaScript `BigInt`: left to users, written down

**Status:** not done, by the user's decision (2026-10-29): it is the consumer's build choice, not avro4k's. Kept here so
it can go into the user documentation for JS, and be measured if someone asks.

## What it is

By default Kotlin/JS represents `Long` as an object holding two 32-bit halves, so `Long` arithmetic allocates and is far
slower than on the JVM or native. Kotlin/JS has an experimental compiler option that represents `Long` as an ES2020
`bigint` instead:

```kotlin
kotlin {
    js {
        compilerOptions {
            freeCompilerArgs.add("-Xes-long-as-bigint") // "Compile Long values as ES2020 bigint instead of object."
        }
    }
}
```

The flag and its description are the Kotlin 2.4.20 compiler's own (`K2JSCompilerArguments`). It needs an ES2020
runtime.

## Why it is the user's choice

- A library ships klibs (Kotlin IR), and the JavaScript is generated when the consumer links its executable. So the
  option applies to the whole program, avro4k included, when the consumer sets it. **Expected, not yet checked on a
  build.**
- It is experimental and changes how every `Long` in the program behaves at the JS boundary (interop with JS code that
  receives a `Long`). avro4k cannot impose that.
- It is not clearly faster: `bigint` arithmetic is arbitrary precision and also allocates in most engines. Only a
  measurement would say.

## Where avro4k stands without it

- Reads: `BufferedDecoder.readLong` decodes the varint in `Int` halves and builds one `Long` at the end.
- Writes: `ByteArrayEncoder.writeLong` did zigzag and varint in `Long` arithmetic. That costs several emulated-`Long`
  objects per value on JS (smoke run, 2026-10-28: JS `lists` write 190 ops/s against read 1,016). The JS write path in
  `Int` halves is the migration's answer (see `PROGRESS.md`); the `BigInt` option is on top of it, for users.

## If it is measured one day

Build `benchmark-common` with the flag as an extra side of a JS write comparison: the `lists` write row (full-range
random `Long`s) and the `complex` rows. Check the golden fixtures pass with the flag on (`jsNodeTest` with the option
added to `core`'s test compilation).

## For the user documentation (JS section)

One short paragraph: `Long` values are slow on Kotlin/JS by default; programs that read or write many `long` fields can
try `-Xes-long-as-bigint` (experimental, ES2020) and measure their own workload.
