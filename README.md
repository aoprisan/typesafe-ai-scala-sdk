# typesafe-sdk-scala

Scala 3 client for the [TypeSafe AI](https://typesafe.ai) **System One** API: send a `state` plus named,
typed questions and get typed answers back.

| Question | Answer                                                                  |
| -------- | ----------------------------------------------------------------------- |
| `Noul`   | probability of "yes" (0–1)                                              |
| `Choice` | selected label, per-label probabilities, confidence                     |
| `Score`  | probability-weighted level, legend, per-level probabilities, confidence |

- **No runtime dependencies.** It uses the JDK `HttpClient` and a small internal JSON AST, so it won't
  pull a circe/jsoniter/sttp version into your build.
- **Same behaviour as the official Python SDK** (`typesafe-sdk` 0.7.1): environment variables,
  defaults, retry semantics, error classification and forward-compatible decoding.
- Scala 3.3 LTS, JDK 17+ (the Ox binding alone needs 21).
- Two flavours per call in the core, blocking and Scala `Future`; the effect bindings add calls you
  can cancel.
- Optional effect bindings: **Cats Effect**, **fs2**, **Monix** and **Ox**, each in its own artifact
  so the core stays dependency-free.

> Unofficial. Not affiliated with TypeSafe AI.

## Install

```scala
libraryDependencies += "io.github.aoprisan" %% "typesafe-sdk-scala" % "0.4.0"
```

Effect bindings are separate artifacts; add one only if you want it. Each depends on the core.

| Artifact                          | Adds                                         | Pulls in                 |
| --------------------------------- | -------------------------------------------- | ------------------------ |
| `typesafe-sdk-scala`              | blocking and `Future` calls                   | nothing                  |
| `typesafe-sdk-scala-cats-effect`  | `TypeSafeClientF[F]` for any `Async[F]`       | cats-effect 3            |
| `typesafe-sdk-scala-fs2`          | pipes for streams of states                   | fs2 3 (and the above)    |
| `typesafe-sdk-scala-monix`        | `TypeSafeClientTask`                          | monix-eval 3             |
| `typesafe-sdk-scala-ox`           | direct style: scopes, `Flow`, `Either`        | ox 1 (JDK 21+)           |

```scala
libraryDependencies += "io.github.aoprisan" %% "typesafe-sdk-scala-cats-effect" % "0.4.0"
libraryDependencies += "io.github.aoprisan" %% "typesafe-sdk-scala-fs2"         % "0.4.0"
libraryDependencies += "io.github.aoprisan" %% "typesafe-sdk-scala-monix"       % "0.4.0"
libraryDependencies += "io.github.aoprisan" %% "typesafe-sdk-scala-ox"          % "0.4.0"
```

Monix 3.x is built on Cats Effect 2, so the Monix and Cats Effect bindings cannot share a classpath:
pick the one your application already uses.

Ox needs a Java 21 runtime, so that module is compiled at `-release 21` and is left out of the
build entirely on an older JDK — everything else stays on 17.

All four effect bindings are new in 0.2.0, along with the `Either`-returning calls, the retry
observer and the throttled fs2 pipes; the core has been on Maven Central since 0.1.0.
See [RELEASING.md](RELEASING.md) for how releases are cut.

0.3.0 adds recording and replaying responses and rubrics derived from a case class (both below).

0.4.0 tightens the API before it settles, and breaks source compatibility to do it: see
[Upgrading to 0.4.0](#upgrading-to-040).

## Quick start

Name each question once and read its answer back with the right type: no casting, no string keys.

```scala
import typesafe.*

val client = TypeSafeClient() // TYPESAFE_API_KEY

val department = Choice(
  "Which team should handle this",
  "billing"   -> "Payment or subscription issues",
  "technical" -> "Bugs or integration problems",
  "sales"     -> "Pricing or account questions"
).named("department")                                   // Asked[ChoiceAnswer]
val frustration = Score("How frustrated the customer appears",
  "Calm", "Frustrated but civil", "Very angry").named("frustration")  // Asked[ScoreAnswer]
val urgent = Noul("The message conveys urgency").named("is_urgent")   // Asked[NoulAnswer]

val res = client.systemOne(
  "I've been trying to connect my Stripe account for 3 days. Please help ASAP.",
  Questions.of(department, frustration, urgent)
)

res(department).choice      // String
res(department).confidence  // Double
res(frustration).score      // Double
res(urgent).isYes(0.8)      // Boolean
res.get(urgent)             // Option[NoulAnswer]
```

`sbt "examples/runMain examples.triage"` runs this: against the live API when `TYPESAFE_API_KEY` is
set, and against a local fake server when it is not. See [examples/](examples/) for the rest.

String keys work too: `Questions("is_urgent" -> Noul("…"))` with `res.noul("is_urgent")`.

Questions are built only through these constructors and `.named`, so a handle's answer type always
matches its question: `Asked[ScoreAnswer]("x", Noul("…"))` does not compile. Instructions that encode
to `null` — a `None` — are left out rather than sent as `null`, and `Noul()` asks without any.

### State

`state` is anything with a `ToJson` instance: `String`, `Json`, numbers, collections, or your own case
classes with `derives ToJson`:

```scala
enum Channel derives ToJson:
  case Email, Chat

final case class Ticket(subject: String, priority: Option[Int], messages: List[String], channel: Channel)
    derives ToJson

client.systemOne(Ticket("Payouts", None, List("…"), Channel.Chat), questions)
// state = {"subject":"Payouts","messages":["…"],"channel":"Chat"}   (None fields omitted)
```

If you already use circe or jsoniter, print your value and use `Json.unsafeParse(text)`.

### Structured instructions and rubrics

```scala
Score(Json.obj("task" -> "rate tone", "ignore" -> List("signatures")))
  .level(Json.obj("level" -> "neutral"))
  .level("hostile")
Noul("Is this a refund request?").describeTrue("Explicit ask for money back")
Choice.labels("Sentiment", "positive", "neutral", "negative")
```

### Typed choices

```scala
enum Dept:
  case billing, technical, sales

res(department).as(Dept.valueOf)   // Either[Throwable, Dept]
```

Or let the enum be the options: an enum that `derives RubricChoice` (below) offers its cases as the
labels, and `RubricChoice[Dept].choice("Which team")` builds the `Choice`.

### Rubrics as case classes

A case class can be the whole rubric. Each field is a question, asked under the snake_case of its name
(`isUrgent` → `is_urgent`), and the field's type is what the answer decodes into:

```scala
import typesafe.*
import typesafe.rubric.*

case class Triage(
  @noul("The message conveys urgency", yes = "A deadline or ASAP", no = "Routine")
  isUrgent: NoulAnswer,
  @choice("Which team should handle this")
  department: ChoiceOf[Department],        // the enum, plus the distribution it was picked from
  @score("How frustrated the customer appears", "Calm", "Frustrated but civil", "Very angry")
  frustration: ScoreAnswer,
  @noul("The customer asks for their money back") @named("wants_refund")
  refund: Double                           // just the probability of "yes"
) derives Rubric

enum Department derives RubricChoice:      // offered as billing, technical, needs_human
  @option("Payment or subscription issues") case Billing
  @option("Bugs or integration problems") case Technical
  case NeedsHuman

val triage: Triage = client.ask[Triage]("The payout failed again. Refund me.")
triage.department.value     // Department.Billing
triage.refund               // Double
Rubric[Triage].questions    // what goes on the wire
```

| Annotation                        | Field type                                                          |
| --------------------------------- | ------------------------------------------------------------------- |
| `@noul("…", yes = "…", no = "…")` | `NoulAnswer`, or `Double` for the probability; `yes`/`no` optional   |
| `@choice("…")`                    | an enum deriving `RubricChoice`, or `ChoiceOf` one                  |
| `@choice("…", "label", …)`        | `ChoiceAnswer`, or `String` for the selected label                  |
| `@score("…", "level", …)`         | `ScoreAnswer`, or `Double` for the score; levels lowest first        |
| `@named("…")`                     | a question name (or, on an enum case, a label) other than the snake_case one |

Mistakes are compile errors rather than a `None` at runtime: an answer read as the wrong type, a field
with no question annotation, a score without levels, a `String` choice without labels, two fields asked
under one name, an enum case that carries data. A response the case class cannot hold — an answer
missing, of another type, or a label the enum does not have — is a `ResponseValidationException` whose
`fieldPath` names it (`answers.department.choice`).

`askFuture` and `askEither` (see [Errors](#errors)) are the other flavours; the Cats Effect and
Monix clients have `ask` and `askEither` too, and the fs2 and Ox modules run a rubric over a batch. `Rubric[Triage].fromResponse(res)` decodes a response you already
have, and both traits can be implemented by hand.

### Per-call options

```scala
client.systemOne(state, questions, CallOptions(
  model     = Some("jev-latest"),
  timeout   = Some(3.seconds),
  retry     = Some(RetryPolicy.none),
  headers   = Map("X-Tenant" -> "acme"),
  extraBody = VectorMap("some_new_field" -> Json.Bool(true))   // shallow-merged last
))
```

Authentication and SDK-identification headers cannot be overridden.

### Async

```scala
val f: Future[SystemOneResponse] = client.systemOneFuture(state, questions)
val t: Future[Triage]            = client.askFuture[Triage](state)
val m: Future[ListModelsResponse] = client.models.listFuture()
```

The `Future` fails with the typed `TypeSafeException`, never a Java wrapper, so `recover` can match on
it. The retry loop behind it is non-blocking, so no call ever parks a thread. Java callers can take a
`CompletionStage` with `scala.jdk.FutureConverters` (`f.asJava`).

A Scala `Future` cannot be cancelled, so a `systemOneFuture` runs to completion either way. For a
call you can cancel, use an effect binding: the [Cats Effect](#cats-effect), [Monix](#monix) and
[Ox](#ox) clients abort the HTTP exchange in flight, and the retries with it, when their fiber, task
or fork is cancelled.

## Cats Effect

```scala
import cats.effect.{IO, IOApp}
import typesafe.*
import typesafe.catseffect.*

object Main extends IOApp.Simple:
  val urgent = Noul("The message conveys urgency").named("is_urgent")

  def run = TypeSafeClientF.resource[IO]().use { client =>
    client.systemOne("My payouts have failed for 3 days!", Questions.of(urgent))
      .map(_(urgent).noul)
      .flatMap(IO.println)
  }
```

`TypeSafeClientF[F]` works for any `Async[F]`, not just `IO`. Calls are descriptions — nothing is sent
until the `F` runs — failures are the SDK's own exceptions rather than `CompletionException` wrappers,
and cancelling the fiber aborts the request and the retries with it.

The retry loop here is native, not a wrapper: attempts are bounded with `F`'s `timeout`, backoff waits
run on `F`'s scheduler (so a cancelled call stops waiting immediately, and no JDK timer thread is
involved), and elapsed time against the retry budget comes from `F`'s clock. Only a single attempt is
borrowed from the core client. What to send, what an answer means and *when* to retry stay in the
shared, dependency-free core, so the two loops cannot drift apart — and because the wait is on `F`,
the schedule is testable on virtual time:

```scala
TestControl.executeEmbed(client.systemOne(state, questions))   // 30s of backoff, 0s of wall clock
```

| Entry point                        | Gives                                                  |
| ---------------------------------- | ------------------------------------------------------ |
| `TypeSafeClientF.resource[F](cfg)` | `Resource[F, TypeSafeClientF[F]]`, closed on release    |
| `TypeSafeClientF.withApiKey[F](k)` | the same with everything else defaulted                 |
| `TypeSafeClientF.fromClient[F](c)` | lifts a client you own and keep closing yourself        |
| `client.effect[F]`                 | the same, as syntax on a plain `TypeSafeClient`         |

### Failures in the value

`F[A]` has no typed error channel, but [`TypeSafeException`](#errors) is sealed, so the SDK can hand
its own failures back as a `Left` the compiler checks:

```scala
client.systemOneEither(state, questions).flatMap {
  case Right(res)                => IO.println(res(urgent).noul)
  case Left(e: ApiException)     => IO.println(s"api said ${e.status}")
  case Left(e: TimeoutException) => IO.println(s"gave up after ${e.timeout}")
  case Left(e)                   => IO.raiseError(e)
}
```

Drop a case and the match fails to compile, which is the part `.attempt` on a `Throwable` cannot give
you. `models.listEither()` is the same for the models call.

Only the SDK's own failures move: a bug in a `ToJson` instance stays in the error channel, and a
cancelled fiber is still cancelled rather than a `Left`.

### Watching the retries

The SDK carries no logging or metrics dependency, so `withOnRetry` is the seam for log4cats, otel4s
or a bare counter:

```scala
TypeSafeClientF.resource[IO]().map(_.withOnRetry { ev =>
  logger.warn(s"${ev.endpoint} attempt ${ev.attempt} failed, waiting ${ev.delay}", ev.error)
})
```

A `RetryEvent` carries the `endpoint`, the 1-based `attempt` that failed, the `delay` before the next
one and the `error`. It fires only when another attempt really is coming — the failure that exhausts
the policy is raised, not announced — and the observer cannot change the outcome: its result is
discarded and its own failure is swallowed, because a broken counter should not fail a request that
was about to succeed.

`withRandom` replaces the ambient `ThreadLocalRandom` that feeds the backoff jitter, so a seeded
`cats.effect.std.Random` plus `TestControl` pins a jittered schedule exactly instead of to a range:

```scala
Random.scalaUtilRandomSeedInt[IO](42).map(client.withRandom)
```

## fs2

The API has no streaming endpoint; what streams is your own workload — a table, a queue, a file of
tickets — run through System One with a bounded number of calls in flight.

```scala
import cats.effect.IO
import fs2.Stream
import typesafe.*
import typesafe.fs2streams.*

TypeSafeStream.stream[IO]().flatMap { client =>
  Stream.emits(tickets).through(client.systemOnePipe(questions, maxConcurrent = 8))
}.map(_(urgent).noul).compile.toVector
```

- `systemOnePipe` — answers in input order; the first failure fails the stream.
- `systemOneUnorderedPipe` — answers as they arrive.
- `systemOneEitherPipe` — emits `(state, Either[TypeSafeException, SystemOneResponse])`, so one bad
  state does not sink a long run. The failure side is the sealed SDK hierarchy, as with Cats Effect's
  `systemOneEither`; a failure that is not the SDK's own still fails the stream.
- `askPipe[R]`, `askUnorderedPipe[R]`, `askEitherPipe[R]` — the same three for a
  [rubric](#rubrics-as-case-classes): `Stream.emits(tickets).through(client.askPipe[Triage](maxConcurrent = 8))`
  emits a `Triage` per ticket.

`TypeSafeStream.stream[F](cfg)` is `TypeSafeClientF.resource[F](cfg)` as a single-element stream, and
`TypeSafeStream.withApiKey[F](k)` the same with everything else defaulted.

### Staying inside a quota

`maxConcurrent` bounds how many calls are *in flight*; a per-minute quota is a bound on how fast they
are *started*. Without one, a burst walks straight into `429 Too Many Requests` and the retry policy
then spends the call's budget waiting out a limit the stream could have respected in the first place.
fs2 has no token bucket of its own, so the module brings one:

```scala
Stream.emits(tickets).through(
  client.systemOneThrottledPipe(questions, every = 500.millis, burst = 10, maxConcurrent = 8)
) // 120 calls a minute, in bursts of up to 10
```

`every` is the steady spacing between call starts and `burst` how many may go at once after an idle
stretch (the default of 1 spaces every call evenly). `systemOneThrottledEitherPipe` is the same with
`systemOneEitherPipe`'s outcomes — the pairing you want for a long run against a rate-limited key.

The bucket is per pipe and a fresh one is taken each time the stream runs.

## Monix

```scala
import monix.execution.Scheduler.Implicits.global
import typesafe.*
import typesafe.monixeffect.*

val urgent = Noul("The message conveys urgency").named("is_urgent")

val task = TypeSafeClientTask.resource().use { client =>
  client.systemOne("My payouts have failed for 3 days!", Questions.of(urgent))
}
task.runToFuture.foreach(res => println(res(urgent).noul))
```

`resource` is a (Cats Effect 2) `Resource[Task, TypeSafeClientTask]` that closes the client on
success, failure or cancellation, as `TypeSafeClientF.resource` does; `withApiKey(k)` is the same with
everything else defaulted, and `fromClient` lifts a client whose lifetime you manage yourself. `systemOneEither`,
`askEither` and `models.listEither` hand the SDK's own failures back as a value, as in the Cats
Effect binding. Cancelling the
`Task` aborts the request in flight, like the Cats Effect binding.

Unlike that binding, this one drives the core's own retry loop rather than a `Task`-native one: Monix
3.x sits on Cats Effect 2 and is no longer developed, so a second loop to keep in step with the Python
SDK's semantics would be maintenance without a return. Backoff therefore runs on the JDK's timer
rather than on your `Scheduler`.

### Models


```scala
client.models.list().models.foreach(m => println(s"${m.name} (${m.releaseDate})"))  // a LocalDate
```

A release date that is not an ISO date (`yyyy-mm-dd`) is a `ResponseValidationException` naming it,
e.g. `models[0].release_date`.

## Ox

[Ox](https://ox.softwaremill.com) is direct style on JDK 21 virtual threads, so there is no wrapper
type here and that is the point: blocking is cheap on a virtual thread, so the core's own blocking
API *is* the Ox API. `client.systemOne(...)` inside a `fork` parks a virtual thread and nothing else,
and the core already honours interruption — it cancels the HTTP exchange and re-arms the interrupt
flag — which is exactly what a supervised scope needs when it winds a fork down.

```scala
import ox.*
import typesafe.*
import typesafe.oxdirect.*

val urgent = Noul("The message conveys urgency").named("is_urgent")

supervised {
  val client = TypeSafeOx.useInScope()       // closed when the scope ends, like Ox's own useInScope
  val a = fork { client.systemOne(ticketA, Questions.of(urgent)) }
  val b = fork { client.systemOne(ticketB, Questions.of(urgent)) }
  (a.join(), b.join())
}
```

What the module adds is the rest: a lifetime tied to a scope and `Flow` operators for a batch. The
sealed failures as `Either` come from the core client itself (see [Errors](#errors)):

```scala
client.systemOneEither(ticket, questions)   // Either[TypeSafeException, SystemOneResponse]
client.modelsEither()                       // the same for the models call
```

and drop straight into an `either` block:

```scala
import ox.either.*

val summary: Either[TypeSafeException, String] = either:
  val res = client.systemOneEither(ticket, questions).ok()
  s"urgency ${res(urgent).noul}"
```

`Flow` gets the same three shapes as the fs2 pipes, with the failure side typed rather than
`Throwable`:

```scala
supervised {
  val client = TypeSafeOx.useInScope()
  Flow.fromIterable(tickets)
    .throttle(120, 1.minute)                                // Ox's own, no module code needed
    .systemOnePar(client, questions, maxConcurrent = 8)     // input order
    .runToList()
}
```

- `systemOnePar` — answers in input order; the first failure fails the flow.
- `systemOneParUnordered` — answers as they arrive.
- `systemOneParEither` — emits `(state, Either[TypeSafeException, SystemOneResponse])`.
- `askPar[R]`, `askParUnordered[R]`, `askParEither[R]` — the same three for a
  [rubric](#rubrics-as-case-classes): `Flow.fromIterable(tickets).askPar[Triage](client, maxConcurrent = 8)`.

Rate limiting stays Ox's job: `Flow#throttle` is built in, so unlike the fs2 module there is no
bucket to ship.

Only `TypeSafeException` is caught on the way into an `Either`. `InterruptedException` is how a scope
winds a fork down, so turning it into a `Left` would quietly swallow a cancellation; it stays an
exception, as do bugs.

## Examples

[examples/](examples/) holds a runnable sample per topic — triage, typed state, async, failures and
retries, configuration, and one or two per effect binding. They need no API key: without
`TYPESAFE_API_KEY` each one starts a local fake of the API and talks to that instead, so a fresh
checkout can run all of them, and the same command runs against the real API once a key is set.

```sh
sbt "examples/runMain examples.triage"                      # core
sbt "examplesCatsEffect/runMain examples.catseffect.Basics" # Cats Effect
sbt "examplesFs2/runMain examples.streams.BatchOfTickets"   # fs2
sbt "examplesMonix/runMain examples.monixtask.MonixBasics"  # Monix
sbt "examplesOx/runMain examples.direct.oxScoped"           # Ox (JDK 21+)
```

## Configuration

```scala
TypeSafeClient(ClientConfig(
  apiKey     = Some("…"),                // else TYPESAFE_API_KEY (required)
  baseUrl    = Some("https://…"),        // else TYPESAFE_BASE_URL, else https://api.typesafe.ai
  model      = Some("jev-latest"),       // else TYPESAFE_DEFAULT_MODEL, else jev-latest
  timeout    = 10.seconds,               // per attempt
  retry      = RetryPolicy(),
  headers    = Map.empty,
  httpClient = None,                     // your own java.net.http.HttpClient (proxy, executor, TLS)
  record     = None,                     // else TYPESAFE_RECORD: a directory to record responses into
  replay     = None                      // else TYPESAFE_REPLAY: a directory to replay responses from
))
```

Explicit values win; blank environment values are ignored. The client is `AutoCloseable`: call
`client.close()` when done, or use `scala.util.Using` (a no-op on JDK 17, where `HttpClient` isn't
closeable). A config's `toString` shows `[REDACTED]` in place of the API key and of any credential
header, so it is safe to log. `TypeSafeClient.either(config)` returns a `ConfigException` as a `Left`
instead of throwing it.

## Recording and replaying

Tests that call the API are slow, cost money and need a key. Record their answers once and replay them
after that:

```sh
TYPESAFE_RECORD=src/test/resources/cassettes sbt test   # live: each successful response is kept
TYPESAFE_REPLAY=src/test/resources/cassettes sbt test   # offline: no network, no API key
```

```scala
val client = TypeSafeClient(ClientConfig(replay = Some(Path.of("src/test/resources/cassettes"))))
```

- A response is kept at `<dir>/<key>.json`, where the key is the SHA-256 of the exact request body:
  state, model, questions in order, and any `extraBody` fields. Change any of them and it is a
  different recording. `Cassette.key(state, model, questions)` computes it.
- A request with no recording fails with `ReplayMissException` (`key`, `path`). A replaying client
  never falls back to the network, so a test cannot quietly start spending.
- A recording that no longer decodes is a `ResponseValidationException`, like a bad live body.
  Replayed responses report `meta.attempts == 0` and no headers; `models.list()` is not recorded, and
  a replaying client refuses it with `ConfigException`.
- Setting both is a `ConfigException`. Only System One calls that succeed and decode are recorded.
- The effect bindings record and replay too: they take the same `ClientConfig`.
- The files are the ones the Rust SDK records and `jev eval --cache` keeps — the same key, and the
  body as compact JSON in server order — so a cassette directory can be shared between them.

## Retries

`RetryPolicy()` matches the Python SDK:

- 2 retries after the first attempt,
- exponential backoff from 0.5 s to 5 s with 25 % subtractive jitter,
- retries on 408, 429 and 500–599 (including TypeSafe's `529 Overloaded`), connection errors and timeouts,
- honours `retry-after-ms` and `Retry-After` (seconds or HTTP date),
- a 30 s total budget per call: it stops *before* a wait that would exceed it,
- retries send `X-TypeSafe-Retry-Count`.

```scala
RetryPolicy(maxRetries = 5, backoffInitial = 200.millis, budget = Some(10.seconds))
  .retryIf { case e: ApiException => e.status == 409; case _ => false }
```

## Errors

All failures extend the sealed `TypeSafeException`:

| Exception                     | When                                                                    |
| ----------------------------- | ----------------------------------------------------------------------- |
| `ConfigException`             | missing API key, invalid base URL, non-positive timeout, invalid retry policy, record and replay both set |
| `InvalidRequestException`     | no questions, empty score or choice criteria, malformed raw question, unencodable state |
| `ApiException`                | non-2xx after retries; `kind`, `detail`, `body`, `requestId`, `retryAfter` |
| `ConnectionException`         | no response (DNS, connect, reset, read)                                 |
| `TimeoutException`            | an attempt exceeded its timeout                                         |
| `ResponseValidationException` | 2xx body missing required data; `fieldPath` like `answers.tone.confidence` |
| `ReplayMissException`         | replaying, and this request was never recorded; `key`, `path`            |

```scala
try client.systemOne(state, questions)
catch
  case e: ApiException if e.kind == ApiErrorKind.RateLimit =>
    println(s"rate limited, retry after ${e.retryAfter}, request ${e.requestId}")
  case e: ResponseValidationException => println(s"bad field ${e.fieldPath}")
```

The exceptions are the SDK's to raise: their constructors are not public, so a failure you catch
always carries what the SDK put there. A test that needs one can get it from a mock server, as the
SDK's own tests do.

`ApiException(status, kind)` is also an extractor, for matching on the status alone:
`case ApiException(429, _) =>` or `case ApiException(_, ApiErrorKind.Authentication) =>`.

Error messages from FastAPI-style validation bodies are flattened, e.g.
`questions.frustration.criteria: List should have at least 2 items`.

Because the hierarchy is sealed, every call can also hand these back as a value, and the compiler then
checks the match for you:

```scala
client.systemOneEither(state, questions) match   // Either[TypeSafeException, SystemOneResponse]
  case Right(res)                   => println(res(urgent).noul)
  case Left(ApiException(429, _))   => println("rate limited")
  case Left(e: TimeoutException)    => println(s"gave up after ${e.timeout}")
  case Left(e)                      => throw e

client.askEither[Triage](state)      // Either[TypeSafeException, Triage]
client.modelsEither()                // Either[TypeSafeException, ListModelsResponse]; also models.listEither()
TypeSafeClient.either(config)        // Either[ConfigException, TypeSafeClient]
```

Only `TypeSafeException` moves into the `Left`: `InterruptedException` (how a blocked caller is
cancelled) and bugs are still thrown. The Cats Effect and Monix clients have the same methods in `F`
and `Task`.

## Upgrading to 0.4.0

0.4.0 breaks source compatibility with 0.3.x in the places below; each is a mechanical change.

| 0.3.x                                                         | 0.4.0                                                                  |
| ------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `Noul(Some(json), …)`, `Score(…).copy(…)`, `Asked[A](name, q)` | the typed constructors (`Noul("…")`, `Score("…", levels*)`, …) and `.named` |
| `Noul(None: Option[String])` sent `"instructions": null`       | instructions that encode to `null` are left out; `Noul()` for none     |
| `systemOneAsync`, `askAsync`, `models.listAsync` (`CompletableFuture`) | `systemOneFuture`, `askFuture`, `models.listFuture`; an effect binding to cancel |
| `new ApiException(…)` and the other exception constructors      | raised by the SDK only; match on them as before, `ApiException(status, kind)` included |
| `answer.kind == "noul"`                                       | `answer.kind == AnswerKind.Noul`; `kind.wire` is the string            |
| `model.releaseDate: String`                                   | `java.time.LocalDate`                                                  |
| `typesafe.RubricSupport`                                       | `typesafe.internal.RubricSupport` (generated code only; not an API)    |
| `TypeSafeClientTask.use(cfg)(f)`, `TypeSafeClientTask.create(cfg)` | `TypeSafeClientTask.resource(cfg).use(f)`                         |
| `TypeSafeStream.resource[F](cfg)`                             | `TypeSafeStream.stream[F](cfg)`                                        |
| `TypeSafeOx.inScope(cfg)`                                     | `TypeSafeOx.useInScope(cfg)`                                           |
| `systemOnePar(…, parallelism = n)` and the other Ox operators    | `maxConcurrent = n`, as in the fs2 pipes                               |
| `systemOneAttemptPipe`, `systemOneThrottledAttemptPipe`         | `systemOneEitherPipe`, `systemOneThrottledEitherPipe` (`Left` is `TypeSafeException`) |
| `typesafe.oxdirect.AskingEither`                              | `client.askEither[R]`, a member of `TypeSafeClient`                    |

`Constants.Version` now comes from the build (the git tag), so a snapshot reports itself as one.

## Forward compatibility

- Answer types this version does not know are skipped (logged at WARNING) and remain in `response.raw`.
- Unknown response fields are ignored.
- `RawQuestion(json)` sends a hand-built question; `extraBody` adds top-level fields.

## Logging

Uses `System.Logger` (logger name `typesafe`), so it routes to SLF4J/Logback via `slf4j-jdk-platform-logging`.
INFO shows one line per request and response; DEBUG shows headers and bodies. Secret headers are
redacted; bodies are not.

## Development

```sh
just test     # sbt test, across core and the effect modules
just examples # run every sample in examples/ against its local fake API
just live     # smoke test against the real API (needs TYPESAFE_API_KEY)
```

The build is `core` plus one module per effect system (`cats-effect`, `fs2`, `monix`, `ox`), and a
matching sample module under [examples/](examples/) for each of them; the effect modules reuse the
core's mock-API test harness, and the samples are aggregated so `sbt test` compiles them too. `ox` is compiled at `-release 21` and is
dropped from the aggregate on an older JDK, so `just test` stays green on 17 — it just covers one
module fewer. Releases are cut on 21 so that module is published too. The core exposes the call description, the decoders,
a single-attempt `sendOnce` and the retry decisions as `private[typesafe]` internals, which is what a
binding needs to run its own loop without re-deriving any semantics or widening the public API.

## License

MIT
