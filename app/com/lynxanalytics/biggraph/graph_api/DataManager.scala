// The DataManager triggers the execution of computation for a MetaGraphEntity.
// It can schedule the execution on one of the domains it controls.
// It can return values for Scalars and information about execution status.

package com.lynxanalytics.biggraph.graph_api

import com.lynxanalytics.biggraph.graph_util.LoggedEnvironment

trait EntityProgressManager {
  case class ScalarComputationState[T](
      computeProgress: Double,
      value: Option[T],
      error: Option[Throwable])
  // Returns an indication of whether the entity has already been computed.
  // 0 means it is not computed.
  // 1 means it is computed.
  // Anything in between indicates that the computation is in progress.
  // -1.0 indicates that an error has occurred during computation.
  // These constants need to be kept in sync with the ones in:
  // /web/app/script/util.js
  def computeProgress(entity: MetaGraphEntity): Double
  def getComputedScalarValue[T](entity: Scalar[T]): ScalarComputationState[T]
}

// Represents a data locality, such as "Spark" or "Scala" or "single-node server".
trait Domain {
  def has(e: MetaGraphEntity): Boolean
  // Returns the progress (0 - 1) or throws Exception if something is wrong.
  def getProgress(e: MetaGraphEntity): Double
  def compute(e: MetaGraphEntity): SafeFuture[Unit]
  // A hint that this entity is likely to be used repeatedly.
  def cache(e: MetaGraphEntity): Unit
  def get[T](e: Scalar[T]): SafeFuture[T]
  def canCompute(e: MetaGraphEntity): Boolean
  // Moves an entity from another Domain to this one.
  // This is a method on the destination so that the methods for modifying internal
  // data structures can remain private.
  def relocate(e: MetaGraphEntity, source: Domain): SafeFuture[Unit]
}

// Manages data computation across domains.
class DataManager(
    // The domains are in order of preference.
    val domains: Seq[Domain]) extends EntityProgressManager {
  implicit val executionContext =
    ThreadUtil.limitedExecutionContext(
      "DataManager",
      maxParallelism = LoggedEnvironment.envOrElse("KITE_SPARK_PARALLELISM", "5").toInt)

  // This can be switched to false to enter "demo mode" where no new calculations are allowed.
  var computationAllowed = true

  override def computeProgress(entity: MetaGraphEntity): Double = {
    1
  }

  override def getComputedScalarValue[T](entity: Scalar[T]): ScalarComputationState[T] = {
    ScalarComputationState(1, None, None)
  }

  private def bestSource(e: MetaGraphEntity): Domain = {
    domains.find(_.has(e)) match {
      case Some(d) => d
      case None => domains.find(_.canCompute(e)).get
    }
  }

  def compute(entity: MetaGraphEntity): SafeFuture[Unit] = {
    ensure(entity, bestSource(entity))
  }

  def getFuture[T](scalar: Scalar[T]): SafeFuture[T] = {
    val d = bestSource(scalar)
    ensure(scalar, d).flatMap(_ => d.get(scalar))
  }

  def get[T](scalar: Scalar[T]): T = {
    getFuture(scalar).awaitResult(concurrent.duration.Duration.Inf)
  }

  private def ensure(e: MetaGraphEntity, d: Domain): SafeFuture[Unit] = {
    if (d.has(e)) {
      SafeFuture.successful(())
    } else if (computationAllowed) {
      if (d.canCompute(e)) {
        ensureInputs(e, d).map { _ =>
          d.compute(e)
        }
      } else {
        val other = bestSource(e)
        ensure(e, other)
        d.relocate(e, other)
      }
    } else SafeFuture.successful(())
  }

  private def ensureInputs(e: MetaGraphEntity, d: Domain): SafeFuture[Unit] = {
    SafeFuture.sequence {
      e.source.inputs.all.values.map { input =>
        ensure(input, d)
      }
    }.map(_ => ())
  }

  def cache(entity: MetaGraphEntity): Unit = {
    bestSource(entity).cache(entity)
  }
}
