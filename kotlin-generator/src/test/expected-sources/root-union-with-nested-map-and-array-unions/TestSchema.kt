@file:OptIn(
    InternalAvro4kApi::class,
    ExperimentalAvro4kApi::class,
)

import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.Boolean
import kotlin.Double
import kotlin.Int
import kotlin.Long
import kotlin.OptIn
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""["int",{"type":"map","values":["string","null",{"type":"array","items":["long","null",{"type":"map","values":["boolean","null"]}]}]},{"type":"array","items":["long","null","double"]}]""")
public sealed interface TestSchema {
    @JvmInline
    @Serializable
    @AvroGenerated(""""int"""")
    public value class ForInt(
        public val `value`: Int,
    ) : TestSchema

    @JvmInline
    @Serializable
    @AvroGenerated("""{"type":"map","values":["string","null",{"type":"array","items":["long","null",{"type":"map","values":["boolean","null"]}]}]}""")
    public value class ForMap(
        public val `value`: Map<String, ValueUnion?>,
    ) : TestSchema {
        @Serializable
        @AvroGenerated("""["string",{"type":"array","items":["long","null",{"type":"map","values":["boolean","null"]}]}]""")
        public sealed interface ValueUnion {
            @JvmInline
            @Serializable
            @AvroGenerated(""""string"""")
            public value class ForString(
                public val `value`: String,
            ) : ValueUnion

            @JvmInline
            @Serializable
            @AvroGenerated("""{"type":"array","items":["long","null",{"type":"map","values":["boolean","null"]}]}""")
            public value class ForArray(
                public val `value`: List<ValueUnion?>,
            ) : ValueUnion {
                @Serializable
                @AvroGenerated("""["long",{"type":"map","values":["boolean","null"]}]""")
                public sealed interface ValueUnion {
                    @JvmInline
                    @Serializable
                    @AvroGenerated(""""long"""")
                    public value class ForLong(
                        public val `value`: Long,
                    ) : ValueUnion

                    @JvmInline
                    @Serializable
                    @AvroGenerated("""{"type":"map","values":["boolean","null"]}""")
                    public value class ForMap(
                        public val `value`: Map<String, Boolean?>,
                    ) : ValueUnion
                }
            }
        }
    }

    @JvmInline
    @Serializable
    @AvroGenerated("""{"type":"array","items":["long","null","double"]}""")
    public value class ForArray(
        public val `value`: List<ValueUnion?>,
    ) : TestSchema {
        @Serializable
        @AvroGenerated("""["long","double"]""")
        public sealed interface ValueUnion {
            @JvmInline
            @Serializable
            @AvroGenerated(""""long"""")
            public value class ForLong(
                public val `value`: Long,
            ) : ValueUnion

            @JvmInline
            @Serializable
            @AvroGenerated(""""double"""")
            public value class ForDouble(
                public val `value`: Double,
            ) : ValueUnion
        }
    }
}
