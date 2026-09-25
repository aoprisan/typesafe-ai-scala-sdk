package typesafe

import scala.collection.immutable.VectorMap
import scala.deriving.Mirror

/** A case class whose fields are questions and whose values are their answers.
  *
  * {{{
  * import typesafe.*
  * import typesafe.rubric.*
  *
  * case class Triage(
  *   @noul("The message conveys urgency", yes = "A deadline or ASAP", no = "Routine")
  *   isUrgent: NoulAnswer,
  *   @choice("Which team should handle this")
  *   department: ChoiceOf[Department],
  *   @score("How frustrated the customer appears", "Calm", "Frustrated but civil", "Very angry")
  *   frustration: ScoreAnswer
  * ) derives Rubric
  *
  * enum Department derives RubricChoice:
  *   @option("Payment or subscription issues") case Billing
  *   @option("Bugs or integration problems") case Technical
  *
  * val triage: Triage = client.ask[Triage]("The payout failed again.")
  * }}}
  *
  * Each field is asked under the snake_case of its name (`isUrgent` → `is_urgent`) unless
  * `@named("…")` says otherwise, and its annotation says what kind of question it is. The field's
  * type is what the answer decodes into, so an answer read as the wrong type, a field that is not a
  * question, a score without levels or two fields under one name do not compile.
  *
  * | Annotation                           | Field type                                              |
  * | ------------------------------------ | ------------------------------------------------------- |
  * | `@noul("…", yes = "…", no = "…")`    | [[NoulAnswer]], or `Double` for the probability          |
  * | `@choice("…")`                       | an enum deriving [[RubricChoice]], or [[ChoiceOf]] one   |
  * | `@choice("…", "label", …)`           | [[ChoiceAnswer]], or `String` for the label              |
  * | `@score("…", "level", …)`            | [[ScoreAnswer]], or `Double` for the score               |
  *
  * A response that does not fit — an answer missing, of another type, or a label the enum does not
  * have — is a [[ResponseValidationException]] whose `fieldPath` names it (`answers.department` or
  * `answers.department.choice`). The trait can also be implemented by hand.
  */
trait Rubric[R]:
  /** The questions to send, one per field, in field order. */
  def questions: Questions

  /** Read the answers back out of a response to [[questions]]. Throws [[ResponseValidationException]]. */
  def fromResponse(response: SystemOneResponse): R

object Rubric:
  def apply[R](using r: Rubric[R]): Rubric[R] = r

  inline def derived[R](using m: Mirror.ProductOf[R]): Rubric[R] = ${ RubricMacros.rubric[R]('m) }

/** An enum whose cases are the options of a [[Choice]]. Each case is offered under the snake_case of
  * its name (`NeedsHuman` → `needs_human`) unless `@named("…")` says otherwise, described by
  * `@option("…")` if it has one. Cases that carry data do not compile.
  */
trait RubricChoice[E]:
  /** Every option as `(label, description)`, in the order they are offered. */
  def options: Vector[(String, Option[String])]

  /** The case for a label, if there is one. */
  def fromLabel(label: String): Option[E]

  /** The label a case is sent and answered as. */
  def label(value: E): String

  /** A [[Choice]] offering every option. */
  def choice[A: ToJson](instructions: A): Choice =
    options.foldLeft(Choice.labels(instructions)) {
      case (c, (label, Some(description))) => c.option(label, description)
      case (c, (label, None))              => c.label(label)
    }

  /** [[fromLabel]], with an error that lists the labels there are. */
  def parse(label: String): Either[String, E] =
    fromLabel(label).toRight(
      s"unknown label ${Json.Str(label).render}; expected one of ${options.map((l, _) => Json.Str(l).render).mkString(", ")}"
    )

object RubricChoice:
  def apply[E](using c: RubricChoice[E]): RubricChoice[E] = c

  inline def derived[E]: RubricChoice[E] = ${ RubricMacros.choice[E] }

  /** An instance from `(label, description, value)` triples, in the order they are offered. */
  def of[E](entries: (String, Option[String], E)*): RubricChoice[E] =
    val byLabel = VectorMap.from(entries.map((l, _, v) => l -> v))
    val labels = entries.map((l, _, v) => v -> l).toMap
    val opts = entries.map((l, d, _) => l -> d).toVector
    new RubricChoice[E]:
      def options = opts
      def fromLabel(label: String) = byLabel.get(label)
      def label(value: E) = labels(value)

/** A choice decoded into your enum, with the distribution it was picked from. */
final case class ChoiceOf[E](value: E, answer: ChoiceAnswer):
  /** Certainty derived from the distribution, 0 to 1. */
  def confidence: Double = answer.confidence

  /** The probability the model gave an option. */
  def probability(option: E)(using c: RubricChoice[E]): Option[Double] = answer.probability(c.label(option))
