package com.lynxanalytics.biggraph.graph_operations

import scala.util.{ Failure, Success, Try }
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_util.Filename
import com.lynxanalytics.biggraph.spark_util.RDDUtils
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark.SparkContext

// Functions for looking at CSV files. The frontend can use these when
// constructing the import operation.
object ImportUtil {
  def header(file: Filename): String =
    file.reader.readLine

  def fields(file: Filename, delimiter: String): Seq[String] =
    split(header(file), delimiter)

  private[graph_operations] def splitter(delimiter: String): String => Seq[String] = {
    val delim = java.util.regex.Pattern.quote(delimiter)
    def oneOf(options: String*) = options.mkString("|")
    def any(p: String) = capture(p) + "*"
    def capture(p: String) = "(" + p + ")"
    def oneField(p: String) = oneOf(p + delim, p + "$")
    val quote = "\""
    val nonQuote = "[^\"]"
    val doubleQuote = quote + quote
    val quotedString = quote + capture(any(oneOf(nonQuote, doubleQuote))) + quote
    val anyString = capture(".*?")
    val r = oneOf(oneField(quotedString), oneField(anyString)).r
    val splitter = { line: String =>
      val fields = r.findAllMatchIn(line).map(_.subgroups.find(_ != null).get).toList
      val l = fields.length
      // The last field may be a mistake. (Sorry, I couldn't write a better regex.)
      val fixed = if (l < 2 || fields(l - 2).endsWith(delimiter)) fields else fields.take(l - 1)
      fixed.map(_.replace(doubleQuote, quote))
    }
    return splitter
  }

  private val splitters = collection.mutable.Map[String, String => Seq[String]]()

  // Splits a line by the delimiter. Delimiters inside quoted fields are ignored.
  // (They become part of the string.) Quotes inside fields must be escaped by
  // doubling them (" -> "").
  private[graph_operations] def split(line: String, delimiter: String): Seq[String] = {
    // Cache the regular expressions.
    if (!splitters.contains(delimiter)) {
      splitters(delimiter) = splitter(delimiter)
    }
    return splitters(delimiter)(line)
  }
}

case class Javascript(expression: String) {
  def isEmpty = expression.isEmpty
  def nonEmpty = expression.nonEmpty

  def isTrue(mapping: (String, String)*): Boolean = isTrue(mapping.toMap)
  def isTrue(mapping: Map[String, String]): Boolean = {
    if (isEmpty) {
      return true
    }
    val bindings = Javascript.engine.createBindings
    for ((key, value) <- mapping) {
      bindings.put(key, value)
    }
    return Try(Javascript.engine.eval(expression, bindings)) match {
      case Success(result: java.lang.Boolean) =>
        result
      case Success(result) =>
        throw Javascript.Error(s"JS expression ($expression) returned $result instead of a Boolean")
      case Failure(e) =>
        throw Javascript.Error(s"Could not evaluate JS: $expression", e)
    }
  }
}
object Javascript {
  val engine = new javax.script.ScriptEngineManager().getEngineByName("JavaScript")
  case class Error(msg: String, cause: Throwable = null) extends Exception(msg, cause)
}

case class CSV(file: Filename,
               delimiter: String,
               header: String,
               filter: Javascript = Javascript("")) {
  val fields = ImportUtil.split(header, delimiter)

  def lines(sc: SparkContext): RDD[Seq[String]] = {
    val lines = file.loadTextFile(sc)
    return lines
      .filter(_ != header)
      .map(ImportUtil.split(_, delimiter))
      .filter(jsFilter(_))
  }

  def jsFilter(line: Seq[String]): Boolean = {
    if (line.length != fields.length) {
      log.info(s"Input line cannot be parsed: $line")
      return false
    }
    return filter.isTrue(fields.zip(line).toMap)
  }
}

abstract class ImportCommon(csv: CSV) extends MetaGraphOperation {
  type Columns = Map[String, RDD[(Long, String)]]

  protected def mustHaveField(field: String) = {
    assert(csv.fields.contains(field), s"No such field: $field in ${csv.fields}")
  }
  protected def mustNotHaveField(field: String) = {
    assert(!csv.fields.contains(field),
      s"'$field' is a reserved name and cannot be the name of a column.")
  }

  protected def splitGenerateIDs(lines: RDD[Seq[String]]): Columns = {
    val numbered = RDDUtils.fastNumbered(lines)
    return csv.fields.zipWithIndex.map {
      case (field, idx) => field -> numbered.map { case (id, line) => id -> line(idx) }
    }.toMap
  }

  protected def splitWithIDField(lines: RDD[Seq[String]], idField: String): Columns = {
    val idIdx = csv.fields.indexOf(idField)
    return csv.fields.zipWithIndex.map {
      case (field, idx) => field -> lines.map { line => line(idIdx).toLong -> line(idx) }
    }.toMap
  }
}

abstract class ImportVertexList(csv: CSV) extends ImportCommon(csv) {
  mustNotHaveField("vertices")

  def signature = {
    val s = newSignature.outputVertexSet('vertices)
    for (field <- csv.fields) {
      s.outputVertexAttribute[String](Symbol(field), 'vertices)
    }
    s
  }

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = {
    val columns = readColumns(rc.sparkContext)
    for ((field, rdd) <- columns) {
      outputs.putVertexAttribute(Symbol(field), rdd)
    }
    outputs.putVertexSet('vertices, columns.values.head.keys.map((_, Unit)))
  }

  // Override this.
  def readColumns(sc: SparkContext): Columns
}

case class ImportVertexListWithStringIDs(csv: CSV) extends ImportVertexList(csv) {
  def readColumns(sc: SparkContext): Columns = splitGenerateIDs(csv.lines(sc))
}

case class ImportVertexListWithNumericIDs(csv: CSV, id: String) extends ImportVertexList(csv) {
  mustHaveField(id)
  def readColumns(sc: SparkContext): Columns = splitWithIDField(csv.lines(sc), id)
}

abstract class ImportEdgeList(csv: CSV) extends ImportCommon(csv) {
  mustNotHaveField("edges")

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = {
    val columns = readColumns(rc.sparkContext)
    execute(columns, outputs)
  }

  // Override these.
  def readColumns(sc: SparkContext): Columns = splitGenerateIDs(csv.lines(sc))
  def execute(columns: Columns, outputs: DataSetBuilder): Unit = {
    for ((field, rdd) <- columns) {
      outputs.putEdgeAttribute(Symbol(field), rdd)
    }
  }
  def signature = {
    val s = newSignature
    for (field <- csv.fields) {
      // Subclass has to define 'edges.
      s.outputEdgeAttribute[String](Symbol(field), 'edges)
    }
    s
  }
}

case class ImportEdgeListWithNumericIDs(csv: CSV, src: String, dst: String) extends ImportEdgeList(csv) {
  mustHaveField(src)
  mustHaveField(dst)

  override def signature = super.signature.outputGraph('vertices, 'edges)

  override def execute(columns: Columns, outputs: DataSetBuilder) = {
    super.execute(columns, outputs)
    outputs.putVertexSet('vertices,
      (columns(src).values ++ columns(dst).values).distinct.map(_.toLong -> ()))
    outputs.putEdgeBundle('edges, columns(src).join(columns(dst)).mapValues {
      case (src, dst) => Edge(src.toLong, dst.toLong)
    })
  }
}

case class ImportEdgeListWithStringIDs(
    csv: CSV, src: String, dst: String, vertexAttr: String) extends ImportEdgeList(csv) {
  mustHaveField(src)
  mustHaveField(dst)
  mustNotHaveField(vertexAttr)

  override def signature = super.signature
    .outputGraph('vertices, 'edges)
    .outputVertexAttribute[String](Symbol(vertexAttr), 'vertices)

  override def execute(columns: Columns, outputs: DataSetBuilder) = {
    super.execute(columns, outputs)
    val names = (columns(src).values ++ columns(dst).values).distinct
    val idToName = RDDUtils.fastNumbered(names)
    val nameToId = idToName.map { case (id, name) => (name, id) }
    val edgeSrcDst = columns(src).join(columns(dst))
    val bySrc = edgeSrcDst.map {
      case (edge, (src, dst)) => src -> (edge, dst)
    }
    val byDst = bySrc.join(nameToId).map {
      case (src, ((edge, dst), sid)) => dst -> (edge, sid)
    }
    val edges = byDst.join(nameToId).map {
      case (dst, ((edge, sid), did)) => edge -> Edge(sid, did)
    }
    outputs.putEdgeBundle('edges, edges)
    outputs.putVertexSet('vertices, idToName.mapValues(_ => ()))
    outputs.putVertexAttribute(Symbol(vertexAttr), idToName)
  }
}

case class ImportEdgeListWithNumericIDsForExistingVertexSet(
    csv: CSV, src: String, dst: String) extends ImportEdgeList(csv) {
  mustHaveField(src)
  mustHaveField(dst)
  mustNotHaveField("sources")
  mustNotHaveField("destinations")

  override def signature = super.signature
    .inputVertexSet('sources)
    .inputVertexSet('destinations)
    .outputEdgeBundle('edges, 'sources -> 'destinations)

  override def execute(columns: Columns, outputs: DataSetBuilder) = {
    super.execute(columns, outputs)
    outputs.putEdgeBundle('edges, columns(src).join(columns(dst)).mapValues {
      case (src, dst) => Edge(src.toLong, dst.toLong)
    })
  }
}
