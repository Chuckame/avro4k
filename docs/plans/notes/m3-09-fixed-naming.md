# M3-09 follow-up — Fixed type naming

**Outcome:** done. A user-approved behaviour change (2026-09-25, `PROGRESS.md` decision log). No locked decision
touched. Not api: `core/api`, `apache-interop/api` and `kotlin-generator/api` are unchanged
(`git diff ce8be18 -- core/api apache-interop/api kotlin-generator/api` is empty); `AvroFixed.createSchema(elementLocation)`
keeps its signature, only its naming logic changed.
**Commits:** `feat(core): name fixed types after their value class or record` and
`feat(kotlin-generator): generate fixed types as named value classes` (`git log -2 -- docs/plans/notes/m3-09-fixed-naming.md`
finds the second one, which adds this file).

The migration-guide section the user asked for is in [`b8-migration-items.md`](b8-migration-items.md), "Fixed type names".

## Rules implemented (core)

Paths are relative to `core/src/jvmMain/kotlin/com/github/avrokotlin/avro4k/`.

Every `@AvroFixed` schema goes through `serializer/AvroSerializer.kt`'s `AvroFixed.createSchema(elementLocation)`.
Callers (grepped): `SerializerLocatorMiddleware.kt` (the `String` and `ByteArray` serializers),
`KotlinStdLibrarySerializers.kt` (`kotlin.uuid.Uuid`), `JavaStdLibSerializers.kt` (`UUID`, `BigDecimal`). No other
module calls it (confluent, benchmark, apache-interop grepped). `elementLocation` is the first location of
`inlinedElements` that carries `@AvroFixed`, i.e. where the annotation was found.

| `elementLocation.descriptor` | Fixed full name | Doc, aliases |
|---|---|---|
| `isInline` (the annotation is on a value class's own property) | the value class's `nonNullSerialName`, passed to Apache's `Schema.createFixed` as a full name: a dot splits namespace and name, no dot means no namespace (the same rule as records) | the value class's class-level `@AvroDoc`, and `@AvroAlias` added with Apache's `addAlias(alias)`, so relative to the fixed's namespace like `toSchemaAliases` does for records |
| otherwise (a data class property) | the property's element name, in the namespace `descriptor.nonNullSerialName` (the record's full name): `ns.A.id` | none (the property's `@AvroDoc`/`@AvroAlias` stay the field's) |

- **UUID, `kotlin.uuid.Uuid`, `BigDecimal`**: they call `createSchema` then `copy(logicalType = …)`. `Schema.copy`
  (`internal/helpers.kt`) keeps name, namespace, doc and aliases for a fixed, so they follow both rules. Tested for all
  three, on a field and in a value class (with aliases and doc for UUID and BigDecimal).
- **String** fixed: tested too (field and value class).
- **Durations are unchanged**: `java.time.Duration`, `java.time.Period`, `kotlin.time.Duration` and `AvroDuration` use
  `AvroDurationSerializer.DURATION_SCHEMA`, a fixed(12) built without `@AvroFixed`, so they are not concerned.
- **The ambiguous case, `@AvroFixed` on both the field and the value class's property** (e.g. `@AvroFixed(5) val
  mystring: FixedStringValueClass`): `inlinedElements.firstNotNullOfOrNull` finds the field first, as before. So the
  field's size wins (unchanged, pinned by `AvroFixedEncodingTest`), and so does the field's naming
  (`<record>.<field>`); the value class's aliases and doc are not applied. Pinned by `AvroFixedNamingSchemaTest`. The
  README states it.
- **Nested value classes**: the outermost value class carrying `@AvroFixed` names the fixed (e.g.
  `value class Outer(@AvroFixed(3) val bytes: PlainBytes)` → `Outer`'s serial name); a value class wrapping a fixed value
  class without `@AvroFixed` of its own keeps the inner one's name. Both pinned.
- The field rule uses the property's **element name** (`getElementName`), as before, not the configured
  `fieldNamingStrategy`: the record's field may be named `my_id` while its fixed is `ns.A.myId`. Unchanged behaviour,
  noted as open below.
- `internal/helpers.kt`'s `SerialDescriptor.namespace` had no other user and is deleted.

**Rejection message** (`internal/schema/VisitorContext.shareNamedSchema`): now
`Can't redefine: <full name>. Two different named schemas have this full name: <first json> and <second json>. A name
can only define one type in a schema.` — it starts like Apache's `Schema.Parser` message and keeps both definitions.
`ClassVisitor`'s "a record meets another named schema of the same name" message starts with `Can't redefine: <full name>.`
too (same situation, same Apache message).

**KDoc** (no signature change): `AvroAlias` and `AvroDoc` no longer say "Ignored in value classes"; they say they apply
to the fixed of a value class whose property has `@AvroFixed`, and are ignored on other value classes and on value
class properties. `AvroFixed` documents both naming rules. `createSchema` documents them.

## Changed expectations (core), old → new fixed full name

| Test | Old | New |
|---|---|---|
| `encoding/AvroFixedEncodingTest` "support fixed on data class fields", "support @AvroFixed on ByteArray" (`resources/fixed_string.json`) | `mystring` | `Fixed.mystring` |
| `encoding/AvroFixedEncodingTest` "support fixed on string value classes" (was `fixed_string.json`, now the new `resources/fixed_string_value_class.json`) | `mystring` | `FixedString` (the value class's `@SerialName`) |
| `encoding/AvroFixedEncodingTest` "top-est @AvroFixed annotation takes precedence…" (`resources/fixed_string_5.json`) | `mystring` | `FieldPriorToValueClass.mystring` |
| `schema/UUIDSchemaTest` "support UUID logical types as fixed" | `com.github.avrokotlin.avro4k.schema.UUIDSchemaTest.uuidFixed` | `….UUIDSchemaTest.UUIDTestFixed` |
| `schema/KotlinUuidSchemaTest` "support kotlin.uuid.Uuid logical types as fixed" | `….KotlinUuidSchemaTest.uuidFixed` | `….KotlinUuidSchemaTest.KotlinUuidTestFixed` |
| `schema/BigDecimalSchemaTest` "support BigDecimal decimal logical types as fixed" | `field` | `DecimalFixedTest` |
| `encoding/LogicalTypesEncodingTest` "support nullable logical types" (the expected `GenericData.Fixed`s) | `….LogicalTypesEncodingTest.decimalFixedNullable`, `….LogicalTypesEncodingTest.uuidFixed` | `….LogicalTypesEncodingTest.NullableLogicalTypes.decimalFixedNullable`, `….NullableLogicalTypes.uuidFixed` |
| `internal/schema/SchemaGenerationTest` "a named type used twice is a single instance" | two fields `same.A.hash` and `same.B.hash`, both named `same.hash` by the old rule | one value class `@SerialName("same.hash")` used by both records (the assertion is unchanged) |
| `internal/schema/SchemaGenerationTest` "two different named types … are rejected" | two fields `ns.A.hash` (4) and `ns.B.hash` (8), both `ns.hash` | two value classes `@SerialName("ns.hash")` of sizes 4 and 8; the message pin `contains "Two different named schemas have the same full name"` becomes `startsWith "Can't redefine: ns.hash. Two different named schemas have this full name: "` |
| `internal/SchemaFingerprintDifferentialTest` "every resource schema parses" | 51 resources | 52 (the new `fixed_string_value_class.json`) |

`LogicalTypesEncodingTest`'s non-nullable variant needs no change: the Apache writer only uses the fixed's name to pick
a union branch (`GenericData.resolveUnion` by full name), so a wrong name only shows in the nullable variant. That is the
Apache-side consequence written in the migration note.

## Tests added (core)

`core/src/jvmTest/.../schema/AvroFixedNamingSchemaTest.kt`, 15 tests. Each generated schema is also checked against
the Apache oracle: its `toString()` parses back with `Schema.Parser` to an equal schema (no "Can't redefine").

- value class: named after its serial name (no `@SerialName`); `@SerialName` + `@AvroAlias` (relative and absolute) +
  `@AvroDoc`; a serial name without a dot (no namespace, also nested in a namespaced record);
- data class property: `<record full name>.<property>`; a record serial name without a dot (`NoNamespaceRecord.id`);
- two records' fixed fields with one name and sizes 4 and 8 do not clash (`ns.A.id`, `ns.B.id`);
- field + value class both annotated: the field wins and names it;
- a value class fixed nullable, in a list, as map values, wrapped in another value class, and an outer value class
  carrying `@AvroFixed` over a plain value class;
- a value class used by two records is one fixed;
- rejections: two value classes with one serial name and different sizes (`Can't redefine: ns.Conflict. …`, both
  definitions in the message); a record and a fixed with one full name (`Can't redefine: ns.Clash. …`);
- UUID, `kotlin.uuid.Uuid`, BigDecimal (field; UUID and BigDecimal also in a value class with aliases and doc, which
  survive the logical-type `copy`); String (field and value class);
- **migration facts, pinned with Apache's `SchemaCompatibility`:** a data-class-field fixed `ns.id` → `ns.A.id` is
  COMPATIBLE both ways, also in a union; a value-class fixed `ns.value` → `ns.Hash` is INCOMPATIBLE (NAME_MISMATCH), and
  COMPATIBLE with `@AvroAlias("ns.value")`, or with `@SerialName("ns.value")`.

A trap met while writing the last test: three records with the same serial name (`ns.V`) in one `Avro` instance share a
schema cache entry, because plugin-generated descriptors with one serial name and equal elements are `equals`. The test
uses one `Avro {}` per record. This is pre-existing, not changed here (see "Left open").

## kotlin-generator

`kotlin-generator/src/main/kotlin/com/github/avrokotlin/avro4k/KotlinGenerator.kt` and `KotlinPoetHelpers.kt`.

**Invariant reached:** for any source schema, the schema that avro4k infers from the generated Kotlin has every fixed
with the source's full name, aliases, doc and size.

**A fact the brief did not have:** every generated record, enum, sealed interface and root value class carries
`@AvroGenerated(<source json>)`, and `ValueVisitor.visitValue` then returns that json verbatim. So `Avro.schema()` of
generated code already reproduced the source schema, fixed included, before this change. What was lost was the
schema *inferred from the Kotlin types alone* (a class used without its `@AvroGenerated`, or edited by hand): a fixed
field inferred `<package>.<field>`, with no aliases or doc, and a fixed in an array or a map inferred `bytes`, as
`List<ByteArray>` cannot carry `@AvroFixed`. The round-trip test below therefore strips `@AvroGenerated`, or it would
prove nothing.

What the generator does now:

- `isGeneratedAsFixedClass` (file-level): a `FixedSchema` whose logical type is not `duration`.
- Such a fixed generates a value class, one per full name, like records and enums (`generateFixedClass`): class named
  after the fixed's simple name, in the package of its namespace, with `@JvmInline`, `@Serializable`,
  `@SerialName("<full name>")`, `@AvroDoc` + KDoc, `@AvroAlias` (full names), `@AvroGenerated`, and on its `value`
  property `@AvroFixed(size)` plus `@AvroDecimal` for a decimal. `value`'s type is the logical type's or `java-class`'s
  type (`@Serializable(with = BigDecimalSerializer::class) BigDecimal`, `… UUIDSerializer … UUID`, `@Contextual …`), else
  `ByteArray`. Generated from `generateNestedKotlinClasses` (before the logical-type early return) and at the root.
- `getTypeName` returns that class, so fields, array items, map values, union branches and nullable unions reference
  it. The inlined `@AvroFixed`/`@AvroDecimal` are no longer added on the referencing property
  (`buildInlinedFixedAnnotations`).
- **Props:** the fixed's props are **not** written as `@AvroProp`: core rejects `@AvroProp` on a value class wrapping a
  named schema (`InlineClassVisitor`). `logicalType`, `precision` and `scale` are re-created by the serializers from the
  type and `@AvroDecimal`; any other custom prop of a fixed is only kept in `@AvroGenerated`. So the root uuid/decimal
  goldens lose their `@AvroProp("logicalType", …)` lines. The brief said "plus `@AvroDecimal` / props where the fixed
  carries them": `@AvroDecimal` yes, props no, for that reason.
- **Root fixed:** generated as the fixed's value class, named after the fixed, like a root record or enum, instead of a
  value class named after the file (`TestSchema`, `TypeFixed`). It carries the fixed's name with `@SerialName`, as the
  brief required. Two reasons: `generateKotlinClasses`' KDoc already says the anonymous root name is for "any schema
  except record, enum or fixed"; and in `types-across-multiple-files`, `union.avsc` references `FixedType`, which would
  otherwise have generated a second class `FixedType` next to the file's `TypeFixed` for one fixed. **Flagged for the
  user** (generated class names change for a root fixed file).
- **`duration`:** unchanged. `AvroDurationSerializer` builds its own fixed and ignores `@AvroFixed`, so a value class
  could not carry the fixed's name. It stays an `AvroDuration` property with `@AvroFixed(12)` (root: the `TestSchema`
  value class); `root-logicalType-duration` is byte-identical.
- **Equality:** a data class comparing a `ByteArray` field by content now compares a `ByteArray` fixed value class
  field by `value` content too (`f.value.contentEquals(other.f.value)`, `f?.value…` when nullable, and
  `contentHashCode`), via the new `addEqualsHashCode(className, byteArrayValueClasses)` parameter. The fixed value class
  itself cannot override `equals` (a Kotlin value class), like the union branch value classes before.
- **Defaults:** a `ByteArray` fixed default becomes `Fixed(byteArrayOf(…))`; a fixed value class wrapping a logical type
  gets no Kotlin default (its value cannot be built from the default's bytes), as a logical-type fixed field had none
  before (its type was not natively serializable).

### Goldens (`kotlin-generator/src/test/expected-sources/`), each reviewed

Regenerated the way the suite does (`./gradlew :kotlin-generator:test` without `CI`: `updateExpectedGeneratedSources`),
then verified with `CI=true ./gradlew :kotlin-generator:test` (golden compare): green, no further change. Only
fixtures with a fixed changed:

| Fixture | Old | New |
|---|---|---|
| `flat-record-with-all-types/ns/theRecord.kt` | `@AvroFixed(size = 3) val f: ByteArray = byteArrayOf(-1, 4, 7)`, `f.contentEquals` | `val f: Fixed = Fixed(byteArrayOf(-1, 4, 7))`, `f.value.contentEquals` / `contentHashCode` |
| `flat-record-with-all-types/ns/Fixed.kt` | — | new: `ns.Fixed` value class, `@AvroDoc("the doc")` + KDoc, size 3 |
| `nested-record/ns/theRecord.kt`, `nested-record/ns/Fixed.kt` | as `flat-record-with-all-types` | same change |
| `record-as-default/SimpleSubRecord.kt` | `@AvroFixed(size = 3) val fixedField: ByteArray` | `val fixedField: Fixed`, equality on `.value` |
| `record-as-default/BaseRecord.kt` | default `fixedField = byteArrayOf(1, 2, 3)` | `fixedField = Fixed(byteArrayOf(1, 2, 3))` |
| `record-as-default/Fixed.kt` | — | new: `Fixed` (no namespace) |
| `root-complex-union/TestSchema.kt` | `ForAFixed(@AvroFixed(size = 5) val value: ByteArray)` | `ForAFixed(val value: AFixed)` |
| `root-complex-union/AFixed.kt` | — | new |
| `root-fixed/TestSchema.kt` → `root-fixed/ignored/namespace/IgnoredName.kt` | `value class TestSchema(@AvroFixed(16) val value: ByteArray)` | `@SerialName("ignored.namespace.IgnoredName") value class IgnoredName(@AvroFixed(16) val value: ByteArray)` |
| `root-logicalType-decimal-fixed/TestSchema.kt` → `…/ignored/namespace/IgnoredName.kt` | `TestSchema` with `@AvroDecimal`, `@AvroFixed(20)`, three `@AvroProp` | `IgnoredName` with `@SerialName`, `@AvroDecimal`, `@AvroFixed(20)`, no `@AvroProp` |
| `root-logicalType-uuid-fixed/TestSchema.kt` → `…/ns/TheUuid.kt` | `TestSchema` with `@AvroFixed(16)`, `@AvroProp("logicalType", "uuid")` | `@SerialName("ns.TheUuid") value class TheUuid(@AvroFixed(16) val value: UUID)` |
| `types-across-multiple-files/TypeFixed.kt` → `…/FixedType.kt` | `value class TypeFixed(@AvroFixed(12) …)` | `@SerialName("FixedType") value class FixedType(@AvroFixed(12) …)` |
| `types-across-multiple-files/Union.kt` | `ForFixedType(@AvroFixed(size = 12) val value: ByteArray)` | `ForFixedType(val value: FixedType)` |
| `fixed-types/**` | — | new fixture (below) |

Unchanged: every other fixture, including `root-logicalType-duration` and `root-bytes`.

### Round-trip proof of the invariant

New fixture `kotlin-generator/src/test/resources/fixed-types.avsc` (and its goldens): a record `fixed.ns.FixedTypes`
with fixed fields that are plain, in another namespace with doc and two aliases (one relative, one absolute), referenced
twice by name (and from a nested record), nullable (with a `null` default), array items, map values (by name), in no
namespace (`"namespace": ""`), uuid, decimal, with a bytes default, and a union branch (named twice, and one only in the
union).

**Approach: compile the generated code, through the module's existing compile harness**, rather than a hand copy in
`core/src/jvmTest`, so that it cannot go stale. `kotlin-generator/build.gradle.kts`:
- `fixedTypesWithoutAvroGenerated` (a `Sync`) copies `expected-sources/fixed-types` into `build/` without the
  `@AvroGenerated(` lines (see the fact above: with them the test would be vacuous);
- source set `fixedTypesRoundTrip` compiles that copy plus `src/fixedTypesRoundTrip/kotlin/.../FixedTypesRoundTripTest.kt`
  against `:core`;
- its own `Test` task `fixedTypesRoundTripTest`, which `:kotlin-generator:test` depends on. A separate task because the
  first attempt, in `src/test`, failed with `NoSuchMethodError: FieldNamingStrategy.resolve`: the generator's own
  `com.github.avrokotlin.avro4k.FieldNamingStrategy` shadows core's class of the same name on the `test` classpath.
- spotless' kotlin target excludes `build/**` (the copy).

The test infers `Avro.schema()` of `fixed.ns.FixedTypes`, collects every fixed (full name → aliases, doc, size, logical
type) of it and of the parsed `.avsc`, and asserts the two maps are equal and cover the 9 source fixed types; it then
checks that no descriptor of the tree carries `@AvroGenerated`.

The chain is generator → golden (the `CI=true` compare) → inferred schema (this test). In the default, non-`CI` mode the
suite rewrites the goldens *after* the round trip compiled them, so a generator change shows in the round trip on the
next run; in `CI` mode the goldens are compared first-hand.

## Mutation checks (each run, then reverted)

| Mutation | Result |
|---|---|
| Value-class rule off (`createSchema` always uses the field rule) | `:core:jvmTest` 16 of 919 fail: 10 of the 15 new tests, the UUID / Uuid / BigDecimal fixed schema tests, `AvroFixedEncodingTest` "string value classes", and both `SchemaGenerationTest` fixed tests |
| Field rule back to the package namespace | 11 fail: the 7 field-rule tests of the new class, 3 `AvroFixedEncodingTest` tests, `LogicalTypesEncodingTest` nullable |
| Value class's aliases and doc dropped, and the `Can't redefine:` prefix removed | 6 fail: the alias/doc tests (incl. UUID/BigDecimal and the migration alias test), both rejection-message tests |
| Generator: no fixed value class (`isGeneratedAsFixedClass = false`) | `CI=true :kotlin-generator:test`: 9 golden compares fail (exactly the 9 fixtures with a fixed). Then with the goldens regenerated by that generator, `fixedTypesRoundTripTest` fails: every fixed is named `fixed.ns.FixedTypes.<field>`, and `items.ns.Item` is missing (inferred `bytes`) |

## Structural-equality grep

No `==`, `indexOf`, `contains` or equality-hashed key on `AvroSchema`/`Schema`/`Field` was added in main code.
`createSchema` compares nothing. The generator compares `String`s (`logicalTypeName`), and kotlinpoet `TypeName`s
(`addEqualsHashCode`'s `in byteArrayValueClassTypes`), not schemas. The round-trip test compares plain data classes of
strings and ints.

## Test counts

| Task | Before (`ce8be18`, measured) | After |
|---|---|---|
| `:core:jvmTest` | 904 | **919** (+15, `AvroFixedNamingSchemaTest`) |
| `:apache-interop:test` | 451 | **459** (+8: its resource round trip runs 4 tests per schema file, and there are 2 new ones, `fixed_string_value_class.json` and `fixed-types.avsc`) |
| `:confluent-kafka-serializer:test` | 72 | 72 |
| `:kotlin-generator:test` | 148 (1 skipped) | **149** (1 skipped) (+1, the `fixed-types` fixture) |
| `:kotlin-generator:fixedTypesRoundTripTest` (new, run by `:kotlin-generator:test`) | — | **1** |
| `:gradle-plugin:test` | 2 | 2 |

**Acceptance:**
1. `./gradlew :core:jvmTest :apache-interop:test :confluent-kafka-serializer:test :kotlin-generator:test :gradle-plugin:test :benchmark:compileKotlin apiCheck spotlessApply`
   passed.
2. `./gradlew actionsBeforeCommit` passed.
3. `CI=true ./gradlew :kotlin-generator:test` passed (golden compare).

No benchmark was run (schema generation is a cold path; the warm path is untouched).

## Docs

- `README.md`: "Change type name (FIXED only)" becomes "Change type name (FIXED)": both rules, the precedence case,
  UUID/Uuid/BigDecimal, durations not concerned, the `Can't redefine` rejection, and "Keep a previous fixed name" (value
  class with `@SerialName`, or `@AvroAlias`). "Add documentation" and "Add aliases" point to it.
- `gradle-plugin/README.md`: a "Fixed types" section (the value class, props only in `@AvroGenerated`, `duration`), and
  the root-type list updated.
- New `docs/plans/notes/b8-migration-items.md` (see above).

## Left open

- **User decision:** the root fixed file now generates a class named after the fixed, not after the file (see
  "Root fixed").
- The field rule's name is the property's element name, not the `fieldNamingStrategy`-resolved field name (unchanged
  behaviour; with a snake-case strategy, field `my_id` has fixed `<record>.myId`).
- Schema cache: descriptors with one serial name and equal elements are `equals`, so two classes with one serial name
  share a cache entry in one `Avro` instance (pre-existing; now more visible, since `@SerialName` names fixed types).
- The generator's `@SerialName` on a fixed value class is redundant today (the class's FQCN is the fixed's full name),
  but explicit, as the brief asked.
- A fixed's custom props (other than the logical type's) are only kept by `@AvroGenerated` in the generated code.
