@file:OptIn(
    InternalAvro4kApi::class,
    ExperimentalAvro4kApi::class,
)

package fixed.ns

import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import com.github.avrokotlin.avro4k.serializer.BigDecimalSerializer
import java.math.BigDecimal
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
@SerialName("fixed.ns.TheDecimal")
@AvroGenerated("""{"type":"fixed","name":"TheDecimal","namespace":"fixed.ns","size":8,"logicalType":"decimal","precision":10,"scale":2}""")
public value class TheDecimal(
    @AvroDecimal(
        scale = 2,
        precision = 10,
    )
    @AvroFixed(size = 8)
    public val `value`: @Serializable(with = BigDecimalSerializer::class) BigDecimal,
)
