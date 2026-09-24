# Examples

Runnable samples for [typesafe-sdk-scala](../README.md), one sbt module per effect system.

**They run without an API key.** Each example asks [`Demo`](core/src/main/scala/examples/Demo.scala)
where to point the client: the real API when `TYPESAFE_API_KEY` is set, and otherwise a
[`FakeApi`](core/src/main/scala/examples/FakeApi.scala) — a JDK `HttpServer` started on a free port
that speaks the same wire format and answers with canned, deterministic numbers. So a fresh checkout
can run everything, and the same command runs it for real once you have a key:

```sh
sbt "examples/runMain examples.triage"                      # against the fake API
TYPESAFE_API_KEY=sk-… sbt "examples/runMain examples.triage" # against the real one
```

A few examples are about failure — a `429`, a malformed body, a retry that has to wait — and those
always use the fake, because the real API cannot be asked to misbehave on cue.

## The samples

### Core — no dependencies beyond the JDK (`examples`)

| Run                                             | Shows                                                                                    |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `examples/runMain examples.triage`              | the quick start: named questions, typed answers, routing on confidence                     |
| `examples/runMain examples.typedState`          | `derives ToJson` state, JSON instructions and rubrics, a choice mapped back onto an enum   |
| `examples/runMain examples.asyncCalls`          | blocking vs `Future` vs `CompletableFuture`, and what cancelling one does                  |
| `examples/runMain examples.errorsAndRetries`    | every failure the SDK can raise, retries, `Retry-After`, a custom `retryIf`                |
| `examples/runMain examples.configuration`       | `ClientConfig`, per-call `CallOptions`, `RawQuestion`, `extraBody`, listing models         |
| `examples/runMain examples.rubrics`             | a case class as the rubric: `derives Rubric`, an enum as a choice, `client.ask[TicketTriage]`|
| `examples/runMain examples.recordAndReplay`     | recording responses, replaying them with no key or network, and a `ReplayMissException`    |

### Cats Effect (`examplesCatsEffect`)

| Run                                                            | Shows                                                              |
| -------------------------------------------------------------- | ------------------------------------------------------------------ |
| `examplesCatsEffect/runMain examples.catseffect.Basics`         | `Resource`, calls as descriptions, a bounded `parTraverseN` fan-out |
| `examplesCatsEffect/runMain examples.catseffect.TypedFailures`  | `systemOneEither` and an exhaustive match the compiler checks       |
| `examplesCatsEffect/runMain examples.catseffect.WatchingRetries`| `withOnRetry` as the seam for your logging, `withRandom` for a seeded schedule |

### fs2 (`examplesFs2`)

| Run                                                      | Shows                                                                   |
| -------------------------------------------------------- | ------------------------------------------------------------------------ |
| `examplesFs2/runMain examples.streams.BatchOfTickets`    | `systemOnePipe` in input order, `systemOneEitherPipe` for a run that must finish |
| `examplesFs2/runMain examples.streams.StayingInQuota`    | `systemOneThrottledEitherPipe`: bounding how fast calls *start*, not just how many are in flight |

### Monix (`examplesMonix`)

| Run                                                      | Shows                                                           |
| -------------------------------------------------------- | ---------------------------------------------------------------- |
| `examplesMonix/runMain examples.monixtask.MonixBasics`   | `TypeSafeClientTask.use`, `parSequenceN`, cancelling a call       |

Monix 3.x is built on Cats Effect 2, so it cannot share a classpath with the Cats Effect 3 modules —
which is why these samples are their own module rather than living next to the others.

### Ox — direct style, JDK 21+ (`examplesOx`)

| Run                                                | Shows                                                                      |
| --------------------------------------------------- | ---------------------------------------------------------------------------- |
| `examplesOx/runMain examples.direct.oxScoped`      | a supervised scope, two `fork`s, `systemOneEither` inside an `either` block    |
| `examplesOx/runMain examples.direct.oxFlow`        | `Flow#throttle` plus `systemOnePar` / `systemOneParEither` over a backlog      |

On a JDK older than 21 this module is left out of the build entirely, so `sbt compile` stays green —
it just covers one module fewer.

## Notes

- The samples are aggregated into the root project, so `sbt test` compiles them. A change to the API
  they document cannot land while they still describe the old one.
- Nothing here is published to Maven Central; `publish / skip := true` for every example module.
- The SDK logs one line per request and response through `System.Logger` (logger name `typesafe`).
  sbt prints anything on stderr with an `[error]` prefix, which is noise rather than failure — quiet
  it with `java.util.logging.Logger.getLogger("typesafe").setLevel(java.util.logging.Level.WARNING)`,
  or route it to your own backend with `slf4j-jdk-platform-logging`.
