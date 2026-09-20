package examples

import typesafe.*

/** Your own types on the way in, your own types on the way out.
  *
  * The `state` is anything with a `ToJson` instance — here a case class that derives one — and the
  * chosen label can be mapped back onto an enum instead of being compared against strings.
  *
  * {{{
  * sbt "examples/runMain examples.typedState"
  * }}}
  */

enum Channel derives ToJson:
  case Email, Chat, Phone

/** `None` fields are left out of the request; field order is preserved. */
final case class Ticket(
    subject: String,
    priority: Option[Int],
    messages: List[String],
    channel: Channel
) derives ToJson

/** The labels of the `department` question, as a type the rest of the program can match on. */
enum Department:
  case billing, technical, sales

@main def typedState(): Unit = Demo.run() { config =>
  val client = TypeSafeClient(config)

  val ticket = Ticket(
    subject = "Stripe payouts failing",
    priority = None,                                    // omitted from the JSON, not sent as null
    messages = List(
      "Connecting my Stripe account has failed for 3 days.",
      "Any update? I'm losing sales."
    ),
    channel = Channel.Chat
  )

  // Exactly what will be sent as `state`. Useful when a rubric is not behaving and you want to see
  // what the model actually read.
  println(s"state ${ToJson[Ticket](ticket).render}")

  val department = Choice
    .labels("Which team should handle this", Department.values.map(_.toString)*)
    .named("department")

  // Instructions and rubric levels are JSON, not just text, so a structured prompt stays structured.
  val tone = Score(
    Json.obj(
      "task"   -> "Rate how hostile the customer's tone is",
      "ignore" -> List("greetings", "signatures")
    )
  ).level(Json.obj("level" -> "polite", "signals" -> List("please", "thanks")))
    .level(Json.obj("level" -> "terse"))
    .level(Json.obj("level" -> "hostile", "signals" -> List("threats", "insults")))
    .named("tone")

  val refund = Noul("Is this a refund request?")
    .describeTrue("An explicit ask for money back")
    .describeFalse("Anything else, including complaints about price")
    .named("refund")

  try
    val res = client.systemOne(ticket, Questions.of(department, tone, refund))

    // `as` runs your own function over the label; a label this build does not know about comes back
    // as a Left instead of throwing somewhere deeper in the program.
    res(department).as(Department.valueOf) match
      case Right(Department.billing)   => println("route → billing queue")
      case Right(Department.technical) => println("route → engineering on-call")
      case Right(Department.sales)     => println("route → account manager")
      case Left(unknown)               => println(s"unrecognised label: ${unknown.getMessage}")

    val t = res(tone)
    println(f"tone  → level ${t.roundedLevel} (${t.score}%.2f), confidence ${t.confidence}%.2f")
    println(s"refund? ${res(refund).isYes()}")

    // String keys work too, if you would rather not carry the handles around; the lookup is then an
    // Option, because nothing has promised the answer is there.
    println(s"by name: ${res.noul("refund").map(_.noul).getOrElse("-")}")
  finally client.close()
}
