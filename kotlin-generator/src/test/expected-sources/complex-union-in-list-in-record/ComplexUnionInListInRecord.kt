@file:OptIn(
    InternalAvro4kApi::class,
    ExperimentalAvro4kApi::class,
)

import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.OptIn
import kotlin.String
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""{"type":"record","name":"ComplexUnionInListInRecord","fields":[{"name":"theListField","type":{"type":"array","items":["null",{"type":"record","name":"NestedRecord","fields":[{"name":"id","type":"string"},{"name":"value","type":"int"}]},{"type":"enum","name":"Status","symbols":["ACTIVE","INACTIVE","PENDING"]},{"type":"array","items":"string"}]},"default":[]}]}""")
public data class ComplexUnionInListInRecord(
    /**
     * Default value: []
     */
    @AvroDefault("[]")
    public val theListField: List<TheListFieldUnion?> = emptyList(),
) {
    @Serializable
    @AvroGenerated("""[{"type":"record","name":"NestedRecord","fields":[{"name":"id","type":"string"},{"name":"value","type":"int"}]},{"type":"enum","name":"Status","symbols":["ACTIVE","INACTIVE","PENDING"]},{"type":"array","items":"string"}]""")
    public sealed interface TheListFieldUnion {
        @JvmInline
        @Serializable
        @AvroGenerated("""{"type":"record","name":"NestedRecord","fields":[{"name":"id","type":"string"},{"name":"value","type":"int"}]}""")
        public value class ForNestedRecord(
            public val `value`: NestedRecord,
        ) : TheListFieldUnion

        @JvmInline
        @Serializable
        @AvroGenerated("""{"type":"enum","name":"Status","symbols":["ACTIVE","INACTIVE","PENDING"]}""")
        public value class ForStatus(
            public val `value`: Status,
        ) : TheListFieldUnion

        @JvmInline
        @Serializable
        @AvroGenerated("""{"type":"array","items":"string"}""")
        public value class ForArray(
            public val `value`: List<String>,
        ) : TheListFieldUnion
    }
}
