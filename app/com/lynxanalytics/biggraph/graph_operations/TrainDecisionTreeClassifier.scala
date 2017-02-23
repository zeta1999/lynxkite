// Trains a decision tree classification model.
package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.model._
import org.apache.spark.ml

object TrainDecisionTreeClassifier extends OpFromJson {
  class Input(numFeatures: Int) extends MagicInputSignature {
    val vertices = vertexSet
    val features = (0 until numFeatures).map {
      i => vertexAttribute[Double](vertices, Symbol(s"feature-$i"))
    }
    val label = vertexAttribute[Double](vertices)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
  inputs: Input) extends MagicOutput(instance) {
    val model = scalar[Model]
  }
  def fromJson(j: JsValue) = TrainDecisionTreeClassifier(
    (j \ "labelName").as[String],
    (j \ "featureNames").as[List[String]],
    (j \ "maxDepth").as[Int],
    (j \ "minInfoGain").as[Double],
    (j \ "minInstancesPerNode").as[Int])
}

import TrainDecisionTreeClassifier._
case class TrainDecisionTreeClassifier(
  ) extends TypedMetaGraphOp[Input, Output] with ModelMeta {
    val isClassification = true
    override val generatesProbability = true
    override val isHeavy = true
    @transient override lazy val inputs = new Input(featureNames.size)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj(
    "labelName" -> labelName,
    "featureNames" -> featureNames)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val sqlContext = rc.dataManager.newSQLContext()
    import sqlContext.implicits._

    val featuresRddArray = inputs.features.toArray.map(_.rdd)
    val labelDF = inputs.label.rdd.toDF("id", "label")
    val featuresDF = Model.toDF(sqlContext, inputs.vertices.rdd, featuresRddArray)
    val labeledFeaturesDF = featuresDF.join(labelDF, "id")
    assert(!labeledFeaturesDF.rdd.isEmpty, "Training is not possible with empty data set.")

    val decisionTreeClassifier = new ml.classification.DecisionTreeClassifier()
    val model = decisionTreeClassifier.fit(labeledFeaturesDF)


}