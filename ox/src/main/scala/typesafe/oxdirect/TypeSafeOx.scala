package typesafe.oxdirect

import ox.{Ox, useInScope}
import typesafe.{ClientConfig, TypeSafeClient}

/** The [[typesafe.TypeSafeClient]] in an Ox program.
  *
  * There is no wrapper type here, and that is the point: on virtual threads a blocking call is
  * cheap, so the core client's own blocking API *is* the Ox API. `client.systemOne(...)` inside a
  * `fork` parks a virtual thread and nothing else, and the core already honours interruption —
  * it cancels the HTTP exchange and re-arms the interrupt flag — which is exactly the contract a
  * supervised scope relies on when it winds a fork down.
  *
  * What this module adds is the rest: a lifetime tied to a scope, the SDK's sealed failures as
  * `Either`, and [[ox.flow.Flow]] operators for running a batch through System One.
  *
  * {{{
  * import ox.*
  * import typesafe.*
  * import typesafe.oxdirect.*
  *
  * val urgent = Noul("The message conveys urgency").named("is_urgent")
  *
  * supervised {
  *   val client = TypeSafeOx.inScope()
  *   val a = fork { client.systemOne(ticketA, Questions.of(urgent)) }
  *   val b = fork { client.systemOne(ticketB, Questions.of(urgent)) }
  *   (a.join()(urgent).noul, b.join()(urgent).noul)
  * }
  * }}}
  *
  * Requires a Java 21 runtime, like Ox itself.
  */
object TypeSafeOx:

  /** A client whose HTTP resources are released when the enclosing scope ends, however it ends. */
  def inScope(config: ClientConfig = ClientConfig())(using Ox): TypeSafeClient =
    useInScope(TypeSafeClient(config))(_.close())

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey(apiKey: String)(using Ox): TypeSafeClient =
    inScope(ClientConfig(apiKey = Some(apiKey)))
