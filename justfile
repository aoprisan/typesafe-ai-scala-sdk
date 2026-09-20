default: test

test:
    sbt test

compile:
    sbt compile

fmt:
    sbt scalafmtAll

# run every sample in examples/ (against a local fake API unless TYPESAFE_API_KEY is set)
examples:
    sbt "examples/runMain examples.triage" \
        "examples/runMain examples.typedState" \
        "examples/runMain examples.asyncCalls" \
        "examples/runMain examples.errorsAndRetries" \
        "examples/runMain examples.configuration" \
        "examplesCatsEffect/runMain examples.catseffect.Basics" \
        "examplesCatsEffect/runMain examples.catseffect.TypedFailures" \
        "examplesCatsEffect/runMain examples.catseffect.WatchingRetries" \
        "examplesFs2/runMain examples.streams.BatchOfTickets" \
        "examplesFs2/runMain examples.streams.StayingInQuota" \
        "examplesMonix/runMain examples.monixtask.MonixBasics" \
        "examplesOx/runMain examples.direct.oxScoped" \
        "examplesOx/runMain examples.direct.oxFlow"

# live smoke test; needs TYPESAFE_API_KEY
live:
    sbt "examples/runMain examples.triage"

publish-local:
    sbt publishLocal
