package com.lynxanalytics.biggraph.graph_api

import java.io.File
import java.util.UUID
import org.scalatest.FunSuite

import com.lynxanalytics.biggraph.TestUtils

class MetaGraphManagerTest extends FunSuite with TestMetaGraphManager {
  test("Basic application flow works as expected.") {
    val manager = cleanMetaManager

    // We can add two dependent operation.
    val firstInstance = manager.apply(new CreateSomeGraph())
    val firstVertices = firstInstance.outputs.vertexSets('vertices)
    val firstEdges = firstInstance.outputs.edgeBundles('edges)
    val firstVattr = firstInstance.outputs.vertexAttributes('vattr)
    val firstEattr = firstInstance.outputs.edgeAttributes('eattr)

    val secondInstance = manager.apply(
      new FromVertexAttr(),
      MetaDataSet(
        vertexSets = Map('inputVertices -> firstVertices),
        vertexAttributes = Map('inputAttr -> firstVattr)))
    val secondAttrValues = secondInstance.outputs.vertexSets('attrValues)
    val secondLinks = secondInstance.outputs.edgeBundles('links)

    // All entities of both operations are available in the manager by guid.
    firstInstance.entities.all.values.foreach { entity =>
      assert(manager.entity(entity.gUID) == entity)
    }
    secondInstance.entities.all.values.foreach { entity =>
      assert(manager.entity(entity.gUID) == entity)
    }

    // VertexSets and EdgeBundles are linked as expected.
    assert(firstEdges.srcVertexSet == firstVertices)
    assert(firstEdges.dstVertexSet == firstVertices)
    assert(secondLinks.srcVertexSet == secondAttrValues)
    assert(secondLinks.dstVertexSet == firstVertices)
    assert(manager.incomingBundles(firstVertices).toSet == Set(firstEdges, secondLinks))
    assert(manager.outgoingBundles(firstVertices).toSet == Set(firstEdges))
    assert(manager.incomingBundles(secondAttrValues).toSet == Set())
    assert(manager.outgoingBundles(secondAttrValues).toSet == Set(secondLinks))

    // Properties are linked as expected.
    assert(firstVattr.vertexSet == firstVertices)
    assert(firstEattr.edgeBundle == firstEdges)
    assert(manager.attributes(firstVertices).toSet == Set(firstVattr))
    assert(manager.attributes(firstEdges).toSet == Set(firstEattr))

    // Dependent operations linked as expected.
    assert(manager.dependentOperations(firstVertices).toSet == Set(secondInstance))
    assert(manager.dependentOperations(firstVattr).toSet == Set(secondInstance))
  }

  test("Sometimes, there is no such component") {
    val manager = cleanMetaManager
    val instance = manager.apply(new CreateSomeGraph())
    intercept[java.util.NoSuchElementException] {
      manager.entity(new UUID(0, 0))
    }
  }

  test("Save and load works") {
    val m1o = cleanMetaManager
    val m2o = cleanMetaManager

    val firstInstance = m1o.apply(new CreateSomeGraph())
    val firstVertices = firstInstance.outputs.vertexSets('vertices)
    val firstVattr = firstInstance.outputs.vertexAttributes('vattr)
    val secondInstance = m1o.apply(
      new FromVertexAttr(),
      MetaDataSet(
        vertexSets = Map('inputVertices -> firstVertices),
        vertexAttributes = Map('inputAttr -> firstVattr)))

    m1o.setTag("my/favorite/vertices/first", firstVertices)

    val m1c = new MetaGraphManager(m1o.repositoryPath)

    (firstInstance.entities.all.values ++ secondInstance.entities.all.values).foreach { entity =>
      // We have an entity of the GUID of all entities.
      val clonedEntity = m1c.entity(entity.gUID)
      // They look similar.
      assert(clonedEntity.getClass == entity.getClass)
      // But they are not the same!
      assert(clonedEntity ne entity)
      // Nothing leaked over to an unrelated manager.
      intercept[java.util.NoSuchElementException] {
        m2o.entity(entity.gUID)
      }
    }

    assert(m1c.vertexSet("my/favorite/vertices/first").gUID == firstVertices.gUID)
  }

  test("No operation should be calculated twice") {
    val manager = cleanMetaManager
    val instance1 = manager.apply(new CreateSomeGraph())
    val instance2 = manager.apply(new CreateSomeGraph())
    assert(instance1 eq instance2)
  }
}

private case class CreateSomeGraph() extends MetaGraphOperation {
  def signature = newSignature
    .outputGraph('vertices, 'edges)
    .outputVertexAttribute[Long]('vattr, 'vertices)
    .outputEdgeAttribute[String]('eattr, 'edges)

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = ???
}

private case class FromVertexAttr() extends MetaGraphOperation {
  def signature = newSignature
    .inputVertexSet('inputVertices)
    .inputVertexAttribute[Long]('inputAttr, 'inputVertices)
    .outputVertexSet('attrValues)
    .outputEdgeBundle('links, 'attrValues -> 'inputVertices)

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = ???
}
