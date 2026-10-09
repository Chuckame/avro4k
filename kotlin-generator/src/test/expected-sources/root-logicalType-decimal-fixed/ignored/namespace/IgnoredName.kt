@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package ignored.namespace

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
@SerialName("ignored.namespace.IgnoredName")
@AvroGenerated("""{"type":"fixed","name":"IgnoredName","namespace":"ignored.namespace","size":20,"logicalType":"decimal","precision":10,"scale":4}""")
public value class IgnoredName(
    @AvroDecimal(
        scale = 4,
        precision = 10,
    )
    @AvroFixed(size = 20)
    public val `value`: @Serializable(with = BigDecimalSerializer::class) BigDecimal,
)
