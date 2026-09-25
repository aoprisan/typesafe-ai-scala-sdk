package examples.direct

import examples.Demo
import ox.*
import ox.flow.Flow
import scala.concurrent.duration.*
import typesafe.*
import typesafe.oxdirect.*

/** A batch as an Ox `Flow`: bounded parallelism, a quota respected on the way in, and every state
  * paired with its outcome so one bad element does not cost the run.
  *
  * Rate limiting stays Ox's job — `Flow#throttle` is built in, so unlike the fs2 module there is no
  * token bucket to ship.
  *
  * {{{
  * sbt "examplesOx/runMain examples.direct.oxFlow"
  * }}}
  */
@main def oxFlow(): Unit =
  val demo = Demo.open()

  val urgent    = Noul("The message conveys urgency").named("is_urgent")
  val questions = Questions.of(urgent)
  val backlog   = (1 to 10).toVector.map(i => s"Ticket #$i: payouts have failed, please help.")

  try
    supervised {
      val client = TypeSafeOx.useInScope(demo.config)

      // In input order, four calls in flight, no more than five started a second.
      val started = System.nanoTime()
      val answers = Flow
        .fromIterable(backlog)
        .throttle(5, 1.second)
        .systemOnePar(client, questions, maxConcurrent = 4)
        .runToList()
      println(f"ordered   → ${answers.size} answers in ${(System.nanoTime() - started) / 1e6}%.0f ms")
      answers.zipWithIndex.foreach((r, i) => println(f"  #${i + 1}%-3d urgency ${r(urgent).noul}%.3f"))

      // Each state with its outcome, and the failure side typed rather than Throwable, so sorting
      // the wreckage afterwards is a match the compiler checks.
      val outcomes = Flow
        .fromIterable(backlog)
        .systemOneParEither(client, questions, maxConcurrent = 4)
        .runToList()
      val (failed, ok) = outcomes.partitionMap((state, outcome) => outcome.left.map(state -> _))
      println(s"attempted → ${ok.size} answered, ${failed.size} failed")
      failed.foreach((state, e) => println(s"  ${state.take(12)}… ${e.getClass.getSimpleName}: ${e.getMessage}"))
    }
  finally demo.release()
