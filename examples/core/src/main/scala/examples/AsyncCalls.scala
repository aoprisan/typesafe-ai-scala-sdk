package examples

import java.util.concurrent.CompletionStage
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*
import typesafe.*

/** The same call in the two shapes the dependency-free core offers, blocking and Scala `Future` —
  * plus a `CompletionStage` for Java callers. A call you can cancel is an effect's job: the Cats
  * Effect, Monix and Ox modules abort the exchange in flight when their fiber, task or fork goes.
  *
  * {{{
  * sbt "examples/runMain examples.asyncCalls"
  * }}}
  */
@main def asyncCalls(): Unit = Demo.run() { config =>
  val client = TypeSafeClient(config)

  val urgent = Noul("The message conveys urgency").named("is_urgent")
  val questions = Questions.of(urgent)

  val tickets = Vector(
    "My payouts have failed for 3 days!",
    "Just checking whether you support SEPA.",
    "URGENT: the API is returning 500 for every request."
  )

  try
    // 1. Blocking. One thread parked per call, which is fine for a handful of them.
    val started = System.nanoTime()
    tickets.foreach { t =>
      val res = client.systemOne(t, questions)
      println(f"blocking  ${res(urgent).noul}%.3f  ${t.take(30)}…")
    }
    println(f"           ${(System.nanoTime() - started) / 1e6}%.0f ms in sequence")

    // 2. Scala Future: the calls overlap, and a failure arrives as the SDK's own exception rather
    //    than a Java wrapper, so `recover` can match on it.
    val fanOut = System.nanoTime()
    val all: Future[Vector[Double]] =
      Future.sequence(tickets.map(t => client.systemOneFuture(t, questions).map(_(urgent).noul)))
    val recovered = all.recover { case e: ApiException => println(s"api said ${e.status}"); Vector.empty }
    val nouls = Await.result(recovered, 1.minute)
    println(f"futures   ${nouls.map(n => f"$n%.3f").mkString(", ")}")
    println(f"           ${(System.nanoTime() - fanOut) / 1e6}%.0f ms in parallel")

    // 3. Java code wants a CompletionStage; the standard library converts the Future. Failures
    //    then follow the Java convention and come back wrapped in CompletionException.
    val stage: CompletionStage[SystemOneResponse] = client.systemOneFuture(tickets.head, questions).asJava
    println(f"java cs   ${stage.toCompletableFuture.join()(urgent).noul}%.3f")
  finally client.close()
}
