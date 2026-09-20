package examples.direct

import examples.Demo
import ox.*
import ox.either.*
import typesafe.*
import typesafe.oxdirect.*

/** Direct style on virtual threads: there is no wrapper type here, and that is the point. Blocking
  * is cheap on a virtual thread, so the core's own blocking API *is* the Ox API — a `fork` parks a
  * virtual thread and nothing else, and the core honours interruption when the scope winds it down.
  *
  * Needs a Java 21 runtime, like Ox itself.
  *
  * {{{
  * sbt "examplesOx/runMain examples.direct.oxScoped"
  * }}}
  */
@main def oxScoped(): Unit =
  val demo = Demo.open()

  val urgent     = Noul("The message conveys urgency").named("is_urgent")
  val department = Choice(
    "Which team should handle this",
    "billing"   -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems"
  ).named("department")
  val questions = Questions.of(urgent, department)

  val ticketA = "My payouts have failed for 3 days!"
  val ticketB = "Do you support SEPA direct debit?"

  try
    supervised {
      // Closed when the scope ends, however it ends.
      val client = TypeSafeOx.inScope(demo.config)

      // Two calls at once, joined back in place. No executor, no Future, no callback.
      val a = fork(client.systemOne(ticketA, questions))
      val b = fork(client.systemOne(ticketB, questions))
      println(f"A         → urgency ${a.join()(urgent).noul}%.3f, team ${a.join()(department).choice}")
      println(f"B         → urgency ${b.join()(urgent).noul}%.3f, team ${b.join()(department).choice}")

      // The SDK's failures as a value, so an `either` block short-circuits on the first one and the
      // compiler checks the match. InterruptedException is deliberately *not* caught: it is how a
      // scope winds a fork down, and swallowing it would swallow a cancellation.
      val summary: Either[TypeSafeException, String] = either:
        val res    = client.systemOneEither(ticketA, questions).ok()
        val models = client.modelsEither().ok()
        f"urgency ${res(urgent).noul}%.3f from ${models.models.head.name}"

      summary match
        case Right(line) => println(s"either    → $line")
        case Left(e: ApiException) => println(s"either    → api ${e.status} ${e.kind}")
        case Left(e)     => println(s"either    → ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  finally demo.release()
