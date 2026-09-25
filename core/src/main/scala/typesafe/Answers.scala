package typesafe

import java.time.LocalDate
import java.time.format.DateTimeParseException
import scala.collection.immutable.{SortedMap, VectorMap}

/** What type an [[Answer]] is, with the name the API gives it on the wire. */
enum AnswerKind(val wire: String):
  case Noul extends AnswerKind("noul")
  case Choice extends AnswerKind("choice")
  case Score extends AnswerKind("score")

  override def toString: String = wire

object AnswerKind:
  /** The kind the API calls `wire`, if this SDK knows it. */
  def fromWire(wire: String): Option[AnswerKind] = values.find(_.wire == wire)

/** One typed answer: a [[NoulAnswer]], [[ChoiceAnswer]] or [[ScoreAnswer]]. Sealed, so a match on it
  * is checked for exhaustiveness.
  */
sealed trait Answer:
  /** The answer's type; `kind.wire` is its name on the wire. */
  def kind: AnswerKind

/** Probability of "yes", 0 to 1. */
final case class NoulAnswer(noul: Double) extends Answer:
  def kind = AnswerKind.Noul
  def isYes(threshold: Double = 0.5): Boolean = noul >= threshold

/** The selected option, every option's probability (server order) and confidence. */
final case class ChoiceAnswer(choice: String, probabilities: VectorMap[String, Double], confidence: Double)
    extends Answer:
  def kind = AnswerKind.Choice
  def probability(label: String): Option[Double] = probabilities.get(label)
  def ranked: Vector[(String, Double)] = probabilities.toVector.sortBy(-_._2)

  /** Map the label onto your own type, e.g. `answer.as(Department.valueOf)`. */
  def as[T](f: String => T): Either[Throwable, T] = scala.util.Try(f(choice)).toEither

/** Probability-weighted level (may fall between levels), rubric legend and per-level probabilities. */
final case class ScoreAnswer(
    score: Double,
    confidence: Double,
    legend: SortedMap[Int, Json],
    probabilities: SortedMap[Int, Double]
) extends Answer:
  def kind = AnswerKind.Score
  def mostLikelyLevel: Option[Int] = probabilities.maxByOption(_._2).map(_._1)
  def roundedLevel: Int = math.max(0, math.round(score).toInt)

/** Token counts the API reported for a call; either may be absent. */
final case class Usage(inputTokens: Option[Long] = None, outputTokens: Option[Long] = None)

/** HTTP metadata of the exchange that produced a response. */
final case class ResponseMeta(status: Int, headers: Map[String, List[String]], attempts: Int):
  def header(name: String): Option[String] =
    headers.collectFirst { case (k, v :: _) if k.equalsIgnoreCase(name) => v }
  def requestId: Option[String] = header(Constants.RequestIdHeader)

/** The answers to one System One call, keyed by question name, with the model that gave them, token
  * usage, the raw JSON body and the HTTP metadata. Look answers up by handle: `res(urgent).noul`.
  */
final case class SystemOneResponse(
    model: String,
    usage: Usage,
    answers: VectorMap[String, Answer],
    raw: Json,
    meta: ResponseMeta
):
  def requestId: Option[String] = meta.requestId

  /** Typed lookup by handle; `None` if missing or of a different type. */
  def get[A <: Answer](asked: Asked[A]): Option[A] =
    answers.get(asked.name).flatMap(asked.tag.unapply)

  /** Typed lookup by handle; throws `NoSuchElementException` if missing or of a different type. */
  def apply[A <: Answer](asked: Asked[A]): A =
    get(asked).getOrElse(throw java.util.NoSuchElementException(s"no ${asked.tag.runtimeClass.getSimpleName} for '${asked.name}'"))

  def noul(name: String): Option[NoulAnswer] = answers.get(name).collect { case a: NoulAnswer => a }
  def choice(name: String): Option[ChoiceAnswer] = answers.get(name).collect { case a: ChoiceAnswer => a }
  def score(name: String): Option[ScoreAnswer] = answers.get(name).collect { case a: ScoreAnswer => a }

  def nouls: VectorMap[String, NoulAnswer] = answers.collect { case (k, a: NoulAnswer) => k -> a }
  def choices: VectorMap[String, ChoiceAnswer] = answers.collect { case (k, a: ChoiceAnswer) => k -> a }
  def scores: VectorMap[String, ScoreAnswer] = answers.collect { case (k, a: ScoreAnswer) => k -> a }

/** One model the API offers, with the date it was released. */
final case class ModelMetadata(name: String, description: String, releaseDate: LocalDate)

/** The models available to the API key, in the order the API listed them. */
final case class ListModelsResponse(models: Vector[ModelMetadata], meta: ResponseMeta)

/** Decoding with dotted field paths for error messages. */
private[typesafe] object Decode:
  final case class Failure(path: String, detail: String) extends Exception(detail, null, false, false)

  private def fail(path: String, detail: String): Nothing = throw Failure(path, detail)

  private def join(prefix: String, key: String) = if prefix.isEmpty then key else s"$prefix.$key"

  private def obj(j: Json, path: String): VectorMap[String, Json] =
    j.asObject.getOrElse(fail(path, s"expected an object, got ${j.render.take(40)}"))

  private def field(o: VectorMap[String, Json], key: String, prefix: String): Json =
    o.getOrElse(key, fail(join(prefix, key), s"missing field `$key`"))

  private def str(j: Json, path: String): String = j.asString.getOrElse(fail(path, "expected a string"))

  private def date(j: Json, path: String): LocalDate =
    val s = str(j, path)
    try LocalDate.parse(s)
    catch case _: DateTimeParseException => fail(path, s"expected an ISO-8601 date (yyyy-mm-dd), got ${Json.Str(s).render}")

  private def num(j: Json, path: String): Double = j.asDouble.getOrElse(fail(path, "expected a number"))

  private def optLong(o: VectorMap[String, Json], key: String, prefix: String): Option[Long] =
    o.get(key) match
      case None | Some(Json.Null) => None
      case Some(Json.Num(n)) if n.isValidLong => Some(n.toLongExact)
      case Some(_) => fail(join(prefix, key), "expected an integer")

  private def probs(j: Json, path: String): VectorMap[String, Double] =
    obj(j, path).map((k, v) => k -> num(v, join(path, k)))

  private def levelKey(k: String, path: String): Int =
    k.toIntOption.filter(_ >= 0).getOrElse(fail(join(path, k), "expected a non-negative integer key"))

  def systemOne(raw: Json, onUnknown: (String, String) => Unit): (String, Usage, VectorMap[String, Answer]) =
    val top = obj(raw, "")
    val model = str(field(top, "model", ""), "model")
    val u = obj(field(top, "usage", ""), "usage")
    val usage = Usage(optLong(u, "input_tokens", "usage"), optLong(u, "output_tokens", "usage"))
    val answers = VectorMap.newBuilder[String, Answer]
    obj(field(top, "answers", ""), "answers").foreach { (name, value) =>
      val p = s"answers.$name"
      val o = value.asObject.getOrElse(fail(s"$p.type", "missing or non-string answer type"))
      val tpe = o.get("type").flatMap(_.asString).getOrElse(fail(s"$p.type", "missing or non-string answer type"))
      tpe match
        case "noul" =>
          answers += name -> NoulAnswer(num(field(o, "noul", p), s"$p.noul"))
        case "choice" =>
          answers += name -> ChoiceAnswer(
            str(field(o, "choice", p), s"$p.choice"),
            probs(field(o, "probabilities", p), s"$p.probabilities"),
            num(field(o, "confidence", p), s"$p.confidence")
          )
        case "score" =>
          val lp = s"$p.legend"
          val pp = s"$p.probabilities"
          answers += name -> ScoreAnswer(
            num(field(o, "score", p), s"$p.score"),
            num(field(o, "confidence", p), s"$p.confidence"),
            SortedMap.from(obj(field(o, "legend", p), lp).map((k, v) => levelKey(k, lp) -> v)),
            SortedMap.from(probs(field(o, "probabilities", p), pp).map((k, v) => levelKey(k, pp) -> v))
          )
        case other => onUnknown(name, other)
    }
    (model, usage, answers.result())

  def models(raw: Json): Vector[ModelMetadata] =
    val top = obj(raw, "")
    field(top, "models", "").asArray.getOrElse(fail("models", "expected an array")).zipWithIndex.map { (m, i) =>
      val p = s"models[$i]"
      val o = obj(m, p)
      ModelMetadata(
        str(field(o, "name", p), s"$p.name"),
        str(field(o, "description", p), s"$p.description"),
        date(field(o, "release_date", p), s"$p.release_date")
      )
    }
