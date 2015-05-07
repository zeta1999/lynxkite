// Estimates Harmonic Centrality for each vertex using the HyperBall algorithm.
// http://vigna.di.unimi.it/ftp/papers/HyperBall.pdf
// HyperBall uses HyperLogLog counters to estimate sizes of large sets, so
// the centrality values calculated here are approximations. Note that this
// algorithm does not take weights or parallel edges into account.
package com.lynxanalytics.biggraph.graph_operations

import scala.annotation.tailrec

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark._

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.SortedRDD

import com.twitter.algebird.HyperLogLogMonoid
import com.twitter.algebird.HLL
import com.twitter.algebird.HyperLogLog._

object HyperBallCentrality extends OpFromJson {
  class Input extends MagicInputSignature {
    val (vs, es) = graph
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val centrality = vertexAttribute[Double](inputs.vs.entity)
  }
  def fromJson(j: JsValue) = HyperBallCentrality(
    (j \ "maxDiameter").as[Int],
    (j \ "algorithm").as[String])
}
import HyperBallCentrality._
case class HyperBallCentrality(maxDiameter: Int, algorithm: String)
    extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj("maxDiameter" -> maxDiameter, "algorithm" -> algorithm)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val vertices = inputs.vs.rdd
    val vertexPartitioner = vertices.partitioner.get
    val edges = inputs.es.rdd.map { case (id, edge) => (edge.src, edge.dst) }
      .groupBySortedKey(vertexPartitioner).cache()
    // Hll counters are used to estimate set sizes.
    val globalHll = new HyperLogLogMonoid(bits = 8)

    val centralities = getCentralities(
      diameter = 1,
      centralities = vertices.mapValues { _ => 0.0 },
      hyperBallCounters = vertices.mapValuesWithKeys {
        // Initialize a counter for every vertex 
        case (vid, _) => globalHll(vid)
      },
      // We have to keep track of the HyperBall sizes for the actual
      // and the previous diameter.
      hyperBallSizes = vertices.mapValues { _ => (1, 1) },
      vertexPartitioner,
      edges,
      globalHll)
    output(o.centrality, centralities)
  }

  @tailrec private def getCentralities(
    diameter: Int,
    centralities: SortedRDD[ID, Double],
    hyperBallCounters: SortedRDD[ID, HLL],
    hyperBallSizes: SortedRDD[ID, (Int, Int)],
    vertexPartitioner: Partitioner,
    edges: SortedRDD[ID, Iterable[ID]],
    globalHll: HyperLogLogMonoid): SortedRDD[ID, Double] = {

    val newHyperBallCounters = getNextHyperBalls(
      hyperBallCounters, vertexPartitioner, edges).cache()
    val newHyperBallSizes = hyperBallSizes.sortedJoin(newHyperBallCounters).mapValues {
      case ((_, newValue), hll) =>
        (newValue, hll.estimatedSize.toInt)
    }
    val newCentralities = centralities
      .sortedJoin(newHyperBallSizes)
      .mapValues {
        case (original, (oldSize, newSize)) => {
          original + ((newSize - oldSize).toDouble / diameter)
        }
      }

    if (diameter < maxDiameter) {
      getCentralities(diameter + 1, newCentralities,
        newHyperBallCounters, newHyperBallSizes, vertexPartitioner, edges, globalHll)
    } else {
      newCentralities
    }
  }

  /** Returns hyperBallCounters for a diameter increased with 1.*/
  private def getNextHyperBalls(
    hyperBallCounters: SortedRDD[ID, HLL],
    vertexPartitioner: Partitioner,
    edges: SortedRDD[ID, Iterable[ID]]): SortedRDD[ID, HLL] = {
    // Aggregate the Hll counters for every neighbor.
    (hyperBallCounters
      .sortedJoin(edges)
      .flatMap {
        case (id, (hll, neighbors)) => neighbors.map(nid => (nid, hll))
        // Add the original Hlls.
      } ++ hyperBallCounters)
      // Note that the + operator is defined on Algebird's HLL.
      .reduceBySortedKey(vertexPartitioner, _ + _)
  }
}

