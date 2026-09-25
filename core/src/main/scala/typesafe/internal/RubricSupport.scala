package typesafe.internal

import scala.collection.immutable.VectorMap
import scala.deriving.Mirror
import typesafe.*

/** What derived rubrics are built from: the code `derives Rubric` expands to calls these. Public only
  * because that code lands in your own; not part of the API, and free to change between releases.
  */
object RubricSupport:
  final case class Field(name: String, question: Question, decode: SystemOneResponse => Any)

  def rubric[R](fields: List[Field], m: Mirror.ProductOf[R]): Rubric[R] =
    val names = fields.map(_.name)
    require(names.distinct.size == names.size, s"two fields ask under one name: ${names.diff(names.distinct).mkString(", ")}")
    val qs = Questions(VectorMap.from(fields.map(f => f.name -> f.question)))
    new Rubric[R]:
      def questions = qs
      def fromResponse(response: SystemOneResponse): R =
        m.fromProduct(Tuple.fromArray(fields.map(_.decode(response).asInstanceOf[Object]).toArray))

  def noulQuestion(a: typesafe.rubric.noul): Noul =
    val asked = Noul(a.instructions)
    val withYes = if a.yes.nonEmpty then asked.describeTrue(a.yes) else asked
    if a.no.nonEmpty then withYes.describeFalse(a.no) else withYes

  def scoreQuestion(a: typesafe.rubric.score): Score = Score(a.instructions, a.levels*)

  def labelsQuestion(a: typesafe.rubric.choice): Choice = Choice.labels(a.instructions, a.labels*)

  def enumQuestion[E](a: typesafe.rubric.choice, c: RubricChoice[E]): Choice = c.choice(a.instructions)

  def describe(a: typesafe.rubric.option): Option[String] = Some(a.description)

  private def mismatch(response: SystemOneResponse, fieldPath: String, detail: String) =
    ResponseValidationException(response.meta.status, fieldPath, detail, Some(response.raw), response.meta.headers, None)

  private def answer(response: SystemOneResponse, name: String, kind: AnswerKind): Answer =
    val found = response.answers.getOrElse(
      name, {
        val detail =
          if response.raw.get("answers").flatMap(_.get(name)).isDefined then
            s"the answer is of a type this SDK does not know; expected a $kind"
          else s"no answer; expected a $kind"
        throw mismatch(response, s"answers.$name", detail)
      }
    )
    if found.kind != kind then throw mismatch(response, s"answers.$name", s"expected a $kind answer, got a ${found.kind}")
    found

  def noul(response: SystemOneResponse, name: String): NoulAnswer =
    answer(response, name, AnswerKind.Noul).asInstanceOf[NoulAnswer]

  def score(response: SystemOneResponse, name: String): ScoreAnswer =
    answer(response, name, AnswerKind.Score).asInstanceOf[ScoreAnswer]

  def choice(response: SystemOneResponse, name: String): ChoiceAnswer =
    answer(response, name, AnswerKind.Choice).asInstanceOf[ChoiceAnswer]

  def choiceOf[E](response: SystemOneResponse, name: String, c: RubricChoice[E]): ChoiceOf[E] =
    val a = choice(response, name)
    c.parse(a.choice) match
      case Right(value) => ChoiceOf(value, a)
      case Left(detail) => throw mismatch(response, s"answers.$name.choice", detail)
