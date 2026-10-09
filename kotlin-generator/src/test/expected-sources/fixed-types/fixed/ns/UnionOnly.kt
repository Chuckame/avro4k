@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package fixed.ns

import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.ByteArray
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
@SerialName("fixed.ns.UnionOnly")
@AvroGenerated("""{"type":"fixed","name":"UnionOnly","namespace":"fixed.ns","size":5}""")
public value class UnionOnly(
    @AvroFixed(size = 5)
    public val `value`: ByteArray,
)
