package typesafe

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.VectorMap
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.control.NonFatal

/** Client settings. Explicit values win over environment variables; blank env values are ignored.
  *
  * `record` (else `TYPESAFE_RECORD`) keeps each successful System One response under a directory,
  * and `replay` (else `TYPESAFE_REPLAY`) answers from one without the network or an API key; see
  * [[Cassette]].
  */
final case class ClientConfig(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    model: Option[String] = None,
    timeout: FiniteDuration = Constants.DefaultTimeout,
    retry: RetryPolicy = RetryPolicy(),
    headers: Map[String, String] = Map.empty,
    httpClient: Option[HttpClient] = None,
    env: String => Option[String] = sys.env.get,
    record: Option[Path] = None,
    replay: Option[Path] = None
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

/** One call, fully described and not yet sent: what the retry loops of the core and of the effect
  * bindings all work from. Internal; its shape follows what those loops need.
  */
private[typesafe] final case class PreparedCall(
    method: String,
    uri: URI,
    endpoint: String,
    headers: Map[String, String],
    body: Option[String],
    policy: RetryPolicy,
    attemptTimeout: FiniteDuration,
    cassetteKey: Option[String] = None
):
  /** Headers for attempt `attempt` (1-based); a retry announces which one it is. */
  def headersFor(attempt: Int): Map[String, String] =
    if attempt <= 1 then headers else headers + (Constants.RetryCountHeader -> (attempt - 1).toString)

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
    apiKey: Option[String],
    http: HttpClient,
    cassette: Option[TypeSafeClient.CassetteMode]
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
    try
      mapCancelable(execute(systemOneCall(state, questions, options))) { (raw, meta, endpoint) =>
        decodeSystemOne(raw, meta, endpoint)
      }
    catch case NonFatal(e) => CompletableFuture.failedFuture(e)

  /** Scala `Future` variant; fails with the typed [[TypeSafeException]], never a Java wrapper. */
  def systemOneFuture[S: ToJson](state: S, questions: Questions, options: CallOptions = CallOptions.default): Future[SystemOneResponse] =
    toScala(systemOneAsync(state, questions, options))

  // ---- Rubrics ------------------------------------------------------------------------------

  /** Ask the questions of a [[Rubric]] about `state` and decode the answers into it:
    * `client.ask[Triage]("The payout failed again.")`. Throws [[TypeSafeException]].
    */
  def ask[R](using rubric: Rubric[R]): Asking[R] = Asking(rubric)

  /** [[ask]], returning a Java `CompletableFuture`. */
  def askAsync[R](using rubric: Rubric[R]): AskingAsync[R] = AskingAsync(rubric)

  /** [[ask]], returning a Scala `Future` that fails with the typed [[TypeSafeException]]. */
  def askFuture[R](using rubric: Rubric[R]): AskingFuture[R] = AskingFuture(rubric)

  final class Asking[R] private[TypeSafeClient] (rubric: Rubric[R]):
    def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): R =
      rubric.fromResponse(systemOne(state, rubric.questions, options))

  final class AskingAsync[R] private[TypeSafeClient] (rubric: Rubric[R]):
    def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): CompletableFuture[R] =
      try mapCancelable(systemOneAsync(state, rubric.questions, options))(rubric.fromResponse)
      catch case NonFatal(e) => CompletableFuture.failedFuture(e)

  final class AskingFuture[R] private[TypeSafeClient] (rubric: Rubric[R]):
    def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): Future[R] =
      toScala(AskingAsync(rubric)(state, options))

  // ---- Models ------------------------------------------------------------------------------

  object models:
    def list(options: CallOptions = CallOptions.default): ListModelsResponse = await(listAsync(options))

    def listAsync(options: CallOptions = CallOptions.default): CompletableFuture[ListModelsResponse] =
      try mapCancelable(execute(modelsCall(options))) { (raw, meta, endpoint) => decodeModels(raw, meta, endpoint) }
      catch case NonFatal(e) => CompletableFuture.failedFuture(e)

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

  // ---- the pieces an effect binding drives itself ---------------------------------------------
  //
  // A binding wants its own retry loop: its own clock, its own scheduler, its own cancellation. What
  // it should not re-derive is what to send, what an answer means and when to retry, so the call
  // description, the decoders and the retry decisions all live here and are shared with the
  // `CompletableFuture` loop below. Behaviour can then only drift in one place.

  /** Everything one System One call needs, with no request sent yet. Throws on invalid input. */
  private[typesafe] def systemOneCall[S: ToJson](state: S, questions: Questions, options: CallOptions): PreparedCall =
    prepareSystemOne(state, questions, options) match
      case Left(e) => throw e
      case Right(body) =>
        val call = prepareCall("POST", Constants.SystemOnePath, Some(body), options)
        if cassette.isEmpty then call else call.copy(cassetteKey = Some(Cassette.keyOf(body)))

  /** Everything one list-models call needs, with no request sent yet. */
  private[typesafe] def modelsCall(options: CallOptions): PreparedCall =
    cassette match
      case Some(CassetteMode.Replay(dir)) =>
        throw ConfigException(s"Listing models is not recorded, so a client replaying from $dir cannot answer it.")
      case _ => prepareCall("GET", Constants.ModelsPath, None, options)

  private[typesafe] def decodeSystemOne(raw: Json, meta: ResponseMeta, endpoint: String): SystemOneResponse =
    val (model, usage, answers) =
      decodeOrFail(raw, meta, endpoint)(Decode.systemOne(_, (name, tpe) => log.log(
        System.Logger.Level.WARNING, s"Ignoring answer '$name' with unrecognized type '$tpe'")))
    SystemOneResponse(model, usage, answers, raw, meta)

  private[typesafe] def decodeModels(raw: Json, meta: ResponseMeta, endpoint: String): ListModelsResponse =
    ListModelsResponse(decodeOrFail(raw, meta, endpoint)(Decode.models), meta)

  private def prepareCall(method: String, path: String, body: Option[String], options: CallOptions): PreparedCall =
    val policy = options.retry.getOrElse(retry)
    policy.validate()
    val attemptTimeout = options.timeout.getOrElse(timeout)
    checkTimeout(attemptTimeout)
    val uri = URI.create(baseUrl + path)
    val headers: Map[String, String] =
      val merged = (defaultHeaders ++ options.headers).filterNot((k, _) =>
        Constants.Protected(k.toLowerCase) || k.equalsIgnoreCase(Constants.RetryCountHeader))
      merged ++ protectedHeaders ++ body.map(_ => "Content-Type" -> "application/json")
    PreparedCall(method, uri, s"$method ${redact(uri)}", headers, body, policy, attemptTimeout)

  /** One attempt, no retries; cancelling the returned future aborts the exchange.
    *
    * `boundAttempt` cancels the exchange at the attempt deadline, which the loop below relies on. A
    * binding that bounds the attempt with its own `timeout` passes `false` and keeps that job.
    */
  private[typesafe] def sendOnce(
      call: PreparedCall,
      attempt: Int,
      boundAttempt: Boolean = true
  ): CompletableFuture[(Json, ResponseMeta, String)] =
    (cassette, call.cassetteKey) match
      case (Some(CassetteMode.Replay(dir)), Some(key)) =>
        try CompletableFuture.completedFuture(replay(dir, key))
        catch case NonFatal(e) => CompletableFuture.failedFuture(e)
      case (Some(CassetteMode.Record(dir)), Some(key)) =>
        val sent = send(call, attempt, boundAttempt)
        val out = sent.thenApply { ok =>
          record(dir, key, ok._1)
          ok
        }
        // Cancelling `out` must reach the exchange, as `send` does for its own future.
        out.whenComplete { (_, err) =>
          if err.isInstanceOf[CancellationException] then sent.cancel(true)
        }
        out
      case _ => send(call, attempt, boundAttempt)

  private def send(
      call: PreparedCall,
      attempt: Int,
      boundAttempt: Boolean
  ): CompletableFuture[(Json, ResponseMeta, String)] =
    if attempt > 1 then log.log(System.Logger.Level.INFO, s"${call.endpoint} retry ${attempt - 1}")
    val headers = call.headersFor(attempt)
    val publisher = call.body.fold(HttpRequest.BodyPublishers.noBody())(b => HttpRequest.BodyPublishers.ofString(b, UTF_8))
    val builder = HttpRequest
      .newBuilder(call.uri)
      .method(call.method, publisher)
      .timeout(java.time.Duration.ofNanos(call.attemptTimeout.toNanos))
    headers.foreach((k, v) => builder.header(k, v))
    val request = builder.build()
    if log.isLoggable(System.Logger.Level.DEBUG) then
      log.log(System.Logger.Level.DEBUG, s"${call.endpoint} -> headers=${redacted(headers)} body=${call.body.getOrElse("")}")
    val t0 = System.nanoTime()
    val sent = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
    // `HttpRequest.timeout` only bounds the wait for response headers; a body that stalls afterwards
    // would hang. Cancelling the exchange at the deadline covers the whole attempt.
    if boundAttempt then
      CompletableFuture.delayedExecutor(call.attemptTimeout.toNanos, TimeUnit.NANOSECONDS).execute(() => sent.cancel(true))
    val out = CompletableFuture[(Json, ResponseMeta, String)]()
    sent.whenComplete { (resp, err) =>
      try out.complete(interpret(call, attempt, t0, resp, err))
      catch case NonFatal(t) => out.completeExceptionally(t)
    }
    out.whenComplete { (_, err) =>
      if err.isInstanceOf[CancellationException] then sent.cancel(true)
    }
    out

  /** Turns one finished exchange into an answer or the typed failure it deserves. */
  private def interpret(
      call: PreparedCall,
      attempt: Int,
      startedAt: Long,
      resp: HttpResponse[Array[Byte]],
      err: Throwable
  ): (Json, ResponseMeta, String) =
    if err != null then
      val cause = unwrap(err)
      val mapped = cause match
        case t: HttpTimeoutException  => TimeoutException(call.attemptTimeout, t)
        case t: CancellationException => TimeoutException(call.attemptTimeout, t)
        case e: TypeSafeException     => e
        case other                    => ConnectionException(other)
      log.log(System.Logger.Level.INFO, s"${call.endpoint} <- ${mapped.getClass.getSimpleName}")
      throw mapped
    val status = resp.statusCode()
    val respHeaders = resp.headers().map().asScala.view.mapValues(_.asScala.toList).toMap
    val bytes = resp.body()
    val text = new String(bytes, UTF_8)
    log.log(
      System.Logger.Level.INFO,
      f"${call.endpoint} <- $status in ${(System.nanoTime() - startedAt) / 1e6}%.0fms (request ${headerLookup(respHeaders, Constants.RequestIdHeader).getOrElse("-")})"
    )
    if log.isLoggable(System.Logger.Level.DEBUG) then
      log.log(System.Logger.Level.DEBUG, s"${call.endpoint} <- headers=${redacted(respHeaders.view.mapValues(_.mkString(",")).toMap)} body=$text")
    val lenient: Option[Json] =
      if bytes.isEmpty then None else Some(Json.parse(text).getOrElse(Json.Str(text)))
    if status < 200 || status > 299 then throw ApiException(status, lenient, respHeaders, Some(call.endpoint))
    val meta = ResponseMeta(status, respHeaders, attempt)
    Json.parse(text) match
      case Right(json) => (json, meta, call.endpoint)
      case Left(detail) =>
        throw ResponseValidationException(status, "", detail, lenient, respHeaders, Some(call.endpoint))

  /** The recorded response for `key`, in the shape a live call returns: no headers, no attempts. */
  private def replay(dir: Path, key: String): (Json, ResponseMeta, String) =
    val path = Cassette.path(dir, key)
    val text =
      try Files.readString(path, UTF_8)
      catch case NonFatal(_) => throw ReplayMissException(key, path)
    log.log(System.Logger.Level.DEBUG, s"<- replayed $path")
    val endpoint = s"replay $path"
    Json.parse(text) match
      case Right(json) => (json, ResponseMeta(200, Map.empty, 0), endpoint)
      case Left(detail) =>
        throw ResponseValidationException(200, "", detail, Some(Json.Str(text)), Map.empty, Some(endpoint))

  /** Keep a response that decodes; one that does not is left for the caller to fail on. */
  private def record(dir: Path, key: String, body: Json): Unit =
    val decodes =
      try
        Decode.systemOne(body, (_, _) => ())
        true
      catch case NonFatal(_) => false
    if decodes then
      try Cassette.write(dir, key, body)
      catch
        case NonFatal(e) =>
          log.log(System.Logger.Level.WARNING, s"Could not record the response for $key in $dir: ${e.getMessage}")

  // ---- the CompletableFuture retry loop -------------------------------------------------------

  private def execute(call: PreparedCall): CompletableFuture[(Json, ResponseMeta, String)] =
    val started = System.nanoTime()
    // The returned future owns the whole call: cancelling it aborts the exchange in flight and
    // stops the retry loop, instead of merely detaching from work that keeps running.
    val result = CompletableFuture[(Json, ResponseMeta, String)]()
    val inFlight = AtomicReference[CompletableFuture[?]](null)
    result.whenComplete { (_, err) =>
      val current = inFlight.getAndSet(null)
      if current != null && err.isInstanceOf[CancellationException] then current.cancel(true)
    }
    attemptLoop(result, inFlight, 1, call, started)
    result

  /** Runs one attempt and completes `result`, retrying in place until the policy gives up. */
  private def attemptLoop(
      result: CompletableFuture[(Json, ResponseMeta, String)],
      inFlight: AtomicReference[CompletableFuture[?]],
      attempt: Int,
      call: PreparedCall,
      started: Long
  ): Unit =
    if result.isDone then () // cancelled, or already answered
    else
      val current = sendOnce(call, attempt)
      inFlight.set(current)
      if result.isCancelled then current.cancel(true) // cancelled while this attempt was starting
      current.whenComplete { (ok, err) =>
        try step(result, inFlight, attempt, call, started, ok, err)
        catch case NonFatal(t) => result.completeExceptionally(t) // never leave `result` pending
      }
      ()

  private def step(
      result: CompletableFuture[(Json, ResponseMeta, String)],
      inFlight: AtomicReference[CompletableFuture[?]],
      attempt: Int,
      call: PreparedCall,
      started: Long,
      ok: (Json, ResponseMeta, String),
      err: Throwable
  ): Unit =
    if result.isDone then ()
    else if err == null then result.complete(ok)
    else
      val e = unwrap(err)
      if !call.policy.isRetryable(e) then result.completeExceptionally(e)
      else
        val delay = call.policy.delay(attempt, e, scala.util.Random.nextDouble())
        val elapsed = (System.nanoTime() - started).nanos
        if call.policy.shouldStop(attempt, elapsed, delay) then result.completeExceptionally(e)
        else
          val exec = CompletableFuture.delayedExecutor(delay.toNanos, TimeUnit.NANOSECONDS)
          exec.execute { () =>
            try attemptLoop(result, inFlight, attempt + 1, call, started)
            catch case NonFatal(t) => result.completeExceptionally(t)
          }

  private val protectedHeaders: Map[String, String] = apiKey.map(k => "Authorization" -> s"Bearer $k").toMap ++ Map(
    "Accept" -> "application/json",
    "User-Agent" -> s"${Constants.SdkName}/${Constants.Version}",
    Constants.SdkHeader -> s"${Constants.SdkName}/${Constants.Version}",
    Constants.RuntimeHeader ->
      s"scala/${scala.util.Properties.versionNumberString} jvm/${sys.props.getOrElse("java.version", "?")} (${sys.props.getOrElse("os.name", "?")}; ${sys.props.getOrElse("os.arch", "?")})"
  )

object TypeSafeClient:
  private val log = System.getLogger("typesafe")

  /** Where System One responses are recorded to or replayed from. */
  private[typesafe] enum CassetteMode:
    case Record(dir: Path)
    case Replay(dir: Path)

  /** A client configured from `config` (defaults: everything from the environment). */
  def apply(config: ClientConfig = ClientConfig()): TypeSafeClient =
    def resolve(explicit: Option[String], env: String): Option[String] =
      explicit.orElse(config.env(env).map(_.trim).filter(_.nonEmpty))
    def resolvePath(explicit: Option[Path], env: String): Option[Path] =
      explicit.orElse(resolve(None, env).map(Path.of(_)))
    val cassette = (resolvePath(config.record, Constants.RecordEnv), resolvePath(config.replay, Constants.ReplayEnv)) match
      case (Some(_), Some(_)) =>
        throw ConfigException(
          s"Both record and replay are set; a client does one or the other. Unset ${Constants.RecordEnv} or " +
            s"${Constants.ReplayEnv}, or drop one of the settings."
        )
      case (Some(dir), None) =>
        try Files.createDirectories(dir)
        catch case NonFatal(e) => throw ConfigException(s"Cannot record into $dir: ${e.getMessage}.")
        Some(CassetteMode.Record(dir))
      case (None, Some(dir)) => Some(CassetteMode.Replay(dir))
      case (None, None)      => None
    // A replaying client never sends anything, so it has no use for a key.
    val key = resolve(config.apiKey, Constants.ApiKeyEnv).map(_.trim).filter(_.nonEmpty)
    if key.isEmpty && !cassette.exists(_.isInstanceOf[CassetteMode.Replay]) then
      throw ConfigException(s"No API key was provided. Pass apiKey or set the ${Constants.ApiKeyEnv} environment variable.")
    if key.exists(_.exists(c => c < '!' || c > '~')) then
      throw ConfigException("API key must contain only printable ASCII characters without whitespace.")
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
      cassette = cassette,
      http = config.httpClient.getOrElse(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofNanos(config.timeout.toNanos)).build())
    )

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey(apiKey: String): TypeSafeClient = apply(ClientConfig(apiKey = Some(apiKey)))

  private def checkTimeout(t: FiniteDuration): Unit =
    if t <= Duration.Zero then throw ConfigException("timeout must be a positive duration.")

  /** `thenApply`, but cancelling the mapped future cancels `src` too, which `thenApply` does not. */
  private def mapCancelable[A, B](src: CompletableFuture[A])(f: A => B): CompletableFuture[B] =
    val out = CompletableFuture[B]()
    src.whenComplete { (a, err) =>
      if err != null then out.completeExceptionally(unwrap(err))
      else
        try out.complete(f(a))
        catch case NonFatal(t) => out.completeExceptionally(t)
    }
    out.whenComplete { (_, err) =>
      if err.isInstanceOf[CancellationException] then src.cancel(true)
    }
    out

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
    headers.map((k, v) => k -> (if Constants.isSecret(k) then "[REDACTED]" else v))
