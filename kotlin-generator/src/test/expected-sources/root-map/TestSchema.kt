@file:OptIn(
    InternalAvro4kApi::class,
    ExperimentalAvro4kApi::class,
)

import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroProp
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.Double
import kotlin.Int
import kotlin.OptIn
import kotlin.collections.Map
import kotlin.collections.emptyMap
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
@AvroGenerated("""{"type":"map","values":["double","null","int"],"java-key-class":"java.lang.Integer"}""")
public value class TestSchema(
    @AvroProp("java-key-class", "java.lang.Integer")
    @AvroDefault("{}")
    public val `value`: Map<Int, ValueUnion?> = emptyMap(),
) {
    @Serializable
    @AvroGenerated("""["double","int"]""")
    public sealed interface ValueUnion {
        @JvmInline
        @Serializable
        @AvroGenerated(""""double"""")
        public value class ForDouble(
            public val `value`: Double,
        ) : ValueUnion

        @JvmInline
        @Serializable
        @AvroGenerated(""""int"""")
        public value class ForInt(
            public val `value`: Int,
        ) : ValueUnion
    }
}
