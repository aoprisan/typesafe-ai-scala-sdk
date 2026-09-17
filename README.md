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
- **Same behaviour as the official Python SDK** (`typesafe-sdk` 0.6.0): environment variables,
  defaults, retry semantics, error classification and forward-compatible decoding.
- Scala 3.3 LTS, JDK 17+.
- Three flavours per call: blocking, `CompletableFuture`, and Scala `Future`.

> Unofficial. Not affiliated with TypeSafe AI.

## Install

```scala
libraryDependencies += "io.github.aoprisan" %% "typesafe-sdk-scala" % "0.1.0"
```

Not published yet; use `sbt publishLocal` for now.

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

`sbt "Test/runMain examples.triage"` runs this against the live API.

String keys work too: `Questions("is_urgent" -> Noul("…"))` with `res.noul("is_urgent")`.

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
val f: Future[SystemOneResponse]            = client.systemOneFuture(state, questions)
val cf: CompletableFuture[SystemOneResponse] = client.systemOneAsync(state, questions)
```

The Scala `Future` fails with the typed `TypeSafeException`. The `CompletableFuture` follows the Java
convention and wraps failures in `CompletionException` / `ExecutionException`.

The retry loop is non-blocking (`CompletableFuture.delayedExecutor`), so wrapping the async variant in
cats-effect or ZIO (`IO.fromCompletableFuture`, `ZIO.fromCompletionStage`) needs no extra thread.

### Models

```scala
client.models.list().models.foreach(m => println(s"${m.name} (${m.releaseDate})"))
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
  httpClient = None                      // your own java.net.http.HttpClient (proxy, executor, TLS)
))
```

Explicit values win; blank environment values are ignored. Call `client.close()` when done (a no-op on
JDK 17, where `HttpClient` isn't closeable).

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
| `ConfigException`             | missing API key, non-positive timeout, invalid retry policy             |
| `InvalidRequestException`     | no questions, empty score criteria, malformed raw question, unencodable state |
| `ApiException`                | non-2xx after retries; `kind`, `detail`, `body`, `requestId`, `retryAfter` |
| `ConnectionException`         | no response (DNS, connect, reset, read)                                 |
| `TimeoutException`            | an attempt exceeded its timeout                                         |
| `ResponseValidationException` | 2xx body missing required data; `fieldPath` like `answers.tone.confidence` |

```scala
try client.systemOne(state, questions)
catch
  case e: ApiException if e.kind == ApiErrorKind.RateLimit =>
    println(s"rate limited, retry after ${e.retryAfter}, request ${e.requestId}")
  case e: ResponseValidationException => println(s"bad field ${e.fieldPath}")
```

Error messages from FastAPI-style validation bodies are flattened, e.g.
`questions.frustration.criteria: List should have at least 2 items`.

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
just test     # sbt test
just live     # smoke test against the real API (needs TYPESAFE_API_KEY)
```

## License

MIT
