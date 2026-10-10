# Backlog — decoding hardening against untrusted input (deferred)

**Status:** deferred by the user on 2026-10-11, while answering M3-22's question 3. M3-22 implements only the first two
of Apache Avro 1.12.2's four collection guards (the total-count limit, and no up-front reservation for blocks above
1,024 items), which are enough to fix the `OutOfMemoryError` the random-bytes fuzz found. The two below are kept here
for a later hardening unit. Source read: Apache Avro 1.12.2 `GenericDatumReader`, `BinaryDecoder`,
`SystemLimitException` (the fast reader, `FastReaderBuilder`, applies the same guards).

Only **decoding** is concerned, and only bytes that were not written by a correct encoder (corrupted, truncated, or
hostile input): a correct encoder always writes a count equal to the items that follow.

## Guard 3 — the block count against the bytes left, when the input length is known

Every item takes a minimum number of bytes on the wire, so `count × minimum > bytes left` proves the data corrupt
before anything is allocated or read. Apache (`ensureAvailableCollectionBytes`, `ensureAvailableMapBytes`), on every
block, first and next:

| item schema | minimum bytes |
|---|---|
| `null` | 0 (not checkable, see guard 4) |
| `fixed` | its size |
| `float` / `double` | 4 / 8 |
| record | sum of its fields' minimums (0 when the record refers to itself, to break the cycle) |
| union, and everything else | 1 |
| map entry | 1 (the key's length) + the value's minimum |

The length is known only for byte-array input and for `ByteArrayInputStream`/`ByteBufferInputStream`
(`Decoder.remainingBytes()`, `-1` otherwise); Apache throws `EOFException("Collection claims N elements with at least M
bytes each, but only R bytes are available")`, and recomputes the minimum on each block with an allocating schema walk.

For avro4k: byte arrays, `ByteBuffer` and a kotlinx-io `Buffer` know their size; streams (`Source` in general) do not.
The minimum would be cached on the item schema (a lazy field, as the record field index is), so the check is one
multiplication and one comparison per block. **Value:** an earlier failure with a clearer message on truncated
in-memory input; with guard 2 in place it prevents no `OutOfMemoryError` (decoding fails at the end of input anyway).

## Guard 4 — a cap on zero-byte collection items per decoded value

Items whose encoding is zero bytes (`null`, `fixed(0)`, a record of only such fields, and conservatively a recursive
record) make a tiny **valid** payload stand for a huge collection: an `array<null>` of 2 billion items is the count (5
bytes) plus the terminator (1 byte). Guards 1–3 cannot catch it (the count is legal, 0 bytes per item fits any input),
and decoding it faithfully builds a 2-billion-entry list: `OutOfMemoryError`, a "zip bomb" for whoever decodes untrusted
input. Apache (`SystemLimitException.checkMaxCollectionAllocation`): the zero-byte items of all the collections of one
decoded value are summed in a thread-local scope (opened by `GenericDatumReader.read`, nested collections add to it),
and the sum is capped at `org.apache.avro.limits.collectionItems.maxAllocation`, default `maxMemory / 4 / 8` (a quarter
of the heap, 8 bytes per slot); over it, `SystemLimitException`.

For avro4k, open questions when this is scheduled: a fixed internal limit (same on every platform: heap size is a
JVM-only notion) or a public `AvroConfiguration` option (new public API, user approval); the exception would be the
malformed-data family of the exception hierarchy, or a new "limit exceeded" subclass. Until then, the limitation is
documented: decoding untrusted input with zero-byte collection items can allocate in proportion to the claimed count.
