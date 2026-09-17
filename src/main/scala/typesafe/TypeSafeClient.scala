package typesafe

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ExecutionException, TimeUnit}
import scala.collection.immutable.VectorMap
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.control.NonFatal

/** Client settings. Explicit values win over environment variables; blank env values are ignored. */
final case class ClientConfig(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    model: Option[String] = None,
    timeout: FiniteDuration = Constants.DefaultTimeout,
    retry: RetryPolicy = RetryPolicy(),
    headers: Map[String, String] = Map.empty,
    httpClient: Option[HttpClient] = None,
    env: String => Option[String] = sys.env.get
)

/** Per-call overrides. `extraBody` fields are shallow-merged last over `state`/`model`/`questions`. */
final case class CallOptions(
    model: Option[String] = None,
    retry: Option[RetryPolicy] = None,
    timeout: Option[FiniteDuration] = None,
    headers: Map[String, String] = Map.empty,
    extraBody: VectorMap[String, Json] = VectorMap.empty
)

object CallOptions:
  val default: CallOptions = CallOptions()

/** TypeSafe System One client. Thread-safe; share one instance.
  *
  * {{{
  * val client  = TypeSafeClient()
  * val urgent  = Noul("Does this convey urgency?").named("is_urgent")
  * val team    = Choice("Which team?", "billing" -> "Payments", "technical" -> "Bugs").named("team")
  * val res     = client.systemOne("My payouts have failed for 3 days!", Questions.of(urgent, team))
  * res(urgent).noul   // Double
  * res(team).choice   // String
  * }}}
  */
final class TypeSafeClient private (
    val baseUrl: String,
    val defaultModel: String,
    timeout: FiniteDuration,
    retry: RetryPolicy,
    defaultHeaders: Map[String, String],
    apiKey: String,
    http: HttpClient
):
  import TypeSafeClient.*

  override def toString: String = s"TypeSafeClient($baseUrl, model=$defaultModel)"

  // ---- System One --------------------------------------------------------------------------

  /** Ask typed questions about `state` and wait for the answers. Throws [[TypeSafeException]]. */
  def systemOne[S: ToJson](state: S, questions: Questions, options: CallOptions = CallOptions.default): SystemOneResponse =
    await(systemOneAsync(state, questions, options))

  /** Non-blocking variant returning a Java `CompletableFuture` (failures arrive wrapped in
    * `CompletionException`/`ExecutionException`, as usual for that API).
    */
  def systemOneAsync[S: ToJson](
      state: S,
      questions: Questions,
      options: CallOptions = CallOptions.default
  ): CompletableFuture[SystemOneResponse] =
    prepareSystemOne(state, questions, options) match
      case Left(e) => CompletableFuture.failedFuture(e)
      case Right(body) =>
        execute("POST", Constants.SystemOnePath, Some(body), options).thenApply { (raw, meta, endpoint) =>
          val (model, usage, answers) =
            decodeOrFail(raw, meta, endpoint)(Decode.systemOne(_, (name, tpe) => log.log(
              System.Logger.Level.WARNING, s"Ignoring answer '$name' with unrecognized type '$tpe'")))
          SystemOneResponse(model, usage, answers, raw, meta)
        }

  /** Scala `Future` variant; fails with the typed [[TypeSafeException]], never a Java wrapper. */
  def systemOneFuture[S: ToJson](state: S, questions: Questions, options: CallOptions = CallOptions.default): Future[SystemOneResponse] =
    toScala(systemOneAsync(state, questions, options))

  // ---- Models ------------------------------------------------------------------------------

  object models:
    def list(options: CallOptions = CallOptions.default): ListModelsResponse = await(listAsync(options))

    def listAsync(options: CallOptions = CallOptions.default): CompletableFuture[ListModelsResponse] =
      execute("GET", Constants.ModelsPath, None, options).thenApply { (raw, meta, endpoint) =>
        ListModelsResponse(decodeOrFail(raw, meta, endpoint)(Decode.models), meta)
      }

    def listFuture(options: CallOptions = CallOptions.default): Future[ListModelsResponse] = toScala(listAsync(options))

  /** Release the underlying HTTP client (JDK 21+; no-op on older JDKs). */
  def close(): Unit = (http: Any) match
    case c: AutoCloseable => c.close()
    case _                => ()

  // ---- internals ---------------------------------------------------------------------------

  private def prepareSystemOne[S: ToJson](state: S, questions: Questions, options: CallOptions): Either[Throwable, String] =
    try
      val stateJson =
        try ToJson[S](state)
        catch case NonFatal(e) => throw InvalidRequestException(s"The state could not be encoded as JSON: ${e.getMessage}", e)
      questions.validate()
      val body = VectorMap(
        "state" -> stateJson,
        "model" -> Json.Str(options.model.getOrElse(defaultModel)),
        "questions" -> questions.toJson
      ) ++ options.extraBody
      Right(Json.Obj(body).render)
    catch case NonFatal(e) => Left(e)

  private def decodeOrFail[T](raw: Json, meta: ResponseMeta, endpoint: String)(f: Json => T): T =
    try f(raw)
    catch
      case Decode.Failure(path, detail) =>
        throw ResponseValidationException(meta.status, path, detail, Some(raw), meta.headers, Some(endpoint))

  private def execute(
      method: String,
      path: String,
      body: Option[String],
      options: CallOptions
  ): CompletableFuture[(Json, ResponseMeta, String)] =
    try
      val policy = options.retry.getOrElse(retry)
      policy.validate()
      val attemptTimeout = options.timeout.getOrElse(timeout)
      checkTimeout(attemptTimeout)
      val uri = URI.create(baseUrl + path)
      val endpoint = s"$method ${redact(uri)}"
      val headers: Map[String, String] =
        val merged = (defaultHeaders ++ options.headers).filterNot((k, _) =>
          Constants.Protected(k.toLowerCase) || k.equalsIgnoreCase(Constants.RetryCountHeader))
        merged ++ protectedHeaders ++ body.map(_ => "Content-Type" -> "application/json")
      val started = System.nanoTime()
      attemptLoop(1, policy, started, method, uri, endpoint, headers, body, attemptTimeout)
    catch case NonFatal(e) => CompletableFuture.failedFuture(e)

  private def attemptLoop(
      attempt: Int,
      policy: RetryPolicy,
      started: Long,
      method: String,
      uri: URI,
      endpoint: String,
      headers: Map[String, String],
      body: Option[String],
      attemptTimeout: FiniteDuration
  ): CompletableFuture[(Json, ResponseMeta, String)] =
    val retries = attempt - 1
    val h = if retries > 0 then headers + (Constants.RetryCountHeader -> retries.toString) else headers
    if retries > 0 then log.log(System.Logger.Level.INFO, s"$endpoint retry $retries")
    once(method, uri, endpoint, h, body, attemptTimeout, attempt)
      .handle[CompletableFuture[(Json, ResponseMeta, String)]] { (ok, err) =>
        if err == null then CompletableFuture.completedFuture(ok)
        else
          val e = unwrap(err)
          if !policy.isRetryable(e) then CompletableFuture.failedFuture(e)
          else
            val delay = policy.delay(attempt, e, scala.util.Random.nextDouble())
            val elapsed = (System.nanoTime() - started).nanos
            if policy.shouldStop(attempt, elapsed, delay) then CompletableFuture.failedFuture(e)
            else
              val exec = CompletableFuture.delayedExecutor(delay.toNanos, TimeUnit.NANOSECONDS)
              CompletableFuture
                .supplyAsync(() => (), exec)
                .thenCompose(_ => attemptLoop(attempt + 1, policy, started, method, uri, endpoint, headers, body, attemptTimeout))
      }
      .thenCompose(identity)

  private def once(
      method: String,
      uri: URI,
      endpoint: String,
      headers: Map[String, String],
      body: Option[String],
      attemptTimeout: FiniteDuration,
      attempt: Int
  ): CompletableFuture[(Json, ResponseMeta, String)] =
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody())(b => HttpRequest.BodyPublishers.ofString(b, UTF_8))
    val builder = HttpRequest
      .newBuilder(uri)
      .method(method, publisher)
      .timeout(java.time.Duration.ofNanos(attemptTimeout.toNanos))
    headers.foreach((k, v) => builder.header(k, v))
    val request = builder.build()
    if log.isLoggable(System.Logger.Level.DEBUG) then
      log.log(System.Logger.Level.DEBUG, s"$endpoint -> headers=${redacted(headers)} body=${body.getOrElse("")}")
    val t0 = System.nanoTime()
    val sent = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
    // `HttpRequest.timeout` only bounds the wait for response headers; a body that stalls afterwards
    // would hang. Cancelling the exchange at the deadline covers the whole attempt.
    CompletableFuture.delayedExecutor(attemptTimeout.toNanos, TimeUnit.NANOSECONDS).execute(() => sent.cancel(true))
    sent
      .handle[(Json, ResponseMeta, String)] { (resp, err) =>
        if err != null then
          val cause = unwrap(err)
          val mapped = cause match
            case t: HttpTimeoutException  => TimeoutException(attemptTimeout, t)
            case t: CancellationException => TimeoutException(attemptTimeout, t)
            case e: TypeSafeException     => e
            case other                    => ConnectionException(other)
          log.log(System.Logger.Level.INFO, s"$endpoint <- ${mapped.getClass.getSimpleName}")
          throw mapped
        val status = resp.statusCode()
        val respHeaders = resp.headers().map().asScala.view.mapValues(_.asScala.toList).toMap
        val bytes = resp.body()
        val text = new String(bytes, UTF_8)
        log.log(
          System.Logger.Level.INFO,
          f"$endpoint <- $status in ${(System.nanoTime() - t0) / 1e6}%.0fms (request ${headerLookup(respHeaders, Constants.RequestIdHeader).getOrElse("-")})"
        )
        if log.isLoggable(System.Logger.Level.DEBUG) then
          log.log(System.Logger.Level.DEBUG, s"$endpoint <- headers=${redacted(respHeaders.view.mapValues(_.mkString(",")).toMap)} body=$text")
        val lenient: Option[Json] =
          if bytes.isEmpty then None else Some(Json.parse(text).getOrElse(Json.Str(text)))
        if status < 200 || status > 299 then throw ApiException(status, lenient, respHeaders, Some(endpoint))
        val meta = ResponseMeta(status, respHeaders, attempt)
        Json.parse(text) match
          case Right(json) => (json, meta, endpoint)
          case Left(detail) =>
            throw ResponseValidationException(status, "", detail, lenient, respHeaders, Some(endpoint))
      }

  private val protectedHeaders: Map[String, String] = Map(
    "Authorization" -> s"Bearer $apiKey",
    "Accept" -> "application/json",
    "User-Agent" -> s"${Constants.SdkName}/${Constants.Version}",
    Constants.SdkHeader -> s"${Constants.SdkName}/${Constants.Version}",
    Constants.RuntimeHeader ->
      s"scala/${scala.util.Properties.versionNumberString} jvm/${sys.props.getOrElse("java.version", "?")} (${sys.props.getOrElse("os.name", "?")}; ${sys.props.getOrElse("os.arch", "?")})"
  )

object TypeSafeClient:
  private val log = System.getLogger("typesafe")

  /** A client configured from `config` (defaults: everything from the environment). */
  def apply(config: ClientConfig = ClientConfig()): TypeSafeClient =
    def resolve(explicit: Option[String], env: String): Option[String] =
      explicit.orElse(config.env(env).map(_.trim).filter(_.nonEmpty))
    val key = resolve(config.apiKey, Constants.ApiKeyEnv).getOrElse(
      throw ConfigException(s"No API key was provided. Pass apiKey or set the ${Constants.ApiKeyEnv} environment variable.")
    )
    if key.exists(c => c == '\r' || c == '\n') then throw ConfigException("The API key contains invalid characters.")
    checkTimeout(config.timeout)
    config.retry.validate()
    val baseUrl = resolve(config.baseUrl, Constants.BaseUrlEnv).getOrElse(Constants.DefaultBaseUrl).reverse.dropWhile(_ == '/').reverse
    val parsed = scala.util.Try(new URI(baseUrl)).toOption
    if !parsed.exists(u => (u.getScheme == "http" || u.getScheme == "https") && u.getHost != null) then
      throw ConfigException(s"baseUrl must be an absolute http(s) URL, got '$baseUrl'.")
    new TypeSafeClient(
      baseUrl = baseUrl,
      defaultModel = resolve(config.model, Constants.DefaultModelEnv).getOrElse(Constants.DefaultModel),
      timeout = config.timeout,
      retry = config.retry,
      defaultHeaders = config.headers,
      apiKey = key,
      http = config.httpClient.getOrElse(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofNanos(config.timeout.toNanos)).build())
    )

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey(apiKey: String): TypeSafeClient = apply(ClientConfig(apiKey = Some(apiKey)))

  private def checkTimeout(t: FiniteDuration): Unit =
    if t <= Duration.Zero then throw ConfigException("timeout must be a positive duration.")

  private def toScala[T](f: CompletableFuture[T]): Future[T] =
    f.asScala.transform(identity, unwrap)(using scala.concurrent.ExecutionContext.parasitic)

  private def unwrap(t: Throwable): Throwable = t match
    case e: CompletionException if e.getCause != null => unwrap(e.getCause)
    case e: ExecutionException if e.getCause != null  => unwrap(e.getCause)
    case other                                        => other

  private def await[T](f: CompletableFuture[T]): T =
    try f.get()
    catch
      case e: ExecutionException => throw unwrap(e)
      case e: InterruptedException =>
        f.cancel(true)
        Thread.currentThread().interrupt()
        throw e

  private def redact(uri: URI): String =
    new URI(uri.getScheme, null, uri.getHost, uri.getPort, uri.getPath, null, null).toString

  private def redacted(headers: Map[String, String]): Map[String, String] =
    headers.map((k, v) => k -> (if Constants.Secret(k.toLowerCase) then "[REDACTED]" else v))
