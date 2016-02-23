// A convenient interface for evaluating JavaScript expressions.
package com.lynxanalytics.biggraph

import org.mozilla.javascript

case class JavaScript(expression: String) {
  def isEmpty = expression.isEmpty
  def nonEmpty = expression.nonEmpty

  def isTrue(mapping: (String, String)*): Boolean = isTrue(mapping.toMap)
  def isTrue(mapping: Map[String, String]): Boolean = {
    if (isEmpty) {
      return true
    }
    return evaluator.evaluateBoolean(mapping).getOrElse(false)
  }

  def contextString(mapping: Map[String, Any]): String = {
    s"$this with values: {" + mapping.map { case (k, v) => s"$k: $v" }.mkString(", ") + "}"
  }

  // Creating the evaluator is expensive, so let's make it explicit.
  def evaluator = new JavaScriptEvaluator(expression)
}

// JavaScriptEvaluator maintains a Rhino context. So it's not thread-safe and not Serializable.
class JavaScriptEvaluator private[biggraph] (expression: String) {
  // We need a Context object to compile an expression and to run it.
  // Since JavaScriptEvaluator is used on RDD iterators, it is hard to clean up the Context object.
  // We could do it at the end of the iterator, but that may never be reached. We could also do it
  // in finalize() on GC, but at that point we will be in a different thread.
  // At most one Context object is created per thread, so we simply leave them around. (Until we
  // figure out a proper solution.) Each enter() call after the first just increments a reference
  // counter.
  val cx = javascript.Context.enter()
  val script = cx.compileString(expression, "derivation script", 1, null)
  val sharedScope = cx.initSafeStandardObjects( /* scope = */ null, /* sealed = */ true)
  javascript.ScriptableObject.putProperty(sharedScope, "util", JavaScriptUtilities)
  sharedScope.sealObject()

  private def evaluate(mapping: Map[String, Any]): Option[AnyRef] = {
    val scope = cx.newObject(sharedScope)
    scope.setPrototype(sharedScope)
    scope.setParentScope(null)
    for ((name, value) <- mapping) {
      val jsValue = javascript.Context.javaToJS(value, scope)
      javascript.ScriptableObject.putProperty(scope, name, jsValue)
    }
    val jsResult = script.exec(cx, scope)
    jsResult match {
      case _: javascript.Undefined => None
      case definedValue => Some(definedValue)
    }
  }

  // Always returns a Double. Expressions which cannot be interpreted as Doubles
  // like 'abc' will result in Double.NaN.
  def evaluateDouble(mapping: Map[String, Any]): Option[Double] = {
    evaluate(mapping).map { v => javascript.Context.toNumber(v) }
  }

  def evaluateString(mapping: Map[String, Any]): Option[String] = {
    evaluate(mapping).map { v => javascript.Context.toString(v) }
  }

  def evaluateBoolean(mapping: Map[String, Any]): Option[Boolean] = {
    evaluate(mapping).map { v => javascript.Context.toBoolean(v) }
  }
}

// This object is exposed to user-authored JavaScript. Only harmless stuff, please.
object JavaScriptUtilities {
  def rnd(seed: Int) = new scala.util.Random(seed)
  def hash(str: String) = str.hashCode
}
