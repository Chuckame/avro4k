@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package other.ns

import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.ByteArray
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * a fixed with a doc and aliases
 */
@JvmInline
@Serializable
@SerialName("other.ns.Documented")
@AvroDoc("a fixed with a doc and aliases")
@AvroAlias(
    "other.ns.OldDocumented",
    "legacy.ns.LegacyDocumented",
)
@AvroGenerated("""{"type":"fixed","name":"Documented","namespace":"other.ns","doc":"a fixed with a doc and aliases","size":8,"aliases":["OldDocumented","legacy.ns.LegacyDocumented"]}""")
public value class Documented(
    @AvroFixed(size = 8)
    public val `value`: ByteArray,
)
