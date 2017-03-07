package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.controllers._

import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_api.GraphTestUtils._

class FingerprintingBetweenProjectAndSegmentationOperationTest extends OperationsTestBase {
  test("Fingerprinting between project and segmentation") {
    run("Example Graph")
    run("Import project as segmentation", Map(
      "them" -> s"!checkpoint(${project.checkpoint.get},ExampleGraph2)"))
    run("Import segmentation links", Map(
      "table" -> importCSV("OPERATIONSTEST$/fingerprint-example-connections.csv"),
      "base-id-attr" -> "name",
      "base-id-column" -> "src",
      "seg-id-attr" -> "name",
      "seg-id-column" -> "dst",
      "apply_to" -> "|ExampleGraph2"))
    run("Fingerprinting between project and segmentation", Map(
      "mo" -> "1",
      "ms" -> "0.5",
      "apply_to" -> "|ExampleGraph2"))
    run("Aggregate from segmentation", Map(
      "prefix" -> "seg",
      "aggregate-age" -> "average",
      "aggregate-id" -> "",
      "aggregate-name" -> "",
      "aggregate-location" -> "",
      "aggregate-gender" -> "",
      "aggregate-fingerprinting_similarity_score" -> "",
      "aggregate-income" -> "",
      "apply_to" -> "|ExampleGraph2"))
    val newAge = project.vertexAttributes("seg_age_average")
      .runtimeSafeCast[Double].rdd.collect.toSeq.sorted
    // Two mappings.
    assert(newAge == Seq(0 -> 20.3, 1 -> 18.2, 2 -> 50.3, 3 -> 2.0))
    val oldAge = project.vertexAttributes("age")
      .runtimeSafeCast[Double].rdd.collect.toMap
    // They map Adam to Adam, Eve to Eve.
    for ((k, v) <- newAge) {
      assert(v == oldAge(k))
    }
  }

  test("Fingerprinting between project and segmentation by attribute") {
    run("Import vertices and edges from a single table", Map(
      "table" -> importCSV("OPERATIONSTEST$/fingerprint-edges-2.csv"),
      "src" -> "src",
      "dst" -> "dst"))
    run("Aggregate edge attribute to vertices", Map(
      "prefix" -> "",
      "direction" -> "outgoing edges",
      "aggregate-src_link" -> "most_common",
      "aggregate-dst" -> "",
      "aggregate-src" -> ""))
    run("Rename vertex attribute", Map("from" -> "src_link_most_common", "to" -> "link"))
    val otherCp = project.checkpoint.get
    run("Import vertices and edges from a single table", Map(
      "table" -> importCSV("OPERATIONSTEST$/fingerprint-edges-1.csv"),
      "src" -> "src",
      "dst" -> "dst"))
    run("Import project as segmentation", Map(
      "them" -> s"!checkpoint($otherCp,other)"))
    val seg = project.segmentation("other")
    run("Define segmentation links from matching attributes", Map(
      "base-id-attr" -> "stringID",
      "seg-id-attr" -> "link",
      "apply_to" -> "|other"))
    def belongsTo = seg.belongsTo.toPairSeq
    assert(belongsTo.size == 6)
    run("Fingerprinting between project and segmentation", Map(
      "mo" -> "0",
      "ms" -> "0",
      "apply_to" -> "|other"))
    assert(belongsTo.size == 6)
    val similarity = seg.vertexAttributes("fingerprinting_similarity_score")
      .runtimeSafeCast[Double].rdd.values.collect
    assert(similarity.size == 6)
    assert(similarity.filter(_ > 0).size == 6)
  }

}
