package typesafe.rubric

import scala.annotation.StaticAnnotation

/** Asks the field as a yes/no question. The field is a [[typesafe.NoulAnswer]], or a `Double` for the
  * probability of "yes"; `yes` and `no` describe the two sides and are optional.
  */
final class noul(val instructions: String, val yes: String = "", val no: String = "") extends StaticAnnotation

/** Asks the field as a choice. An enum deriving [[typesafe.RubricChoice]], or a
  * [[typesafe.ChoiceOf]] of one, brings its options; a [[typesafe.ChoiceAnswer]] or a `String` (the
  * selected label) takes them here instead: `@choice("Which team", "billing", "technical")`.
  */
final class choice(val instructions: String, val labels: String*) extends StaticAnnotation

/** Asks the field as a score over `levels`, lowest first. The field is a [[typesafe.ScoreAnswer]], or a
  * `Double` for the probability-weighted level.
  */
final class score(val instructions: String, val levels: String*) extends StaticAnnotation

/** Describes an option of an enum deriving [[typesafe.RubricChoice]]. */
final class option(val description: String) extends StaticAnnotation

/** The name to ask a field under, or the label to offer an enum case as, in place of the snake_case
  * of its own name.
  */
final class named(val name: String) extends StaticAnnotation
