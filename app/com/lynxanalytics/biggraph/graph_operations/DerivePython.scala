// Creates new attributes using Python executed on Sphynx.
package com.lynxanalytics.biggraph.graph_operations

import play.api.libs.json

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

import org.apache.spark

object DerivePython extends OpFromJson {
  import scala.language.existentials
  case class Field(parent: String, name: String, tpe: SerializableType[_]) {
    def fullName = Symbol(s"$parent.$name")
  }
  implicit val fField = new json.Format[Field] {
    def reads(j: json.JsValue): json.JsResult[Field] =
      json.JsSuccess(Field(
        (j \ "parent").as[String],
        (j \ "name").as[String],
        SerializableType.fromJson(j \ "tpe")))
    def writes(f: Field): json.JsValue =
      json.Json.obj("parent" -> f.parent, "name" -> f.name, "tpe" -> f.tpe.toJson)
  }

  class Input(fields: Seq[Field]) extends MagicInputSignature {
    val (scalarFields, attrFields) = fields.partition(_.parent == "scalars")
    val vss = attrFields.map(f => f.parent -> vertexSet(Symbol(f.parent))).toMap
    val attrs = attrFields.map(f =>
      runtimeTypedVertexAttribute(vss(f.parent), f.fullName, f.tpe.typeTag))
    val scalars = scalarFields.map(f => runtimeTypedScalar(f.fullName, f.tpe.typeTag))
  }
  class Output(implicit
      instance: MetaGraphOperationInstance,
      inputs: Input, fields: Seq[Field]) extends MagicOutput(instance) {
    val (scalarFields, attrFields) = fields.partition(_.parent == "scalars")
    val attrs = attrFields.map(f =>
      vertexAttribute(inputs.vss(f.parent).entity, f.fullName)(f.tpe.typeTag))
    val scalars = scalarFields.map(f => scalar(f.fullName)(f.tpe.typeTag))
  }

  private def toSerializableType(pythonType: String) = {
    pythonType match {
      case "str" => SerializableType.string
      case "float" => SerializableType.double
      case _ => throw new AssertionError(s"Unknown type: $pythonType")
    }
  }

  def run(code: String, project: com.lynxanalytics.biggraph.controllers.ProjectEditor)(implicit manager: MetaGraphManager): Unit = {
    val existingFields = project.vertexAttributes.map {
      case (name, attr) => Field("vs", name, SerializableType(attr.typeTag))
    } ++ project.edgeAttributes.map {
      case (name, attr) => Field("es", name, SerializableType(attr.typeTag))
    } ++ project.scalars.map {
      case (name, s) => Field("scalars", name, SerializableType(s.typeTag))
    }
    val api = Seq("vs", "es", "scalars")
    val outputFields = api.flatMap { parent =>
      s"$parent\\.(\\w+) *: *(\\w+)".r.findAllMatchIn(code).map { m =>
        Field(parent, m.group(1), toSerializableType(m.group(2)))
      }
    }
    val inputFields = api.flatMap { parent =>
      val refs = s"$parent\\.(\\w+)".r.findAllMatchIn(code).map(_.group(1))
      refs.flatMap(r =>
        existingFields.find(f => f.parent == parent && f.name == r))
    }
    val op = DerivePython(code, inputFields, outputFields)
    import Scripting._
    val builder = InstanceBuilder(op)
    for ((f, i) <- op.attrFields.zipWithIndex) {
      val attr = f.parent match {
        case "vs" => project.vertexAttributes(f.name)
        case "es" => project.edgeAttributes(f.name)
      }
      builder(op.attrs(i), attr)
      builder(op.vss(f.parent), attr.vertexSet)
    }
    for ((f, i) <- op.scalarFields.zipWithIndex) {
      builder(op.scalars(i), project.scalars(f.name))
    }
    builder.toInstance(manager)
    val res = builder.result
    for ((f, i) <- res.attrFields.zipWithIndex) {
      f.parent match {
        case "vs" => project.newVertexAttribute(f.name, res.attrs(i))
        case "es" => project.newEdgeAttribute(f.name, res.attrs(i))
      }
    }
    for ((f, i) <- res.scalarFields.zipWithIndex) {
      project.newScalar(f.name, res.scalars(i))
    }
  }

  def fromJson(j: JsValue): TypedMetaGraphOp.Type = {
    DerivePython(
      (j \ "code").as[String],
      (j \ "inputFields").as[List[Field]],
      (j \ "outputFields").as[List[Field]])
  }
}

import DerivePython._
case class DerivePython private[graph_operations] (
    code: String,
    inputFields: Seq[Field],
    outputFields: Seq[Field])
  extends TypedMetaGraphOp[Input, Output] {
  override def toJson = Json.obj(
    "code" -> code,
    "inputFields" -> inputFields,
    "outputFields" -> outputFields)
  override lazy val inputs = new Input(inputFields)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs, outputFields)
}

