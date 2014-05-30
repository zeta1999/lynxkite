package com.lynxanalytics

import org.slf4j.LoggerFactory
import scala.reflect.runtime.universe._

package object biggraph {
  val bigGraphLogger = LoggerFactory.getLogger("BigGraph backend")

  scala.reflect.runtime.universe

  def printType[T: TypeTag]: Unit = bigGraphLogger.debug("initialize reflection for type: " + typeOf[T])

  printType[Long]
  printType[String]
  printType[Double]
  printType[Array[Long]]

  // static<hostname_of_master>
  // We just connect to a standing spark cluster, no resize support.
  private val staticPattern = "static<(.+)>".r

  // standingGCE<name_of_cluster>
  // We just connect to an already initiated spark cluster running on Google Compute Engine.
  // Supports resizing.
  private val standingGCEPattern = "standingGCE<(.+)>".r

  // newGCE<name_of_cluster>
  // We need to create a new spark cluster running on Google Compute Engine.
  // Supports resizing.
  private val newGCEPattern = "newGCE<(.+)>".r

  lazy val BigGraphProductionEnvironment: BigGraphEnvironment =
    scala.util.Properties.envOrElse("SPARK_CLUSTER_MODE", "static<local>") match {
      case staticPattern(master) =>
        new StaticSparkContextProvider(master) with TemporaryDirEnvironment
      case standingGCEPattern(clusterName) =>
        new spark_util.GCEManagedCluster(clusterName, "BigGraphServer", true) with TemporaryDirEnvironment
      case newGCEPattern(clusterName) =>
        new spark_util.GCEManagedCluster(clusterName, "BigGraphServer", false) with TemporaryDirEnvironment
    }
}
