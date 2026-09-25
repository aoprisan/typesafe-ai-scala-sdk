package examples.streams

import cats.effect.{IO, IOApp, Resource}
import examples.Demo
import fs2.Stream
import scala.concurrent.duration.*
import typesafe.*
import typesafe.fs2streams.*

/** `maxConcurrent` bounds how many calls are *in flight*; a per-minute quota bounds how fast they
  * are *started*. Without the second bound a burst walks straight into `429`, and the retry policy
  * then spends the call's budget waiting out a limit the stream could have respected in the first
  * place.
  *
  * {{{
  * sbt "examplesFs2/runMain examples.streams.StayingInQuota"
  * }}}
  */
object StayingInQuota extends IOApp.Simple:

  private val urgent    = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)
  private val backlog   = (1 to 10).toVector.map(i => s"Ticket #$i: payouts have failed, please help.")

  private val settings: Resource[IO, ClientConfig] =
    Resource.make(IO(Demo.open()))(d => IO(d.release())).map(_.config)

  def run: IO[Unit] =
    Stream.resource(settings).flatMap(config => TypeSafeStream.stream[IO](config)).flatMap { client =>
      Stream.eval(IO.monotonic).flatMap { started =>
        Stream
          .emits(backlog)
          // One call started every 100 ms, up to 3 at once after an idle stretch, at most 4 in
          // flight: 600 calls a minute, in bursts of three. The bucket is per pipe, and a fresh one
          // is taken each time the stream runs.
          .through(
            client.systemOneThrottledEitherPipe(
              questions,
              every = 100.millis,
              burst = 3,
              maxConcurrent = 4
            )
          )
          .zipWithIndex
          .evalMap { case ((_, outcome), i) =>
            IO.monotonic.flatMap { now =>
              val mark = (now - started).toMillis
              val what = outcome.fold(e => e.getClass.getSimpleName, r => f"urgency ${r(urgent).noul}%.3f")
              IO.println(f"  +${mark}%5d ms  #${i + 1}%-3d $what")
            }
          }
      }
    }.compile.drain
