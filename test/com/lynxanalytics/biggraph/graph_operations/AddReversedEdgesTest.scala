package com.lynxanalytics.biggraph.graph_operations

import org.scalatest.FunSuite

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_api.GraphTestUtils._

class AddReversedEdgesTest extends FunSuite with TestGraphOp {
  test("Enhanced example graph") {
    val g = EnhancedExampleGraph()().result
    val op = AddReversedEdges()
    val out = op(op.es, g.edges).result
    val reversedEdges = g.edges.toPairSeq.map { case (a, b) => (b, a) }
    val expected = g.edges.toPairSeq ++ reversedEdges
    assert(out.esPlus.toPairSeq == expected.sorted)

    val numOrigEdges = g.edges.rdd.count()
    assert(out.attr.rdd.filter(_._2 == 0L).count == numOrigEdges)
    assert(out.attr.rdd.filter(_._2 == 1L).count == numOrigEdges)
  }
}
