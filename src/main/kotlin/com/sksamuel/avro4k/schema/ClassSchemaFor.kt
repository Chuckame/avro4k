package com.sksamuel.avro4k.schema

import com.sksamuel.avro4k.AnnotationExtractor
import com.sksamuel.avro4k.Avro
import com.sksamuel.avro4k.AvroProp
import com.sksamuel.avro4k.RecordNaming
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.modules.SerializersModule
import org.apache.avro.JsonProperties
import org.apache.avro.Schema
import org.apache.avro.SchemaBuilder

@ExperimentalSerializationApi
class ClassSchemaFor(
   private val descriptor: SerialDescriptor,
   private val namingStrategy: NamingStrategy,
   private val serializersModule: SerializersModule,
   private val resolvedSchemas: MutableMap<String, Schema>
) : SchemaFor {

   private val entityAnnotations = AnnotationExtractor(descriptor.annotations)
   private val naming = RecordNaming(descriptor)
   private val json by lazy {
      Json{
         serializersModule = this@ClassSchemaFor.serializersModule
      }
   }

   override fun schema(): Schema {
      // if the class is annotated with @AvroInline then we need to encode the single field
      // of that class directly.
      return when (entityAnnotations.valueType()) {
         true -> valueTypeSchema()
         false -> dataClassSchema()
      }
   }

   private fun valueTypeSchema(): Schema {
      require(descriptor.elementsCount == 1) { "A value type must only have a single field" }
      return buildField(0).schema()
   }

   private fun dataClassSchema(): Schema {
      val fullName = naming.qualifiedName()

      // return schema if already defined, even without fields
      resolvedSchemas[fullName]?.let { return it }

      val record = Schema.createRecord(naming.name(), entityAnnotations.doc(), naming.namespace(), false)

      // add partially defined schema right now, so that fields could recursively use it
      resolvedSchemas[fullName] = record

      val fields = (0 until descriptor.elementsCount)
         .map { index -> buildField(index) }

      record.fields = fields
      entityAnnotations.aliases().forEach { record.addAlias(it) }
      entityAnnotations.props().forEach { (k, v) -> record.addProp(k, v) }

      // clean up classSchemaBag
      resolvedSchemas.remove(fullName)

      return record
   }

   private fun buildField(index: Int): Schema.Field {

      val fieldDescriptor = descriptor.getElementDescriptor(index)
      val annos = AnnotationExtractor(descriptor.getElementAnnotations(index))
      val fieldNaming = RecordNaming(descriptor, index)
      val schema = schemaFor(
         serializersModule,
         fieldDescriptor,
         descriptor.getElementAnnotations(index),
         namingStrategy,
         resolvedSchemas
      ).schema()

      // if we have annotated the field @AvroFixed then we override the type and change it to a Fixed schema
      // if someone puts @AvroFixed on a complex type, it makes no sense, but that's their cross to bear
      // in addition, someone could annotate the target type, so we need to check into that too
      val (size, name) = when (val a = annos.fixed()) {
         null -> {
            val fieldAnnos = AnnotationExtractor(fieldDescriptor.annotations)
            val n = RecordNaming(fieldDescriptor)
            when (val b = fieldAnnos.fixed()) {
               null -> 0 to n.name()
               else -> b to n.name()
            }
         }
         else -> a to fieldNaming.name()
      }

      val schemaOrFixed = when (size) {
         0 -> schema
         else ->
            SchemaBuilder.fixed(name)
               .doc(annos.doc())
               .namespace(annos.namespace() ?: naming.namespace())
               .size(size)
      }

      // the field can override the containingNamespace if the Namespace annotation is present on the field
      // we may have annotated our field with @AvroNamespace so this containingNamespace should be applied
      // to any schemas we have generated for this field
      val schemaWithResolvedNamespace = when (val ns = annos.namespace()) {
         null -> schemaOrFixed
         else -> schemaOrFixed.overrideNamespace(ns)
      }

      val default: Any? = annos.default()?.let {
         when {
             it == Avro.NULL -> Schema.Field.NULL_DEFAULT_VALUE
             schemaWithResolvedNamespace.extractNonNull().type in listOf(Schema.Type.FIXED, Schema.Type.BYTES, Schema.Type.STRING) -> it
             else -> json.parseToJsonElement(it).convertToAvroDefault()
         }
      }

      val field = Schema.Field(fieldNaming.name(), schemaWithResolvedNamespace, annos.doc(), default)
      val props = this.descriptor.getElementAnnotations(index).filterIsInstance<AvroProp>()
      props.forEach { field.addProp(it.key, it.value) }
      annos.aliases().forEach { field.addAlias(it) }

      return field
   }

   private fun JsonElement.convertToAvroDefault() : Any{
      return when(this){
         is JsonNull -> JsonProperties.NULL_VALUE
         is JsonObject -> this.map { Pair(it.key,it.value.convertToAvroDefault()) }.toMap()
         is JsonArray -> this.map { it.convertToAvroDefault() }.toList()
         is JsonPrimitive -> when {
             this.isString -> this.content
             this.booleanOrNull != null -> this.boolean
             else -> {
                 val number = this.content.toBigDecimal()
                 if(number.scale() <= 0){
                    number.toBigInteger()
                 }else{
                    number
                 }
             }
         }
      }
   }


}
