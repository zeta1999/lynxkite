package com.lynxanalytics.biggraph.graph_operations

import scala.collection.mutable
import scala.reflect.runtime.universe._
import scala.util.Random

import org.apache.spark
import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.RDDUtils

private object ConnectedComponents {
  // A "constant", but we want to use a small value in the unit tests.
  var maxEdgesProcessedLocally = 20000000
}
case class ConnectedComponents() extends MetaGraphOperation {
  def signature = newSignature
    .inputGraph('vs, 'es)
    .outputVertexSet('cc)
    .outputEdgeBundle('link, 'vs -> 'cc)

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = {
    val partitioner = rc.defaultPartitioner
    val inputEdges = inputs.edgeBundles('es).rdd.values
      .map(edge => (edge.src, edge.dst))
    // Graph as edge lists. Does not include degree-0 vertices.
    val graph = inputEdges.groupByKey(partitioner).mapValues(_.toSet)
    val edges = getComponents(graph, 0).map { case(vId, cId) => Edge(vId, cId) }
    outputs.putEdgeBundle('link, RDDUtils.fastNumbered(edges))
  }

  type ComponentID = ID

  def getComponents(
    graph: RDD[(ID, Set[ID])], iteration: Int): RDD[(ID, ComponentID)] = {
    // Need to take a count of edges, and then operate on the graph.
    // We best cache it here.
    graph.persist(StorageLevel.MEMORY_AND_DISK)
    if (graph.count == 0) {
      return graph.sparkContext.emptyRDD[(ID, ComponentID)]
    }
    val edgeCount = graph.map(_._2.size).reduce(_ + _)
    if (edgeCount <= ConnectedComponents.maxEdgesProcessedLocally) {
      return getComponentsLocal(graph)
    } else {
      return getComponentsDist(graph, iteration)
    }
  }

  def getComponentsDist(graph: RDD[(ID, Set[ID])], iteration: Int): RDD[(ID, ComponentID)] = {

    val partitioner = graph.partitioner.get

    // Each node decides if it is hosting a party or going out as a guest.
    val invitations = graph.mapPartitionsWithIndex {
      case (pidx, it) =>
        val rnd = new Random((pidx << 16) + iteration)
        it.flatMap {
          case (n, edges) =>
            if (rnd.nextBoolean()) { // Host. All the neighbors are invited.
              (edges.map((_, n)) +
                ((n, -1l))) // -1 is note to self: stay at home, host party.
            } else { // Guest. Can always stay at home at least.
              Seq((n, n))
            }
        }
    }

    // Accept invitations.
    val moves = invitations.groupByKey(partitioner).mapPartitions(pit =>
      pit.map {
        case (n, invIt) =>
          val invSeq = invIt.toSeq.sorted
          if (invSeq.size == 1 || invSeq.contains(-1l)) {
            // Nowhere to go, or we are hosting a party. Stay at home.
            (n, n)
          } else {
            // Free to go. Go somewhere else.
            (n, invSeq.find(_ != n).get)
          }
      },
      preservesPartitioning = true).persist(StorageLevel.MEMORY_AND_DISK)

    // Update edges. First, update the source (n->c) and flip the direction.
    val halfDone = graph.join(moves).flatMap({
      case (n, (edges, c)) =>
        edges.map((_, c))
    }).groupByKey(partitioner).mapValues(_.toSet)
    // Second, update the source, and merge the edge lists.
    val almostDone = halfDone.join(moves).map({
      case (n, (edges, c)) =>
        (c, edges)
    }).groupByKey(partitioner).mapPartitions(pit =>
      pit.map {
        case (c, edgesList) =>
          (c, edgesList.toSet.flatten - c)
      },
      preservesPartitioning = true)

    // Third, remove finished components.
    val newGraph = almostDone.filter({ case (n, edges) => edges.nonEmpty })
    // Recursion.
    val newComponents: RDD[(ID, ComponentID)] = getComponents(newGraph, iteration + 1)
    // We just have to map back the component IDs to the vertices.
    val reverseMoves = moves.map({ case (n, party) => (party, n) })
    val parties = reverseMoves.groupByKey(partitioner)
    val components = parties.leftOuterJoin(newComponents).flatMap({
      case (party, (guests, component)) =>
        guests.map((_, component.getOrElse(party)))
    }).partitionBy(partitioner)
    components
  }

  def getComponentsLocal(
    graphRDD: RDD[(ID, Set[ID])]): RDD[(ID, ComponentID)] = {
    // Moves all the data to the driver and processes it there.
    val p = graphRDD.collect

    val graph = p.toMap
    val components = mutable.Map[ID, ComponentID]()
    var idx = 0
    // Breadth-first search.
    for (node <- graph.keys) {
      if (!components.contains(node)) {
        components(node) = node
        val todo = mutable.Queue(node)
        while (todo.size > 0) {
          val v = todo.dequeue()
          for (u <- graph(v)) {
            if (!components.contains(u)) {
              components(u) = node
              todo.enqueue(u)
            }
          }
        }
      }
    }
    assert(components.size == graph.size, s"${components.size} != ${graph.size}")

    graphRDD.sparkContext.parallelize(components.toSeq).partitionBy(graphRDD.partitioner.get)
  }
}
