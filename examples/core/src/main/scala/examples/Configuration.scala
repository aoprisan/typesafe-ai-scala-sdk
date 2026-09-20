package examples

import java.net.http.HttpClient
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.*
import typesafe.*

/** Settings that belong to the client, settings that belong to one call, and the two escape hatches
  * for an API that has moved on before this SDK has.
  *
  * {{{
  * sbt "examples/runMain examples.configuration"
  * }}}
  */
@main def configuration(): Unit = Demo.run() { demoConfig =>
  // Everything below is optional: `TypeSafeClient()` reads TYPESAFE_API_KEY, TYPESAFE_BASE_URL and
  // TYPESAFE_DEFAULT_MODEL on its own. Explicit values win over the environment.
  val client = TypeSafeClient(
    demoConfig.copy(
      model   = Some("jev-latest"),
      timeout = 8.seconds,                                   // per attempt, not per call
      retry   = RetryPolicy(maxRetries = 3, budget = Some(20.seconds)),
      headers = Map("X-Tenant" -> "acme"),                   // sent with every request
      // Bring your own HTTP client for a proxy, a custom executor or pinned TLS. The SDK only
      // insists on the headers that identify it and carry your key.
      httpClient = Some(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build())
    )
  )

  try
    println(s"client    → $client")

    // Which models the key can use. `releaseDate` is a string, exactly as the API sends it.
    client.models.list().models.foreach(m => println(s"  model ${m.name} (${m.releaseDate}) — ${m.description}"))

    val urgent = Noul("The message conveys urgency").named("is_urgent")

    // A question shape this SDK version does not model yet: hand-build the JSON and it is passed
    // through after a light sanity check.
    val raw = RawQuestion(
      Json.obj(
        "type"         -> "noul",
        "instructions" -> "Does the customer mention a deadline?",
        "criteria"     -> Json.obj("true" -> "A date, a time or a contractual SLA is named")
      )
    )

    val res = client.systemOne(
      "We go live on Friday and payouts still fail.",
      Questions.of(urgent) + ("deadline" -> raw),
      // Per-call overrides; anything left out falls back to the client's own settings.
      CallOptions(
        model     = Some("jev-latest"),
        timeout   = Some(3.seconds),
        retry     = Some(RetryPolicy.none),
        headers   = Map("X-Request-Source" -> "examples"),
        // Shallow-merged last over state/model/questions, for a body field newer than this SDK.
        extraBody = VectorMap("some_new_field" -> Json.Bool(true))
      )
    )

    println(s"urgent?   → ${res(urgent).noul}")
    println(s"deadline? → ${res.noul("deadline").map(_.noul).getOrElse("-")}")   // raw questions are read by name
  finally client.close()
}
