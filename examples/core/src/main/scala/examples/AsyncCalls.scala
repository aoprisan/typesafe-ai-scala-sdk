package examples

import java.util.concurrent.CompletableFuture
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import typesafe.*

/** The same call in the three shapes the dependency-free core offers: blocking, Scala `Future` and
  * `CompletableFuture` — plus what cancelling one actually does.
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

    // 3. CompletableFuture, for interop with Java code. Failures follow the Java convention and
    //    come back wrapped in CompletionException.
    val cf: CompletableFuture[SystemOneResponse] = client.systemOneAsync(tickets.head, questions)
    println(f"java cf   ${cf.join()(urgent).noul}%.3f")

    // Cancelling is honoured end to end: the exchange in flight is aborted and no retry follows.
    // (A Scala Future cannot be cancelled, which is why this one is the CompletableFuture.)
    val doomed = client.systemOneAsync(tickets.head, questions)
    doomed.cancel(true)
    println(s"cancelled → ${doomed.isCancelled}")
  finally client.close()
}
