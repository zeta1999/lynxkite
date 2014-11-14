package com.lynxanalytics.biggraph.graph_api

import org.scalatest.FunSuite
import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.TestUtils
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_operations.ExampleGraph
import com.lynxanalytics.biggraph.graph_util.Filename

class DataManagerTest extends FunSuite with TestMetaGraphManager with TestDataManager {
  test("We can obtain a simple new graph") {
    val metaManager = cleanMetaManager
    val dataManager = cleanDataManager
    val instance = metaManager.apply(ExampleGraph(), MetaDataSet())

    assert(TestUtils.RDDToSortedString(
      dataManager.get(instance.outputs.vertexSets('vertices)).rdd) ==
      "(0,())\n" +
      "(1,())\n" +
      "(2,())\n" +
      "(3,())")
    assert(TestUtils.RDDToSortedString(
      dataManager.get(instance.outputs.vertexAttributes('name)).rdd) ==
      "(0,Adam)\n" +
      "(1,Eve)\n" +
      "(2,Bob)\n" +
      "(3,Isolated Joe)")
    assert(TestUtils.RDDToSortedString(
      dataManager.get(instance.outputs.vertexAttributes('age)).rdd) ==
      "(0,20.3)\n" +
      "(1,18.2)\n" +
      "(2,50.3)\n" +
      "(3,2.0)")

    assert(TestUtils.RDDToSortedString(
      dataManager.get(instance.outputs.edgeBundles('edges)).rdd) ==
      "(0,Edge(0,1))\n" +
      "(1,Edge(1,0))\n" +
      "(2,Edge(2,0))\n" +
      "(3,Edge(2,1))")
    assert(TestUtils.RDDToSortedString(
      dataManager.get(instance.outputs.vertexAttributes('comment)).rdd) ==
      "(0,Adam loves Eve)\n" +
      "(1,Eve loves Adam)\n" +
      "(2,Bob envies Adam)\n" +
      "(3,Bob loves Eve)")
    assert(dataManager.get(instance.outputs.scalars('greeting)).value == "Hello world!")
  }

  test("We can reload a graph from disk without recomputing it") {
    val metaManager = cleanMetaManager
    val dataManager1 = cleanDataManager
    val dataManager2 = new DataManager(sparkContext, dataManager1.repositoryPath)
    val operation = ExampleGraph()
    val instance = metaManager.apply(operation)
    val names = instance.outputs.vertexAttributes('name).runtimeSafeCast[String]
    val greeting = instance.outputs.scalars('greeting).runtimeSafeCast[String]
    val data1: VertexAttributeData[String] = dataManager1.get(names)
    val scalarData1: ScalarData[String] = dataManager1.get(greeting)
    val data2 = dataManager2.get(names)
    val scalarData2 = dataManager2.get(greeting)
    assert(data1 ne data2)
    assert(TestUtils.RDDToSortedString(data1.rdd) ==
      TestUtils.RDDToSortedString(data2.rdd))
    assert(scalarData1 ne scalarData2)
    assert(scalarData1.value == scalarData2.value)
    assert(operation.executionCounter == 1)
  }

  test("We can compute a graph whose meta was loaded from disk") {
    val metaManager = cleanMetaManager
    val dataManager = cleanDataManager
    val operation = ExampleGraph()
    val instance = metaManager.apply(operation)
    val ageGUID = instance.outputs.vertexAttributes('age).gUID
    val reloadedMetaManager = new MetaGraphManager(metaManager.repositoryPath)
    val reloadedAge = reloadedMetaManager.vertexAttribute(ageGUID).runtimeSafeCast[Double]
    assert(TestUtils.RDDToSortedString(dataManager.get(reloadedAge).rdd) ==
      "(0,20.3)\n" +
      "(1,18.2)\n" +
      "(2,50.3)\n" +
      "(3,2.0)")
  }

  test("No infinite recursion even when there is recursive dependency between operations") {
    // In a previous implementation we've seen an infinite recursion in the data manager
    // due to a kind of circular dependency between the operations ImportEdgeList and the
    // implicitly created EdgeBundleAsVertexSet operation. This is how the circular dependency
    // goeas:
    //  - EdgeBundleAsVertexSet takes as input the edge bundle output of ImportEdgeList
    //  - ImportEdgeList outputs edge attributes. When loading those, we depend on the id set
    //    of those attributes, which in this case is the output of EdgeBundleAsVertexSet
    // This DataManager got into an infinite recursion trying to provide alternatingly the inputs
    // for these two operations.
    //
    // To actually trigger this bug for sure, you need to be in a special case where the edge
    // attribute is already saved to disk but the edge bundle is not. If nothing is on disk,
    // the operation will run, save everything and load back results one by one. If it loads
    // the edge bundle before the edge attribute, then no problem happens.
    // On the other hand, if everything is already on disk, then the ImportEdgeList operation
    // never even triggers, so again, no problem happens.
    //
    // Anyways, this test was able to reproduce the issue and is here to ensure that this daemon
    // does not ever come back.
    implicit val metaManager = cleanMetaManager
    val dataManager = cleanDataManager
    import Scripting._

    val testCSVFile = Filename(myTempDir.toString) / "almakorte.csv"
    testCSVFile.createFromStrings("alma,korte,barack\n3,4,5\n")
    val operation = graph_operations.ImportEdgeList(
      graph_operations.CSV(testCSVFile, ",", "alma,korte,barack"),
      "alma",
      "korte")
    val imported = operation().result
    val barack = imported.attrs("barack").entity

    // Fake barack being on disk.
    val entityPath = dataManager.repositoryPath / "entities" / barack.gUID.toString
    val instancePath = dataManager.repositoryPath / "operations" / barack.source.gUID.toString
    def fakeSuccess(path: Filename): Unit = {
      val successPath = path / "_SUCCESS"
      path.mkdirs
      successPath.createFromStrings("")
    }
    fakeSuccess(entityPath)
    fakeSuccess(instancePath)

    // Check that we managed to fake.
    assert(dataManager.isCalculated(barack))

    // And now we get the future for it, this should not stack overflow or anything evil.
    dataManager.get(barack)
  }

  test("Failed operation can be retried") {
    implicit val metaManager = cleanMetaManager
    val dataManager = cleanDataManager
    import Scripting._

    val testfile = Filename(myTempDir.toString) / "test.csv"
    testfile.delete()
    val imported = graph_operations.ImportEdgeList(
      graph_operations.CSV(testfile, ",", "src,dst"), "src", "dst")().result

    // The file does not exist, so the import fails.
    val e = intercept[java.util.concurrent.ExecutionException] {
      dataManager.get(imported.edges)
    }
    assert(e.getCause.isInstanceOf[AssertionError])
    // Create the file.
    testfile.createFromStrings("src,dst\n1,2\n")
    // The result can be accessed now.
    assert(TestUtils.RDDToSortedString(
      dataManager.get(imported.stringID).rdd.values) == "1\n2")
  }
}
