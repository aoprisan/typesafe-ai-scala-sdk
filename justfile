default: test

test:
    sbt test

compile:
    sbt compile

fmt:
    sbt scalafmtAll

# live smoke test; needs TYPESAFE_API_KEY
live:
    sbt "core/Test/runMain examples.triage"

publish-local:
    sbt publishLocal
