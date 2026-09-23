package typesafe

import java.time.{Duration as JDuration, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

object Constants:
  val ApiKeyEnv = "TYPESAFE_API_KEY"
  val BaseUrlEnv = "TYPESAFE_BASE_URL"
  val DefaultModelEnv = "TYPESAFE_DEFAULT_MODEL"
  val RecordEnv = "TYPESAFE_RECORD"
  val ReplayEnv = "TYPESAFE_REPLAY"
  val DefaultBaseUrl = "https://api.typesafe.ai"
  val DefaultModel = "jev-latest"
  val DefaultTimeout: FiniteDuration = 10.seconds
  val Version = "0.3.0"
  val SdkName = "typesafe-sdk-scala"

  private[typesafe] val SystemOnePath = "/v1/systemone"
  private[typesafe] val ModelsPath = "/v1/models"
  private[typesafe] val MaxErrorBodyLength = 200
  private[typesafe] val SdkHeader = "X-TypeSafe-SDK"
  private[typesafe] val RuntimeHeader = "X-TypeSafe-Runtime"
  private[typesafe] val RetryCountHeader = "X-TypeSafe-Retry-Count"
  private[typesafe] val RequestIdHeader = "x-typesafe-request-id"
  private[typesafe] val RetryAfterHeader = "retry-after"
  private[typesafe] val RetryAfterMsHeader = "retry-after-ms"
  private[typesafe] val Protected = Set("authorization", "accept", "user-agent", "x-typesafe-sdk", "x-typesafe-runtime", "content-type")
  private[typesafe] val Secret = Set("authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie")

/** Root of every SDK failure. */
sealed abstract class TypeSafeException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

/** Missing API key, invalid timeout or retry policy, record and replay both set, or listing models
  * while replaying.
  */
final class ConfigException(message: String) extends TypeSafeException(message)

/** Rejected locally before sending (no questions, empty score criteria, malformed raw question, unencodable state). */
final class InvalidRequestException(message: String, cause: Throwable = null)
    extends TypeSafeException(message, cause)

enum ApiErrorKind:
  case BadRequest, Authentication, PermissionDenied, NotFound, UnprocessableEntity, RateLimit, InternalServer, Other

object ApiErrorKind:
  def fromStatus(status: Int): ApiErrorKind = status match
    case 400           => BadRequest
    case 401           => Authentication
    case 403           => PermissionDenied
    case 404           => NotFound
    case 422           => UnprocessableEntity
    case 429           => RateLimit
    case s if s >= 500 => InternalServer
    case _             => Other

private def headerLookup(headers: Map[String, List[String]], name: String): Option[String] =
  headers.collectFirst { case (k, v :: _) if k.equalsIgnoreCase(name) => v }

private def suffix(endpoint: Option[String], core: String, headers: Map[String, List[String]]): String =
  endpoint.fold("")(e => s"$e: ") + core +
    headerLookup(headers, Constants.RequestIdHeader).fold("")(id => s" (request_id=$id)")

/** An unsuccessful HTTP response. `body` is the JSON error body, the raw text as `Json.Str`, or `None`. */
final class ApiException(
    val status: Int,
    val body: Option[Json],
    val headers: Map[String, List[String]],
    val endpoint: Option[String]
) extends TypeSafeException(
      suffix(endpoint, s"$status ${ApiException.messageFor(body)}", headers)
    ):
  val kind: ApiErrorKind = ApiErrorKind.fromStatus(status)
  val detail: String = ApiException.messageFor(body)
  def requestId: Option[String] = headerLookup(headers, Constants.RequestIdHeader)
  def retryAfter: Option[FiniteDuration] = RetryAfter.parse(headers)

object ApiException:
  private[typesafe] def messageFor(body: Option[Json]): String =
    body.flatMap(extractMessage).getOrElse {
      body match
        case None                => "status code (no body)"
        case Some(Json.Str(raw)) => truncate(raw)
        case Some(other)         => truncate(other.render)
    }

  private def truncate(raw: String): String =
    if raw.codePointCount(0, raw.length) > Constants.MaxErrorBodyLength then
      raw.substring(0, raw.offsetByCodePoints(0, Constants.MaxErrorBodyLength)) + "…"
    else raw

  /** Pull a human-readable message out of the error shapes the API (FastAPI) may return. */
  private[typesafe] def extractMessage(body: Json): Option[String] = body match
    case Json.Str(s) => Option.when(s.nonEmpty)(s)
    case Json.Obj(o) =>
      def msgOf(j: Option[Json]) = j.flatMap(_.get("message")).flatMap(_.asString)
      (o.get("error"), o.get("message"), o.get("detail")) match
        case (Some(Json.Str(e)), _, _)                                  => Some(e)
        case (e @ Some(Json.Obj(_)), _, _) if msgOf(e).isDefined       => msgOf(e)
        case (_, Some(Json.Str(m)), _)                                  => Some(m)
        case (_, _, Some(Json.Str(d)))                                  => Some(d)
        case (_, _, d @ Some(Json.Obj(_)))                              => msgOf(d)
        case (_, _, Some(Json.Arr(entries))) =>
          val parts = entries.flatMap { entry =>
            entry.get("msg").flatMap(_.asString).map { msg =>
              val path = entry.get("loc").flatMap(_.asArray).fold("") { loc =>
                loc.filterNot(_ == Json.Str("body")).map {
                  case Json.Str(s) => s
                  case other       => other.render
                }.mkString(".")
              }
              if path.isEmpty then msg else s"$path: $msg"
            }
          }
          Option.when(parts.nonEmpty)(parts.mkString("; "))
        case _ => None
    case _ => None

/** The request never produced a response (DNS, connect, reset, read failure). */
final class ConnectionException(cause: Throwable)
    extends TypeSafeException(s"Connection error: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}", cause)

/** The request exceeded its per-attempt timeout. */
final class TimeoutException(val timeout: FiniteDuration, cause: Throwable = null)
    extends TypeSafeException(s"Request timed out (timeout=${timeout.toMillis / 1000.0}s).", cause)

/** A 2xx response whose body is missing or has structurally invalid required data. */
final class ResponseValidationException(
    val status: Int,
    val fieldPath: String,
    val detail: String,
    val body: Option[Json],
    val headers: Map[String, List[String]],
    val endpoint: Option[String]
) extends TypeSafeException(suffix(endpoint, s"$status Invalid response data at '$fieldPath': $detail", headers))

/** The client is replaying and this request was never recorded. Nothing was sent: a replaying
  * client does not fall back to the network. Record it first (`TYPESAFE_RECORD=<dir>`); see
  * [[Cassette]].
  */
final class ReplayMissException(val key: String, val path: java.nio.file.Path)
    extends TypeSafeException(s"No recording for this request: $path does not exist (replaying, so nothing was sent).")

private[typesafe] object RetryAfter:
  def parse(headers: Map[String, List[String]]): Option[FiniteDuration] =
    val ms = headerLookup(headers, Constants.RetryAfterMsHeader).flatMap { raw =>
      val t = raw.trim
      (if t.isEmpty then Some(0.0) else t.toDoubleOption)
        .filter(v => !v.isNaN && !v.isInfinite && v >= 0)
        .map(v => (v * 1000).round.micros)
    }
    ms.orElse {
      headerLookup(headers, Constants.RetryAfterHeader).flatMap { raw =>
        val t = raw.trim
        (if t.isEmpty then Some(0.0) else t.toDoubleOption) match
          case Some(v) if !v.isNaN && !v.isInfinite && v >= 0 => Some((v * 1000000).round.micros)
          case Some(_) => None
          case None =>
            scala.util
              .Try(ZonedDateTime.parse(raw.trim, DateTimeFormatter.RFC_1123_DATE_TIME))
              .toOption
              .map { at =>
                val d = JDuration.between(java.time.Instant.now(), at.toInstant)
                if d.isNegative then Duration.Zero else d.toMillis.millis
              }
      }
    }

/** Retry configuration; semantics match the official Python SDK (Tenacity-based). */
final case class RetryPolicy(
    maxRetries: Int = 2,
    backoffInitial: FiniteDuration = 500.millis,
    backoffMax: FiniteDuration = 5.seconds,
    backoffJitter: Double = 0.25,
    httpStatuses: Set[Int] = Set(408, 429) ++ (500 until 600),
    respectRetryAfter: Boolean = true,
    retryConnectionErrors: Boolean = true,
    retryTimeouts: Boolean = true,
    predicate: Option[Throwable => Boolean] = None,
    budget: Option[FiniteDuration] = Some(30.seconds)
):
  def retryIf(p: Throwable => Boolean): RetryPolicy = copy(predicate = Some(p))

  private[typesafe] def validate(): Unit =
    if maxRetries < 0 then throw ConfigException("maxRetries must be a non-negative integer.")
    if backoffInitial < Duration.Zero || backoffMax < Duration.Zero then
      throw ConfigException("backoff delays must be non-negative.")
    if !(backoffJitter >= 0 && backoffJitter <= 1) then
      throw ConfigException("backoffJitter must be between zero and one.")
    if budget.exists(_ <= Duration.Zero) then throw ConfigException("retry budget must be positive.")

  private[typesafe] def isRetryable(e: Throwable): Boolean =
    val builtin = e match
      case _: TimeoutException    => retryTimeouts
      case _: ConnectionException => retryConnectionErrors
      case a: ApiException        => httpStatuses.contains(a.status)
      case _                      => false
    builtin || predicate.exists(_(e))

  /** `attempt` is the 1-based number of the attempt that just failed. */
  private[typesafe] def delay(attempt: Int, e: Throwable, random: => Double): FiniteDuration =
    val fromServer = e match
      case a: ApiException if respectRetryAfter => a.retryAfter
      case _                                    => None
    fromServer.getOrElse(RetryPolicy.backoff(attempt, backoffInitial, backoffMax, backoffJitter, random))

  private[typesafe] def shouldStop(attempts: Int, elapsed: FiniteDuration, upcoming: FiniteDuration): Boolean =
    attempts > maxRetries || budget.exists(b => elapsed + upcoming >= b)

object RetryPolicy:
  val none: RetryPolicy = RetryPolicy(maxRetries = 0)

  private[typesafe] def backoff(
      attempt: Int,
      initial: FiniteDuration,
      max: FiniteDuration,
      jitter: Double,
      r: Double
  ): FiniteDuration =
    val (i, m) = (initial.toNanos / 1e9, max.toNanos / 1e9)
    if i == 0 || m == 0 then Duration.Zero
    else
      val exponent = (attempt - 1).max(0).toDouble
      val log2 = (x: Double) => math.log(x) / math.log(2)
      val exponential = if exponent >= log2(m) - log2(i) then m else i * math.pow(2, exponent)
      val delay = exponential * (1 - r * jitter)
      val rounded = math.round(delay * 1000) / 1000.0
      (math.min(exponential, rounded) * 1e9).round.nanos
