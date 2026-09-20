package examples

import typesafe.ClientConfig

/** Where an example gets its client settings, and how to give them back.
  *
  * Every example in this folder runs either way:
  *
  *   - with `TYPESAFE_API_KEY` set, against the real API;
  *   - without it, against a [[FakeApi]] started on a free port, so a checkout with no credentials
  *     still shows what the SDK does.
  *
  * Nothing here is part of the SDK: it only keeps the examples from repeating the same eight lines.
  *
  * @param config  what to build the client from
  * @param release stops the fake API; a no-op when the real one is in use
  */
final case class Demo(config: ClientConfig, release: () => Unit)

object Demo:

  /** Settings for one example. `failFirst` asks the fake to reject that many requests with a `503`
    * before answering, which forces the retry policy to show itself; asking for it always uses the
    * fake, since the real API cannot be told to fail on demand.
    */
  def open(failFirst: Int = 0): Demo =
    val key = sys.env.get("TYPESAFE_API_KEY").map(_.trim).filter(_.nonEmpty)
    if key.isDefined && failFirst == 0 then
      println("→ live API (TYPESAFE_API_KEY is set)")
      Demo(ClientConfig(), () => ())
    else
      val api = FakeApi.start(failFirst = failFirst)
      val why = if failFirst > 0 then "this example needs a server that fails on demand"
                else "set TYPESAFE_API_KEY to use the real one"
      println(s"→ local fake API at ${api.baseUrl} ($why)")
      Demo(ClientConfig(apiKey = Some("fake-key"), baseUrl = Some(api.baseUrl)), () => api.stop())

  /** [[open]], with the release taken care of. */
  def run[A](failFirst: Int = 0)(body: ClientConfig => A): A =
    val demo = open(failFirst)
    try body(demo.config)
    finally demo.release()
