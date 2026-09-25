package typesafe

import scala.collection.immutable.VectorMap
import scala.reflect.ClassTag

/** A typed question. Instructions, descriptions and levels are [[Json]], so structured rubrics work too.
  *
  * [[Noul]], [[Choice]] and [[Score]] are built with their companions' smart constructors and the
  * builder methods on them; their case-class constructors are the SDK's own, so an `Option` or a
  * question of the wrong kind cannot slip past the typed ones.
  */
sealed trait Question:
  def toJson: Json

/** Instructions as sent: a value that encodes to `null` (a `None`, say) means none at all. */
private def instructionsOf[A: ToJson](instructions: A): Option[Json] =
  Some(ToJson[A](instructions)).filter(_ != Json.Null)

/** Yes/no question; the answer is the probability of "yes". */
final case class Noul private[typesafe] (
    instructions: Option[Json],
    whenTrue: Option[Json],
    whenFalse: Option[Json]
) extends Question:
  def describeTrue[A: ToJson](description: A): Noul = copy(whenTrue = Some(ToJson[A](description)))
  def describeFalse[A: ToJson](description: A): Noul = copy(whenFalse = Some(ToJson[A](description)))

  /** Attach a name, producing a handle that reads back a [[NoulAnswer]]. */
  def named(name: String): Asked[NoulAnswer] = Asked(name, this)

  def toJson: Json =
    val criteria =
      if whenTrue.isEmpty && whenFalse.isEmpty then Nil
      else
        List("criteria" -> Json.Obj(VectorMap.from(whenTrue.map("true" -> _) ++ whenFalse.map("false" -> _))))
    Json.Obj(VectorMap.from(("type" -> Json.Str("noul")) :: instructions.map("instructions" -> _).toList ++ criteria))

object Noul:
  /** `Noul("Is this a refund request?")`. Instructions that encode to `null` are left out. */
  def apply[A: ToJson](instructions: A): Noul = new Noul(instructionsOf(instructions), None, None)

  /** A yes/no question with no instructions of its own. */
  def apply(): Noul = new Noul(None, None, None)

/** Pick one option from a set you define. `None` descriptions are sent as `null`. */
final case class Choice private[typesafe] (
    instructions: Option[Json],
    criteria: VectorMap[String, Option[Json]]
) extends Question:
  def option[A: ToJson](label: String, description: A): Choice =
    copy(criteria = criteria.updated(label, Some(ToJson[A](description))))
  def label(label: String): Choice = copy(criteria = criteria.updated(label, None))

  def named(name: String): Asked[ChoiceAnswer] = Asked(name, this)

  def toJson: Json =
    Json.Obj(
      VectorMap.from(
        ("type" -> Json.Str("choice")) :: instructions.map("instructions" -> _).toList ++
          List("criteria" -> Json.Obj(criteria.map((k, v) => k -> v.getOrElse(Json.Null))))
      )
    )

object Choice:
  /** `Choice("Which team?", "billing" -> "Payments", "technical" -> "Bugs")` */
  def apply[A: ToJson](instructions: A, options: (String, String)*): Choice =
    new Choice(instructionsOf(instructions), VectorMap.from(options.map((k, v) => k -> Some(Json.Str(v)))))

  /** A choice between undescribed labels. */
  def labels[A: ToJson](instructions: A, labels: String*): Choice =
    new Choice(instructionsOf(instructions), VectorMap.from(labels.map(_ -> None)))

/** Rate along ordered levels (index 0 upwards); the answer is a probability-weighted level. */
final case class Score private[typesafe] (instructions: Option[Json], criteria: Vector[Json]) extends Question:
  def level[A: ToJson](description: A): Score = copy(criteria = criteria :+ ToJson[A](description))

  def named(name: String): Asked[ScoreAnswer] = Asked(name, this)

  def toJson: Json =
    Json.Obj(
      VectorMap.from(
        ("type" -> Json.Str("score")) :: instructions.map("instructions" -> _).toList ++
          List("criteria" -> Json.Arr(criteria))
      )
    )

object Score:
  /** `Score("How frustrated?", "Calm", "Frustrated", "Very angry")` */
  def apply[A: ToJson](instructions: A, levels: String*): Score =
    new Score(instructionsOf(instructions), levels.map(Json.Str(_)).toVector)

/** A hand-built question object, passed through after light validation (must have a non-empty
  * string `type`). Useful for fields this SDK version does not model.
  */
final case class RawQuestion(json: Json) extends Question:
  def toJson: Json = json

/** A question with its name and the answer type it produces; read it back with `response(asked)`.
  * Made by `.named(...)` on a [[Noul]], [[Choice]] or [[Score]], which is what ties the answer type
  * to the question.
  */
final case class Asked[A <: Answer] private[typesafe] (name: String, question: Question)(using val tag: ClassTag[A])

/** Ordered question name → question map. Answers come back under the same names. */
final case class Questions(entries: VectorMap[String, Question]):
  def +(entry: (String, Question)): Questions = Questions(entries + entry)
  def +(asked: Asked[?]): Questions = Questions(entries.updated(asked.name, asked.question))
  def isEmpty: Boolean = entries.isEmpty
  def size: Int = entries.size

  def toJson: Json = Json.Obj(entries.map((k, q) => k -> q.toJson))

  private[typesafe] def validate(): Unit =
    if entries.isEmpty then throw InvalidRequestException("At least one question is required.")
    entries.foreach {
      case (name, Score(_, levels)) if levels.isEmpty => throw emptyScore(name)
      case (name, Choice(_, options)) if options.isEmpty => throw emptyChoice(name)
      case (name, RawQuestion(json))                  => validateRaw(name, json)
      case _                                          => ()
    }

  private def emptyScore(name: String) =
    InvalidRequestException(s"""Score question "$name" has no criteria; at least one score is required.""")

  private def emptyChoice(name: String) =
    InvalidRequestException(s"""Choice question "$name" has no criteria; at least one option is required.""")

  private def validateRaw(name: String, json: Json): Unit =
    val tpe = json.get("type").flatMap(_.asString).filter(_.nonEmpty).getOrElse {
      throw InvalidRequestException(
        s"""Question "$name" must be a question object or a JSON object with a nonempty string "type"."""
      )
    }
    if tpe == "choice" || tpe == "score" then
      val criteria = json.get("criteria").getOrElse(throw InvalidRequestException(s"""Question "$name" requires "criteria"."""))
      val empty = criteria match
        case Json.Null      => true
        case Json.Bool(b)   => !b
        case Json.Str(s)    => s.isEmpty
        case Json.Arr(a)    => a.isEmpty
        case Json.Obj(o)    => o.isEmpty
        case Json.Num(n)    => n == 0
      if empty then throw (if tpe == "score" then emptyScore(name) else emptyChoice(name))

object Questions:
  val empty: Questions = Questions(VectorMap.empty)

  def apply(entries: (String, Question)*): Questions = new Questions(VectorMap.from(entries))

  /** `Questions(urgent, department)` from named handles. */
  def of(asked: Asked[?]*): Questions = new Questions(VectorMap.from(asked.map(a => a.name -> a.question)))
