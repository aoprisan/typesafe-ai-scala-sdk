package examples.monixtask

import examples.Demo
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*
import typesafe.*
import typesafe.monixeffect.*

/** The Monix binding: the same client as a `Task`, with the lifetime handled by a `Resource`.
  *
  * Monix 3.x sits on Cats Effect 2, so this module can never share a classpath with the Cats Effect
  * 3 ones — which is why these examples live in their own sbt module too.
  *
  * {{{
  * sbt "examplesMonix/runMain examples.monixtask.MonixBasics"
  * }}}
  */
object MonixBasics:

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

  def main(args: Array[String]): Unit =
    val demo = Demo.open()

    // The resource builds the client, and `use` runs the body and closes it on success, failure or
    // cancellation.
    val program: Task[Unit] = TypeSafeClientTask.resource(demo.config).use { client =>
      for
        models <- client.models.list()
        _      <- Task(println(s"models    → ${models.models.map(_.name).mkString(", ")}"))

        // Nothing is sent until the Task runs, so this describes the call twice and sends it once.
        res    <- client.systemOne(tickets.head, questions)
        _      <- Task(println(f"first     → urgency ${res(urgent).noul}%.3f, team ${res(department).choice}"))

        // A batch, with Monix doing the bounding.
        all    <- Task.parSequenceN(4)(tickets.map(client.systemOne(_, questions)))
        _      <- Task(all.foreach { r =>
                    println(f"batch     → urgency ${r(urgent).noul}%.3f, team ${r(department).choice}")
                  })

        // Cancelling aborts the exchange in flight rather than merely detaching from it.
        fiber  <- client.systemOne(tickets.head, questions).start
        _      <- fiber.cancel
        _      <- Task(println("cancelled → request aborted"))
      yield ()
    }

    try Await.result(program.runToFuture, 2.minutes)
    finally demo.release()
