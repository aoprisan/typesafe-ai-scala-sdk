package typesafe.catseffect

import cats.effect.kernel.Async
import typesafe.TypeSafeClient

/** `client.effect[IO]` on a client built the plain way. The lifetime stays the caller's. */
extension (client: TypeSafeClient) def effect[F[_]: Async]: TypeSafeClientF[F] = TypeSafeClientF.fromClient[F](client)

/** A client fixed to `cats.effect.IO`, for signatures that would rather not be polymorphic. */
type TypeSafeClientIO = TypeSafeClientF[cats.effect.IO]
