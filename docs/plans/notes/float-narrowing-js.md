# Double → float narrowing on Kotlin/JS

**Outcome:** fixed. A double that no float holds exactly is now rejected on JS as on the JVM and native, on the three
paths that narrow a double to a float. Tests green on JVM, JS (Node) and macosArm64, native test binaries of the other
targets linked. Internal code only: no API change (`core/api` and `apache-interop/api` clean).

**Branch** `v3/float-narrowing-js`, base `4254a4d0`, one commit (code, tests, this note).

**`main` is not concerned:** on `main` (`6ad6c1c6`) `core` is a JVM-only module (`library-module-conventions`, no
`jsMain`). Its `Double.toFloatExact()` is the same code and is correct on the JVM. JS is new, so this divergence is
migration scope, not a pre-existing bug.

## Cause

`Double.toFloatExact()` (`C/internal/NumberUtils.kt`) checked `this.toFloat().toDouble() != this`. Kotlin/JS stores a
`Float` as a JS number, and its `Double.toFloat()` does not round to 32 bits, so the round trip returns the double
unchanged and the check never fails. Measured on Node, Kotlin 2.4.20 (throwaway probe, removed):

| expression | JVM, native | JS |
|---|---|---|
| `0.1.toFloat().toDouble()` | `0.10000000149011612` | `0.1` |
| `0.1f.toDouble()` | `0.10000000149011612` | `0.1` (a float literal is not rounded either) |
| `"0.1".toFloat().toDouble()` | `0.10000000149011612` | `0.1` |
| `1e-50.toFloat().toDouble()` | `0.0` | `1e-50` |
| `1.0e40.toFloat()` | `Infinity` | `1e+40` |
| `Float.MAX_VALUE.toDouble()` | `3.4028234663852886E38` | `3.4028235e+38` (the constant is not a 32-bit value) |
| `Float.fromBits(0.1.toFloat().toRawBits()).toDouble()` | `0.10000000149011612` | `0.10000000149011612` |

`toRawBits`/`toBits`/`fromBits` do round on JS (they go through a `Float32Array`), so the bytes of a float are right
everywhere; only the value check was wrong.

Callers: `AbstractAvroEncoder.encodeDoubleConverted` (a `Double` written to a `float` schema, directly or as the `float`
branch of a union), `AbstractAvroDirectDecoder.decodeFloat` (a `Float` field read from a writer `double`) and
`JsonDefaultDecoder.decodeFloat` (a `Float` field whose reader schema is `double` taking its default).

## Fix

`toFloatExact` becomes `internal expect fun Double.toFloatExact(): Float` in `commonMain`, with two actuals:

- `jvmAndNativeMain/…/internal/NumberUtils.jvmAndNative.kt`: today's code, unchanged.
- `jsMain/…/internal/NumberUtils.js.kt`: `if (fround(this) != this) invalidTypeError()`, `fround` being
  `js("Math.fround(value)")` (compiled to a plain `Math.fround(value)` call, checked in the JS output).

`Math.fround` was preferred to a common comparison through `toRawBits`/`fromBits` (which also rounds on JS): the common
form would have added two calls to the JVM method, and the per-platform form keeps the JVM bytecode identical. The
returned float is `this.toFloat()` on both: once the check passed it is the same number.

### Bytecode (`javap -c -p`, offset of the last instruction, `core/build/classes/kotlin/jvm/main`)

| Method | Before | After |
|---|---:|---:|
| `toFloatExact` | 49 (`NumberUtilsKt`) | 49 (`NumberUtils_jvmAndNativeKt`), the same 27 instructions |
| `AbstractAvroEncoder.encodeDoubleConverted` | 91 | 91 |
| `AbstractAvroDirectDecoder.decodeFloat` | 149 | 149 |
| `JsonDefaultDecoder.decodeFloat` | 168 | 168 |

Only the class that holds `toFloatExact` changes (the actual lives in another file); the callers' `invokestatic` has the
same size.

## Behaviour per platform

Double → float, on the three paths (encoder, writer double read into a `Float`, `double` default read into a `Float`):

| value | JVM, native before and after | JS before | JS after |
|---|---|---|---|
| `0.5`, `1.0`, `-0.0` (sign kept: bits `80000000`) | accepted | accepted | accepted |
| largest float as a double, `3.4028234663852886E38`, and its negative | accepted | accepted | accepted |
| smallest subnormal float as a double, `1.401298464324817E-45` | accepted | accepted | accepted |
| `+Infinity`, `-Infinity` | accepted | accepted | accepted |
| `NaN` | rejected (never equal to itself) | rejected (same reason) | rejected, kept as on the JVM |
| `0.1`, `1e-50`, `1e40`, just above the largest float, just below its negative | rejected | **accepted** | rejected |

The error is the same `AvroSchemaMismatchException("Value 0.1 is not a valid Float")` (the number in the message is the
platform's `Double.toString()`, so `1e-50` prints `1.0E-50` on the JVM and `1e-50` on JS, as every message of that kind
already did).

Consequence to know on JS: a `Float` that never went through 32 bits (a literal such as `0.1f`, or `Float.MAX_VALUE`,
which is `3.4028235e+38` there) widened to a `Double` and then written to a `float` schema is now rejected, as the same
double would be on the JVM. A Kotlin `Float` written to a `float` schema is not checked and is unaffected.

## Other float narrowings checked

| Narrowing | Result |
|---|---|
| Bytes of an accepted double written to `float` | Identical: `writeFloat` uses `toBits`, which rounds on JS too. Shown by `a double exact as a float is written to a float schema` (bytes compared with the codec's, bits given explicitly). |
| `Float` field read from a writer `double` | Fixed by the same change (it calls `toFloatExact`). `a float field reads a writer double only when it is exact as a float`. |
| `Double` field read from a writer `float` | Identical: `readFloat` uses `fromBits`. `a double field reads a float writer as the value of the float`. |
| `String` written to a `float` schema | Identical bytes for ordinary texts (`0.1`, `1.5`, `1e-50` → 0, `1e40` → infinity): `toFloat()` then `toBits()` rounds on JS. `a string written to a float schema gives the bytes of the rounded float`. Edge case below. |
| `Float` read from text (writer `string`, a `float`/`string`/`enum` default, a `float` default read into a `Double`) | **Not identical, not fixed.** See below. Pinned per platform by `a float read from text is the nearest float except on JS`. |

### Not fixed: a float read from text keeps a double's precision on JS

`String.toFloat()` (and kotlinx-serialization-json's `JsonPrimitive.float`, which is `content.toFloat()`) does not round
on JS. So `"0.1"` read into a `Float` from a writer `string`, or from a `float` default, is `0.1` on JS and
`0.10000000149011612` on the JVM; `"1e40"` is `1e40` on JS and `Infinity` on the JVM. The same float read from its 4
bytes is rounded on JS, so on JS the value depends on the writer type.

A JS actual rounding the parse with `Math.fround` was written and works, but it breaks the golden fixture `floating-point
numbers written as strings` on JS: the fixture decodes `"3.4028235e+38"` (JS's text for `Float.MAX_VALUE`) and writes the
decoded value back; rounded, it comes back as `"3.4028234663852886e+38"`. That fixture pins the text a stringable `Float`
writes on JS, a behaviour the user decided to keep on 2026-10-13 (`m3-16.md`), and units do not edit expected bytes. So
the rounding was taken out and the current behaviour is pinned instead, for the coordinator to decide. Options:
round the parse on JS and change that fixture's JS round trip, or leave it as Kotlin/JS's `Float` semantics.

### Not fixed: text exactly between two floats, after a double's rounding

On JS a decimal text is rounded twice (to a double, then to a float), which can differ from the JVM's single rounding
for a text within a double's precision of the midpoint between two floats. Measured with
`"1.000000178813934326171874999"` written to a `float` schema: JVM and native `0100803f` (`1.0000001`), JS `0200803f`
(`1.0000002`). Fixing it needs a correctly rounded decimal-to-float parser on JS; this applies to the bytes written and to
the `Float` read. Not tested, recorded here.

## Other divergences seen (not float narrowing, not touched)

- A Kotlin `Float` written to a `double` schema: `0.1f` writes the double `0.1` on JS (`9a9999999999b93f`) and
  `0.10000000149011612` on the JVM (`000000a09999b93f`), because a JS float literal holds a double. Same root cause
  (Kotlin/JS `Float` semantics); rounding with `Math.fround` before widening would align it.
- `Float`/`Double` written as text (`0.1` decoded from a float into a `String`: JVM `0.1`, JS `0.10000000149011612`;
  `1e-50` → `1.0E-50` vs `1e-50`): already known and documented, `m3-16.md`.

## Tests

New file `core/src/commonTest/kotlin/com/github/avrokotlin/avro4k/encoding/FloatNarrowingTest.kt`, 7 tests, run on JVM,
JS and native, each through `Avro` and `Avro { validateSerialization = true }` where it encodes or decodes bytes:

1. `a double exact as a float is written to a float schema`: the exact values above into `float`, `["null","float"]`,
   `["string","float"]`, bytes compared.
2. `a double not exact as a float is rejected by a float schema`: the inexact values and NaN into the same schemas; the
   message for `0.1` pinned.
3. `a float field reads a writer double only when it is exact as a float`: writers `double` and `["null","double"]`.
4. `a float field takes a double default only when it is exact as a float`: `JsonDefaultDecoder` on `double` and
   `["null","double"]` with json texts (so `-0.0` keeps its sign on JS), then end to end through records whose `Float`
   field has a `double` schema (a test serializer) and an `@AvroDefault` of `0.5` (read) and `0.1` (rejected).
5. `a float read from text is the nearest float except on JS`: pins the current per-platform values (see above).
6. `a double field reads a float writer as the value of the float`.
7. `a string written to a float schema gives the bytes of the rounded float`.

Restored in `UnionBranchSelectionTest.a double selects double first then float then string`: `0.1` into
`["null","float"]` (the case removed from it before) and into `float`, both rejected.

**Shown failing before the fix:** the new tests and the restored case run against the base `commonMain` (the fix taken
out): JVM and macosArm64 green; JS fails tests 2, 3, 4 and the restored `UnionBranchSelectionTest` case (all "expected
exception but no exception was thrown" on `0.1`). Tests 1 and 7 pass on JS before and after (identical behaviour);
tests 5 and 6 do not go through `toFloatExact`.

| Task | Before (`4254a4d0`) | After |
|---|---:|---:|
| `:core:jvmTest` | 1158 | 1165 (+7) |
| `:core:jsNodeTest` | 315 | 322 (+7) |
| `:core:macosArm64Test` | 318 | 325 (+7) |

Note: on `jsNodeTest`, a `--tests '*Name'` filter with a leading wildcard silently runs nothing; use the full class
name.

## Acceptance

- `./gradlew :core:jvmTest :core:jsNodeTest :core:macosArm64Test :apache-interop:test :confluent-kafka-serializer:test
  :kotlin-generator:test :benchmark:compileKotlin apiCheck`: **BUILD SUCCESSFUL**.
- `./gradlew actionsBeforeCommit`: **BUILD SUCCESSFUL**.
- Native test binaries linked (the code adds an `expect`/`actual`): `:core:androidNativeArm32TestBinaries
  :core:androidNativeArm64TestBinaries :core:androidNativeX86TestBinaries :core:androidNativeX64TestBinaries
  :core:linuxArm64TestBinaries :core:iosArm64TestBinaries :core:watchosArm32TestBinaries :core:watchosArm64TestBinaries
  :core:watchosDeviceArm64TestBinaries :core:tvosArm64TestBinaries`: **BUILD SUCCESSFUL**.
- `git diff --exit-code core/api apache-interop/api`: clean.
- `spotlessApply` run before the commit. No benchmark run.
