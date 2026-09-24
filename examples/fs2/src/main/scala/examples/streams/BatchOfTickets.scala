package examples.streams

import cats.effect.{IO, IOApp, Resource}
import examples.Demo
import fs2.Stream
import typesafe.*
import typesafe.fs2streams.*

/** A backlog of tickets through System One: bounded concurrency, input order preserved, and one bad
  * state kept from sinking the run.
  *
  * The API has no streaming endpoint — what streams is your own workload.
  *
  * {{{
  * sbt "examplesFs2/runMain examples.streams.BatchOfTickets"
  * }}}
  */
object BatchOfTickets extends IOApp.Simple:

  private val urgent     = Noul("The message conveys urgency").named("is_urgent")
  private val department = Choice(
    "Which team should handle this",
    "billing"   -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems"
  ).named("department")

  private val questions = Questions.of(urgent, department)

  private val backlog = (1 to 12).toVector.map(i => s"Ticket #$i: payouts have failed for $i days, please help.")

  private val settings: Resource[IO, ClientConfig] =
    Resource.make(IO(Demo.open()))(d => IO(d.release())).map(_.config)

  def run: IO[Unit] =
    Stream.resource(settings).flatMap(config => TypeSafeStream.resource[IO](config)).flatMap { client =>
      // 1. In input order, at most 4 calls in flight. The first failure that survives the retry
      //    policy fails the stream — the right default for a job that must be complete or not at all.
      val ordered =
        Stream.emits(backlog)
          .through(client.systemOnePipe(questions, maxConcurrent = 4))
          .map(res => f"${res(department).choice}%-10s ${res(urgent).noul}%.3f")
          .zipWithIndex
          .evalMap((line, i) => IO.println(f"  #${i + 1}%-3d $line"))

      // 2. Each state with its own outcome instead: a long run against a flaky key finishes, and
      //    the failures can be counted, logged or fed back in later.
      val attempted =
        Stream.emits(backlog)
          .through(client.systemOneEitherPipe(questions, maxConcurrent = 4))
          .fold((0, 0)) {
            case ((ok, failed), (_, Right(_))) => (ok + 1, failed)
            case ((ok, failed), (state, Left(e))) =>
              println(s"  failed: ${state.take(12)}… ${e.getClass.getSimpleName}")
              (ok, failed + 1)
          }
          .evalMap((ok, failed) => IO.println(s"attempted → $ok answered, $failed failed"))

      Stream.eval(IO.println("ordered →")) ++ ordered ++ attempted
    }.compile.drain
