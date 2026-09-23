package examples

import java.nio.file.Files
import typesafe.*

/** Tests that call System One are slow, cost money and need a key. Record the answers once, then
  * replay them: no network, no key, and a request that was never recorded fails instead of
  * quietly spending.
  *
  * The same switch works from the environment, without touching the code under test:
  *
  * {{{
  * TYPESAFE_RECORD=src/test/resources/cassettes sbt test   # live: each answer is kept
  * TYPESAFE_REPLAY=src/test/resources/cassettes sbt test   # offline
  * sbt "examples/runMain examples.recordAndReplay"
  * }}}
  */
@main def recordAndReplay(): Unit = Demo.run() { config =>
  val urgent = Noul("The message conveys urgency").named("is_urgent")
  val questions = Questions.of(urgent)
  val ticket = "The payout failed again."
  val dir = Files.createTempDirectory("typesafe-cassettes")

  // 1. Record: a live call, and its response kept at <dir>/<key>.json.
  val recording = TypeSafeClient(config.copy(record = Some(dir)))
  try
    val live = recording.systemOne(ticket, questions)
    println(s"recorded  ${live(urgent).noul}")
  finally recording.close()
  val key = Cassette.key(ticket, recording.defaultModel, questions)
  println(s"          → ${Cassette.path(dir, key)}")

  // 2. Replay: no API key and no server; the answer comes from the file.
  val replaying = TypeSafeClient(ClientConfig(replay = Some(dir)))
  val again = replaying.systemOne(ticket, questions)
  println(s"replayed  ${again(urgent).noul} (attempts: ${again.meta.attempts})")

  // 3. Anything that was not recorded — here, another state — is refused, not sent.
  try replaying.systemOne("Something new.", questions)
  catch case e: ReplayMissException => println(s"not recorded → ${e.path.getFileName}")
}
