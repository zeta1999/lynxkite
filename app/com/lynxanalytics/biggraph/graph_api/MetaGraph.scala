package com.lynxanalytics.biggraph.graph_api

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.UUID
import org.apache.spark.rdd.RDD
import scala.reflect.runtime.universe._
import scala.Symbol // There is a Symbol in the universe package too.
import scala.collection.mutable
import scala.collection.immutable.SortedMap

sealed trait MetaGraphEntity extends Serializable {
  val source: MetaGraphOperationInstance
  val name: Symbol
  // Implement from source operation's GUID, name and the actual class of this component.
  val gUID: UUID = {
    val buffer = new ByteArrayOutputStream
    val objectStream = new ObjectOutputStream(buffer)
    objectStream.writeObject(name)
    objectStream.writeObject(source.gUID)
    objectStream.writeObject(this.getClass.toString)
    objectStream.close()
    UUID.nameUUIDFromBytes(buffer.toByteArray)
  }
  override def toString = toStringStruct.toString
  def toStringStruct = StringStruct(name.name, Map("" -> source.toStringStruct))
}
case class StringStruct(name: String, contents: SortedMap[String, StringStruct] = SortedMap()) {
  override def toString = {
    val stuff = contents.map {
      case (k, v) =>
        val s = v.toString
        val guarded = if (s.contains(" ")) s"($s)" else s
        if (k.isEmpty) guarded else s"$k=$guarded"
    }.mkString(" ")
    if (stuff.isEmpty) name
    else s"$name of $stuff"
  }
}
object StringStruct {
  def apply(name: String, contents: Map[String, StringStruct]) =
    new StringStruct(name, SortedMap[String, StringStruct]() ++ contents)
}

case class VertexSet(source: MetaGraphOperationInstance,
                     name: Symbol) extends MetaGraphEntity

case class EdgeBundle(source: MetaGraphOperationInstance,
                      name: Symbol,
                      srcVertexSet: VertexSet,
                      dstVertexSet: VertexSet) extends MetaGraphEntity {
  val isLocal = srcVertexSet == dstVertexSet
}

sealed trait Attribute[T] extends MetaGraphEntity {
  val typeTag: TypeTag[T]
  def runtimeSafeCast[S: TypeTag]: Attribute[S]
  def is[S: TypeTag] = {
    implicit val tt = typeTag
    typeOf[S] =:= typeOf[T]
  }
}

// Marker trait for possible attributes of a triplet. It's either a vertex attribute
// belonging to the source vertex, a vertex attribute belonging to the destination vertex
// or an edge attribute.
sealed trait TripletAttribute[T]

case class VertexAttribute[T: TypeTag](source: MetaGraphOperationInstance,
                                       name: Symbol,
                                       vertexSet: VertexSet)
    extends Attribute[T] with RuntimeSafeCastable[T, VertexAttribute] {
  val typeTag = implicitly[TypeTag[T]]
}

case class SrcAttr[T](attr: VertexAttribute[T]) extends TripletAttribute[T]
case class DstAttr[T](attr: VertexAttribute[T]) extends TripletAttribute[T]

case class EdgeAttribute[T: TypeTag](source: MetaGraphOperationInstance,
                                     name: Symbol,
                                     edgeBundle: EdgeBundle)
    extends Attribute[T] with RuntimeSafeCastable[T, EdgeAttribute] with TripletAttribute[T] {
  val typeTag = implicitly[TypeTag[T]]
}

case class Scalar[T: TypeTag](source: MetaGraphOperationInstance,
                              name: Symbol)
    extends MetaGraphEntity with RuntimeSafeCastable[T, Scalar] {
  val typeTag = implicitly[TypeTag[T]]
}

trait InputSignature {
  val vertexSets: Set[Symbol]
  val edgeBundles: Map[Symbol, (Symbol, Symbol)]
  val vertexAttributes: Map[Symbol, Symbol]
  val edgeAttributes: Map[Symbol, Symbol]
  val scalars: Set[Symbol]
}

case class SimpleInputSignature(
  vertexSets: Set[Symbol],
  edgeBundles: Map[Symbol, (Symbol, Symbol)],
  vertexAttributes: Map[Symbol, Symbol],
  edgeAttributes: Map[Symbol, Symbol],
  scalars: Set[Symbol]) extends InputSignature

trait MetaGraphOp extends Serializable {
  def inputSig: InputSignature
  def outputs(instance: MetaGraphOperationInstance): MetaDataSet
  def execute(
    inputDatas: DataSet,
    outputMeta: MetaDataSet,
    output: OutputBuilder,
    rc: RuntimeContext): Unit

  val gUID: UUID = {
    val buffer = new ByteArrayOutputStream
    val objectStream = new ObjectOutputStream(buffer)
    objectStream.writeObject(this)
    objectStream.close()
    UUID.nameUUIDFromBytes(buffer.toByteArray)
  }

  override def toString = toStringStruct.toString
  def toStringStruct = {
    val mirror = reflect.runtime.currentMirror.reflect(this)
    val className = mirror.symbol.name.toString
    val params = mirror.symbol.toType.members.collect { case m: MethodSymbol if m.isCaseAccessor => m }
    def get(param: MethodSymbol) = mirror.reflectField(param).get
    StringStruct(className, params.map(p => p.name.toString -> StringStruct(get(p).toString)).toMap)
  }
}

trait TypedMetaGraphOp[IS <: InputSignature, OMDS <: MetaDataSet] extends MetaGraphOp {
  def inputSig: IS
  def outputs(instance: MetaGraphOperationInstance): OMDS
}

trait MetaGraphOperation extends TypedMetaGraphOp[InputSignature, MetaDataSet] {
  // Override "signature" to easily describe the inputs and outputs of your operation. E.g.:
  //     class MyOperation extends MetaGraphOperation {
  //       def signature = newSignature
  //         .inputGraph("input-vertices", "input-edges")
  //         .outputVertexAttribute[Double]("input-vertices", "my-attribute")
  //     }
  protected def signature: MetaGraphOperationSignature
  protected def newSignature = new MetaGraphOperationSignature

  @transient lazy val inputSig = SimpleInputSignature(
    signature.inputVertexSets.toSet,
    signature.inputEdgeBundles.toMap,
    signature.inputVertexAttributes.mapValues { case (vs, tt) => vs }.toMap,
    signature.inputEdgeAttributes.mapValues { case (eb, tt) => eb }.toMap,
    signature.inputScalars.keySet.toSet)

  def outputs(instance: MetaGraphOperationInstance): MetaDataSet = {
    val outputVertexSets = signature.outputVertexSets.map(n => n -> VertexSet(instance, n)).toMap
    val allVertexSets = outputVertexSets ++ instance.inputs.vertexSets
    val outputEdgeBundles = signature.outputEdgeBundles.map {
      case (n, (svs, dvs)) => n -> EdgeBundle(instance, n, allVertexSets(svs), allVertexSets(dvs))
    }.toMap
    val allEdgeBundles = outputEdgeBundles ++ instance.inputs.edgeBundles
    val vertexAttributes =
      signature.outputVertexAttributes.map {
        case (n, (vs, tt)) => n -> VertexAttribute(instance, n, allVertexSets(vs))(tt)
      }
    val edgeAttributes = signature.outputEdgeAttributes.map {
      case (n, (eb, tt)) => n -> EdgeAttribute(instance, n, allEdgeBundles(eb))(tt)
    }
    val scalars = signature.outputScalars.map {
      case (n, tt) => n -> Scalar(instance, n)(tt)
    }
    MetaDataSet(
      outputVertexSets,
      outputEdgeBundles,
      vertexAttributes.toMap,
      edgeAttributes.toMap,
      scalars.toMap)
  }

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit

  def execute(inputDatas: DataSet,
              outputMeta: MetaDataSet,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    val builder = new DataSetBuilder(outputMeta)
    execute(inputDatas, builder, rc)
    builder.toDataSet.all.foreach { case (name, data) => output.addData(data) }
  }
}

class MetaGraphOperationSignature private[graph_api] {
  val inputVertexSets: mutable.Set[Symbol] = mutable.Set()
  val inputEdgeBundles: mutable.Map[Symbol, (Symbol, Symbol)] = mutable.Map()
  val inputVertexAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val inputEdgeAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val inputScalars: mutable.Map[Symbol, TypeTag[_]] = mutable.Map()
  val outputVertexSets: mutable.Set[Symbol] = mutable.Set()
  val outputEdgeBundles: mutable.Map[Symbol, (Symbol, Symbol)] = mutable.Map()
  val outputVertexAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val outputEdgeAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val outputScalars: mutable.Map[Symbol, TypeTag[_]] = mutable.Map()
  val allNames: mutable.Set[Symbol] = mutable.Set()
  def inputVertexSet(name: Symbol) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    inputVertexSets += name
    allNames += name
    this
  }
  def inputEdgeBundle(name: Symbol, srcDst: (Symbol, Symbol), create: Boolean = false) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    inputEdgeBundles(name) = srcDst
    val (src, dst) = srcDst
    allNames += name
    if (create) {
      inputVertexSet(src)
      inputVertexSet(dst)
    }
    this
  }
  def inputGraph(vertexSetName: Symbol, edgeBundleName: Symbol) = {
    inputVertexSet(vertexSetName)
    inputEdgeBundle(edgeBundleName, vertexSetName -> vertexSetName)
  }
  def inputVertexAttribute[T: TypeTag](attributeName: Symbol,
                                       vertexSetName: Symbol,
                                       create: Boolean = false) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    inputVertexAttributes(attributeName) = vertexSetName -> typeTag[T]
    allNames += attributeName
    if (create) {
      inputVertexSet(vertexSetName)
    }
    this
  }
  def inputEdgeAttribute[T: TypeTag](attributeName: Symbol, edgeBundleName: Symbol) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    inputEdgeAttributes(attributeName) = edgeBundleName -> typeTag[T]
    allNames += attributeName
    this
  }
  def inputScalar[T: TypeTag](name: Symbol) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    inputScalars(name) = typeTag[T]
    allNames += name
    this
  }
  def outputVertexSet(name: Symbol) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    outputVertexSets += name
    allNames += name
    this
  }
  def outputEdgeBundle(name: Symbol, srcDst: (Symbol, Symbol)) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    outputEdgeBundles(name) = srcDst
    allNames += name
    this
  }
  def outputGraph(vertexSetName: Symbol, edgeBundleName: Symbol) = {
    outputVertexSet(vertexSetName)
    outputEdgeBundle(edgeBundleName, vertexSetName -> vertexSetName)
  }
  def outputVertexAttribute[T: TypeTag](attributeName: Symbol, vertexSetName: Symbol) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    outputVertexAttributes(attributeName) = vertexSetName -> typeTag[T]
    allNames += attributeName
    this
  }
  def outputEdgeAttribute[T: TypeTag](attributeName: Symbol, edgeBundleName: Symbol) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    outputEdgeAttributes(attributeName) = edgeBundleName -> typeTag[T]
    allNames += attributeName
    this
  }
  def outputScalar[T: TypeTag](name: Symbol) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    outputScalars(name) = typeTag[T]
    allNames += name
    this
  }
}

/*
 * Base class for concrete instances of MetaGraphOperations. An instance of an operation is
 * the operation together with concrete input vertex sets and edge bundles.
 */
trait MetaGraphOperationInstance {
  val operation: MetaGraphOp

  val inputs: MetaDataSet

  val gUID: UUID = {
    val buffer = new ByteArrayOutputStream
    val objectStream = new ObjectOutputStream(buffer)
    objectStream.writeObject(operation.gUID)
    inputs.all.keys.toSeq.map(_ match { case Symbol(s) => s }).sorted.foreach { name =>
      objectStream.writeObject(name)
      objectStream.writeObject(inputs.all(Symbol(name)).gUID)
    }
    objectStream.close()
    UUID.nameUUIDFromBytes(buffer.toByteArray)
  }

  val outputs: MetaDataSet

  def entities: MetaDataSet = inputs ++ outputs

  override def toString = toStringStruct.toString
  def toStringStruct: StringStruct = {
    val op = operation.toStringStruct
    val fixed = mutable.Set[Symbol]()
    val mentioned = mutable.Map[MetaGraphEntity, Symbol]()
    val span = mutable.Map[String, StringStruct]()
    def put(k: Symbol, v: MetaGraphEntity): Unit = {
      if (!fixed.contains(k)) {
        mentioned.get(v) match {
          case Some(k0) =>
            span(k.name) = StringStruct(k0.name)
          case None =>
            span(k.name) = v.toStringStruct
            mentioned(v) = k
        }
      }
    }
    val inputSig: InputSignature = operation.inputSig
    for ((k, v) <- inputs.edgeAttributes) {
      put(k, v)
      fixed += inputSig.edgeAttributes(k)
    }
    for ((k, v) <- inputs.edgeBundles) {
      put(k, v)
      fixed += inputSig.edgeBundles(k)._1
      fixed += inputSig.edgeBundles(k)._2
    }
    for ((k, v) <- inputs.vertexAttributes) {
      put(k, v)
      fixed += inputSig.vertexAttributes(k)
    }
    for ((k, v) <- inputs.vertexSets) {
      put(k, v)
    }
    StringStruct(op.name, op.contents ++ span)
  }
}

case class TypedOperationInstance[IS <: InputSignature, OMDS <: MetaDataSet](
    operation: TypedMetaGraphOp[IS, OMDS],
    inputs: MetaDataSet) extends MetaGraphOperationInstance {
  val outputs: OMDS = operation.outputs(this)
}
case class NonTypedOperationInstance(
    operation: MetaGraphOp,
    inputs: MetaDataSet) extends MetaGraphOperationInstance {
  val outputs: MetaDataSet = operation.outputs(this)
}

sealed trait EntityData {
  val gUID: UUID
  val entity: MetaGraphEntity
}
sealed trait EntityRDDData extends EntityData {
  val rdd: RDD[_]
}
class VertexSetData(val vertexSet: VertexSet,
                    val rdd: VertexSetRDD) extends EntityRDDData {
  val gUID = vertexSet.gUID
  val entity = vertexSet
}

class EdgeBundleData(val edgeBundle: EdgeBundle,
                     val rdd: EdgeBundleRDD) extends EntityRDDData {
  val gUID = edgeBundle.gUID
  val entity = edgeBundle
}

sealed trait AttributeData[T] extends EntityRDDData {
  val typeTag: TypeTag[T]
  def runtimeSafeCast[S: TypeTag]: AttributeData[S]
  val rdd: AttributeRDD[T]
}

class VertexAttributeData[T](val vertexAttribute: VertexAttribute[T],
                             val rdd: AttributeRDD[T])
    extends AttributeData[T] with RuntimeSafeCastable[T, VertexAttributeData] {
  val typeTag = vertexAttribute.typeTag
  val gUID = vertexAttribute.gUID
  val entity = vertexAttribute
}

class EdgeAttributeData[T](val edgeAttribute: EdgeAttribute[T],
                           val rdd: AttributeRDD[T])
    extends AttributeData[T] with RuntimeSafeCastable[T, EdgeAttributeData] {
  val typeTag = edgeAttribute.typeTag
  val gUID = edgeAttribute.gUID
  val entity = edgeAttribute
}

class ScalarData[T](val scalar: Scalar[T],
                    val value: T)
    extends EntityData with RuntimeSafeCastable[T, ScalarData] {
  val typeTag = scalar.typeTag
  val gUID = scalar.gUID
  val entity = scalar
}

// A bundle of metadata types.
case class MetaDataSet(vertexSets: Map[Symbol, VertexSet] = Map(),
                       edgeBundles: Map[Symbol, EdgeBundle] = Map(),
                       vertexAttributes: Map[Symbol, VertexAttribute[_]] = Map(),
                       edgeAttributes: Map[Symbol, EdgeAttribute[_]] = Map(),
                       scalars: Map[Symbol, Scalar[_]] = Map()) {
  val all: Map[Symbol, MetaGraphEntity] =
    vertexSets ++ edgeBundles ++ vertexAttributes ++ edgeAttributes ++ scalars
  assert(all.size ==
    vertexSets.size + edgeBundles.size + vertexAttributes.size + edgeAttributes.size + scalars.size,
    "Cross type collision %s %s %s %s".format(
      vertexSets, edgeBundles, vertexAttributes, edgeAttributes))

  def apply(name: Symbol) = all(name)

  def ++(mds: MetaDataSet): MetaDataSet = {
    assert(
      (all.keySet & mds.all.keySet).forall(key => all(key).gUID == mds.all(key).gUID),
      "Collision: " + (all.keySet & mds.all.keySet).toSeq.filter(
        key => all(key).gUID != mds.all(key).gUID))
    return MetaDataSet(
      vertexSets ++ mds.vertexSets,
      edgeBundles ++ mds.edgeBundles,
      vertexAttributes ++ mds.vertexAttributes,
      edgeAttributes ++ mds.edgeAttributes,
      scalars ++ mds.scalars)
  }

  def mapNames(mapping: (Symbol, Symbol)*): MetaDataSet = {
    MetaDataSet(mapping.map {
      case (from, to) => to -> all(from)
    }.toMap)
  }

  override def toString = all.toString
}
object MetaDataSet {
  def apply(all: Map[Symbol, MetaGraphEntity]): MetaDataSet = {
    MetaDataSet(
      vertexSets = all.collect { case (k, v: VertexSet) => (k, v) },
      edgeBundles = all.collect { case (k, v: EdgeBundle) => (k, v) },
      vertexAttributes = all.collect { case (k, v: VertexAttribute[_]) => (k, v) }.toMap,
      edgeAttributes = all.collect { case (k, v: EdgeAttribute[_]) => (k, v) }.toMap,
      scalars = all.collect { case (k, v: Scalar[_]) => (k, v) }.toMap)
  }
  def applyWithSignature(signature: InputSignature,
                         all: (Symbol, MetaGraphEntity)*): MetaDataSet = {
    var res = MetaDataSet()
    def addVS(name: Symbol, vs: VertexSet) {
      assert(signature.vertexSets.contains(name), s"No such input vertex set: $name")
      res ++= MetaDataSet(vertexSets = Map(name -> vs))
    }
    def addEB(name: Symbol, eb: EdgeBundle) {
      val (srcName, dstName) = signature.edgeBundles(name)
      res ++= MetaDataSet(edgeBundles = Map(name -> eb))
      addVS(srcName, eb.srcVertexSet)
      addVS(dstName, eb.dstVertexSet)
    }
    def addVA(name: Symbol, va: VertexAttribute[_]) {
      val vsName = signature.vertexAttributes(name)
      res ++= MetaDataSet(vertexAttributes = Map(name -> va))
      addVS(vsName, va.vertexSet)
    }
    def addEA(name: Symbol, ea: EdgeAttribute[_]) {
      val ebName = signature.edgeAttributes(name)
      res ++= MetaDataSet(edgeAttributes = Map(name -> ea))
      addEB(ebName, ea.edgeBundle)
    }
    def addSC(name: Symbol, sc: Scalar[_]) {
      assert(signature.scalars.contains(name), s"No such input scalar: $name")
      res ++= MetaDataSet(scalars = Map(name -> sc))
    }

    all.foreach {
      case (name, entity) =>
        entity match {
          case vs: VertexSet => addVS(name, vs)
          case eb: EdgeBundle => addEB(name, eb)
          case va: VertexAttribute[_] => addVA(name, va)
          case ea: EdgeAttribute[_] => addEA(name, ea)
          case sc: Scalar[_] => addSC(name, sc)
        }
    }

    res
  }
}

// A bundle of data types.
case class DataSet(vertexSets: Map[Symbol, VertexSetData] = Map(),
                   edgeBundles: Map[Symbol, EdgeBundleData] = Map(),
                   vertexAttributes: Map[Symbol, VertexAttributeData[_]] = Map(),
                   edgeAttributes: Map[Symbol, EdgeAttributeData[_]] = Map(),
                   scalars: Map[Symbol, ScalarData[_]] = Map()) {
  def metaDataSet = MetaDataSet(
    vertexSets.mapValues(_.vertexSet),
    edgeBundles.mapValues(_.edgeBundle),
    vertexAttributes.mapValues(_.vertexAttribute),
    edgeAttributes.mapValues(_.edgeAttribute),
    scalars.mapValues(_.scalar))

  def all: Map[Symbol, EntityData] =
    vertexSets ++ edgeBundles ++ vertexAttributes ++ edgeAttributes ++ scalars
}

class DataSetBuilder(entities: MetaDataSet) {
  val vertexSets = mutable.Map[Symbol, VertexSetData]()
  val edgeBundles = mutable.Map[Symbol, EdgeBundleData]()
  val vertexAttributes = mutable.Map[Symbol, VertexAttributeData[_]]()
  val edgeAttributes = mutable.Map[Symbol, EdgeAttributeData[_]]()
  val scalars = mutable.Map[Symbol, ScalarData[_]]()

  def toDataSet = DataSet(vertexSets.toMap, edgeBundles.toMap, vertexAttributes.toMap, edgeAttributes.toMap, scalars.toMap)

  def putVertexSet(name: Symbol, rdd: VertexSetRDD): DataSetBuilder = {
    assert(rdd.partitioner.isDefined, s"Unpartitioned RDD: $rdd")
    vertexSets(name) = new VertexSetData(entities.vertexSets(name), rdd)
    this
  }
  def putEdgeBundle(name: Symbol, rdd: EdgeBundleRDD): DataSetBuilder = {
    assert(rdd.partitioner.isDefined, s"Unpartitioned RDD: $rdd")
    edgeBundles(name) = new EdgeBundleData(entities.edgeBundles(name), rdd)
    this
  }
  def putVertexAttribute[T: TypeTag](name: Symbol, rdd: AttributeRDD[T]): DataSetBuilder = {
    assert(rdd.partitioner.isDefined, s"Unpartitioned RDD: $rdd")
    val vertexAttribute = entities.vertexAttributes(name).runtimeSafeCast[T]
    vertexAttributes(name) = new VertexAttributeData[T](vertexAttribute, rdd)
    this
  }
  def putEdgeAttribute[T: TypeTag](name: Symbol, rdd: AttributeRDD[T]): DataSetBuilder = {
    assert(rdd.partitioner.isDefined, s"Unpartitioned RDD: $rdd")
    val edgeAttribute = entities.edgeAttributes(name).runtimeSafeCast[T]
    edgeAttributes(name) = new EdgeAttributeData[T](edgeAttribute, rdd)
    this
  }
  def putScalar[T: TypeTag](name: Symbol, value: T): DataSetBuilder = {
    scalars(name) = new ScalarData[T](entities.scalars(name).runtimeSafeCast[T], value)
    this
  }
}

class OutputBuilder(instance: MetaGraphOperationInstance) {
  val outputMeta: MetaDataSet = instance.operation.outputs(instance)
  val datas = mutable.Map[UUID, EntityData]()

  def addData(data: EntityData): Unit = {
    val gUID = data.gUID
    val entity = data.entity
    // Check that it's indeed a known output.
    assert(outputMeta.all(entity.name).gUID == entity.gUID)
    datas(gUID) = data
  }

  def addRDDData(data: EntityRDDData): Unit = {
    assert(data.rdd.partitioner.isDefined, s"Unpartitioned RDD: $data.rdd")
    addData(data)
  }

  def apply(vertexSet: VertexSet, rdd: VertexSetRDD): Unit = {
    addRDDData(new VertexSetData(vertexSet, rdd))
  }

  def apply(edgeBundle: EdgeBundle, rdd: EdgeBundleRDD): Unit = {
    addRDDData(new EdgeBundleData(edgeBundle, rdd))
  }

  def apply[T](vertexAttribute: VertexAttribute[T], rdd: AttributeRDD[T]): Unit = {
    addRDDData(new VertexAttributeData(vertexAttribute, rdd))
  }

  def apply[T](edgeAttribute: EdgeAttribute[T], rdd: AttributeRDD[T]): Unit = {
    addRDDData(new EdgeAttributeData(edgeAttribute, rdd))
  }

  def apply[T](scalar: Scalar[T], value: T): Unit = {
    addData(new ScalarData(scalar, value))
  }
}
