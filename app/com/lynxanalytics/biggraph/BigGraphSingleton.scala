package com.lynxanalytics.biggraph

import java.io.File
import org.apache.spark

/*
 * This object is used to initialize common state shared by multiple controllers.
 * TODO: figure out what is the best practice for this in play.
 */
object BigGraphSingleton {
  // TODO: make all this more production like and configurable.
  // Btw, it sucks that you need to specify the jar even in local mode. Not sure why. For now,
  // one need to do sbt package before sbt run. :(
  lazy val sparkContext = new spark.SparkContext("local", "BigGraphSingleton", "", Seq("target/scala-2.10/biggraph_2.10-0.1-SNAPSHOT.jar"))

  private val sysTempDir = System.getProperty("java.io.tmpdir")
  private val myTempDir = new File(
      "%s/%s-%d".format(sysTempDir, getClass.getName, scala.compat.Platform.currentTime))
  myTempDir.mkdir
  private val graphDir = new File(myTempDir, "graph")
  graphDir.mkdir
  private val dataDir = new File(myTempDir, "data")
  dataDir.mkdir

  lazy val bigGraphManager = graph_api.BigGraphManager(graphDir.toString)
  lazy val graphDataManager = graph_api.GraphDataManager(sparkContext, dataDir.toString)
}
