package examples.catseffect

import cats.effect.{IO, IOApp, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import examples.Demo
import typesafe.*
import typesafe.catseffect.*

/** The Cats Effect binding: calls are descriptions, the client is a `Resource`, and a fan-out is
  * `parTraverseN` rather than a thread pool you had to size yourself.
  *
  * {{{
  * sbt "examplesCatsEffect/runMain examples.catseffect.Basics"
  * }}}
  */
object Basics extends IOApp.Simple:

  private val urgent     = Noul("The message conveys urgency").named("is_urgent")
  private val department = Choice(
    "Which team should handle this",
    "billing"   -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems"
  ).named("department")

  private val questions = Questions.of(urgent, department)

  private val tickets = Vector(
    "My payouts have failed for 3 days!",
    "Do you support SEPA direct debit?",
    "Every API call returns 500 since this morning."
  )

  /** The demo's settings, with the fake API (if one was started) stopped on release. */
  private val settings: Resource[IO, ClientConfig] =
    Resource.make(IO(Demo.open()))(d => IO(d.release())).map(_.config)

  def run: IO[Unit] =
    settings
      .flatMap(TypeSafeClientF.resource[IO](_))        // closes the HTTP client on release
      .use { client =>
        for
          models <- client.models.list()
          _      <- IO.println(s"models    → ${models.models.map(_.name).mkString(", ")}")

          // Nothing has been sent yet: `systemOne` builds an IO, and this one is described twice
          // and run once.
          first   = client.systemOne(tickets.head, questions)
          res    <- first
          _      <- IO.println(f"first     → urgency ${res(urgent).noul}%.3f, team ${res(department).choice}")

          // Cats Effect does the bounding, so a batch is one line and cancellation still works:
          // interrupt the fiber and the requests in flight are aborted with it.
          all    <- tickets.parTraverseN(4)(client.systemOne(_, questions))
          _      <- all.traverse_ { r =>
                      IO.println(f"batch     → urgency ${r(urgent).noul}%.3f, team ${r(department).choice}")
                    }
        yield ()
      }
