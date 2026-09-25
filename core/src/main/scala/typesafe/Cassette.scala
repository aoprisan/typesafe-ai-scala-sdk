package typesafe

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.collection.immutable.VectorMap

/** Record and replay: System One responses kept on disk, one file per request.
  *
  * A client configured with `record` (or `TYPESAFE_RECORD=<dir>`) writes each successful response
  * body to `<dir>/<key>.json`; one configured with `replay` (or `TYPESAFE_REPLAY=<dir>`) answers from
  * those files and never touches the network, so it needs no API key. A request with no file is a
  * [[ReplayMissException]], not a call.
  *
  * The key is the SHA-256, in lowercase hex, of the exact JSON body the request would POST —
  * `{"state":…,"model":…,"questions":…}` without whitespace, questions in the order they were
  * added, plus any `extraBody` fields. The file is that response body as compact JSON with its keys
  * in the order the server sent them, and a newline. That is also what the Rust SDK and
  * `jev eval --cache` keep, so an eval cache directory is a cassette directory and the other way
  * round.
  *
  * {{{
  * val key = Cassette.key("The payout failed again.", "jev-latest",
  *   Questions("is_urgent" -> Noul("The message conveys urgency")))
  * // 4bb6a561cd7ce28500dc6aa8fc821771e45e4f811f1195c2441263651a7dca55
  * }}}
  */
object Cassette:

  /** The key of a request with no `extraBody`: the hash of `{"state", "model", "questions"}` as
    * compact JSON.
    */
  def key[S: ToJson](state: S, model: String, questions: Questions): String =
    keyOf(Json.Obj(VectorMap("state" -> ToJson[S](state), "model" -> Json.Str(model), "questions" -> questions.toJson)).render)

  /** The key of a request body that has already been encoded. */
  def keyOf(body: String): String =
    MessageDigest.getInstance("SHA-256").digest(body.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** Where the response for `key` is kept under `dir`. */
  def path(dir: Path, key: String): Path = dir.resolve(s"$key.json")

  /** Keep a response. The file is written whole, then renamed into place, so concurrent readers
    * never see half of one.
    */
  private[typesafe] def write(dir: Path, key: String, body: Json): Unit =
    val partial = dir.resolve(s".$key.${java.util.UUID.randomUUID()}.partial")
    Files.writeString(partial, render(body) + "\n", UTF_8)
    try Files.move(partial, path(dir, key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(partial, path(dir, key), StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(partial)
    ()

  /** Compact JSON, numbers spelled the way serde_json spells them, so a recording has the same
    * bytes whichever SDK made it: an integer as written, anything else as the shortest double.
    */
  private[typesafe] def render(json: Json): String =
    val sb = new java.lang.StringBuilder
    def go(j: Json): Unit = j match
      case Json.Num(n) => sb.append(number(n))
      case Json.Arr(xs) =>
        sb.append('[')
        xs.iterator.zipWithIndex.foreach { (x, i) =>
          if i > 0 then sb.append(',')
          go(x)
        }
        sb.append(']')
      case Json.Obj(fs) =>
        sb.append('{')
        fs.iterator.zipWithIndex.foreach { case ((k, v), i) =>
          if i > 0 then sb.append(',')
          sb.append(Json.Str(k).render).append(':')
          go(v)
        }
        sb.append('}')
      case other => sb.append(other.render)
    go(json)
    sb.toString

  private val U64Max = BigInt("18446744073709551615")
  private val I64Min = BigInt(Long.MinValue)

  private[typesafe] def number(n: BigDecimal): String =
    if n.scale == 0 && n.toBigInt >= I64Min && n.toBigInt <= U64Max then n.toBigInt.toString
    else shortest(n.toDouble)

  // ryu's `format_finite`, which serde_json prints doubles with: plain notation from 1e-5 up to 1e16,
  // `1e-7` / `1.5e20` outside it, and a whole number keeps its `.0`.
  private def shortest(d: Double): String =
    if d.isNaN || d.isInfinite then "null"
    else if d == 0 then (if 1 / d < 0 then "-0.0" else "0.0")
    else
      val exact = java.math.BigDecimal(java.lang.Double.toString(math.abs(d))).stripTrailingZeros
      val digits = exact.unscaledValue.toString
      val len = digits.length
      val k = -exact.scale // value = digits × 10^k
      val kk = len + k // position of the decimal point
      val body =
        if k >= 0 && kk <= 16 then digits + "0" * k + ".0"
        else if kk > 0 && kk <= 16 then digits.take(kk) + "." + digits.drop(kk)
        else if kk > -5 && kk <= 0 then "0." + "0" * -kk + digits
        else if len == 1 then s"${digits}e${kk - 1}"
        else s"${digits.head}.${digits.tail}e${kk - 1}"
      if d < 0 then "-" + body else body
