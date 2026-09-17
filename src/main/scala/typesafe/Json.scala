package typesafe

import scala.collection.immutable.VectorMap
import scala.compiletime.{constValueTuple, erasedValue}
import scala.deriving.Mirror

/** A small immutable JSON AST. The SDK keeps its own so it adds no runtime dependencies; convert from
  * circe/jsoniter/upickle values with [[Json.parse]] on their printed form, or build values with
  * [[Json.obj]] / [[Json.arr]].
  */
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(items: Vector[Json])
  case Obj(fields: VectorMap[String, Json])

  /** Compact JSON text. */
  def render: String =
    val sb = new java.lang.StringBuilder
    Json.write(this, sb)
    sb.toString

  override def toString: String = render

  /** Field lookup on objects. */
  def get(key: String): Option[Json] = this match
    case Obj(f) => f.get(key)
    case _      => None

  def asString: Option[String] = this match
    case Str(s) => Some(s)
    case _      => None

  def asDouble: Option[Double] = this match
    case Num(n) => Some(n.toDouble)
    case _      => None

  def asObject: Option[VectorMap[String, Json]] = this match
    case Obj(f) => Some(f)
    case _      => None

  def asArray: Option[Vector[Json]] = this match
    case Arr(a) => Some(a)
    case _      => None

object Json:
  /** Object from `key -> value` pairs; values may be anything with a [[ToJson]] instance. */
  def obj(fields: (String, JsonValue)*): Json = Obj(VectorMap.from(fields.map((k, v) => k -> v.json)))

  /** Array from values with a [[ToJson]] instance. */
  def arr(items: JsonValue*): Json = Arr(items.map(_.json).toVector)

  def str(s: String): Json = Str(s)

  /** Parse JSON text. */
  def parse(text: String): Either[String, Json] =
    try
      val p = Parser(text)
      val v = p.value()
      p.skipWs()
      if p.pos != text.length then Left(s"trailing characters at offset ${p.pos}") else Right(v)
    catch case e: ParseError => Left(e.getMessage)

  /** Parse, throwing `IllegalArgumentException` on malformed input. */
  def unsafeParse(text: String): Json = parse(text).fold(e => throw IllegalArgumentException(e), identity)

  private def write(j: Json, sb: java.lang.StringBuilder): Unit = j match
    case Null     => sb.append("null")
    case Bool(b)  => sb.append(b)
    case Num(n)   => sb.append(n.bigDecimal.toString)
    case Str(s)   => quote(s, sb)
    case Arr(xs) =>
      sb.append('[')
      var first = true
      xs.foreach { x =>
        if !first then sb.append(',')
        first = false
        write(x, sb)
      }
      sb.append(']')
    case Obj(fs) =>
      sb.append('{')
      var first = true
      fs.foreach { (k, v) =>
        if !first then sb.append(',')
        first = false
        quote(k, sb)
        sb.append(':')
        write(v, sb)
      }
      sb.append('}')

  private def quote(s: String, sb: java.lang.StringBuilder): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c    => sb.append(c)
      i += 1
    sb.append('"')

  private final class ParseError(msg: String) extends Exception(msg)

  private final class Parser(s: String):
    var pos = 0

    private def fail(what: String): Nothing = throw ParseError(s"$what at offset $pos")

    def skipWs(): Unit =
      while pos < s.length && " \t\r\n".indexOf(s.charAt(pos)) >= 0 do pos += 1

    private def expect(lit: String): Unit =
      if s.startsWith(lit, pos) then pos += lit.length else fail(s"expected '$lit'")

    def value(): Json =
      skipWs()
      if pos >= s.length then fail("unexpected end of input")
      s.charAt(pos) match
        case '{' => obj()
        case '[' => arr()
        case '"' => Str(string())
        case 't' => expect("true"); Bool(true)
        case 'f' => expect("false"); Bool(false)
        case 'n' => expect("null"); Null
        case c if c == '-' || c.isDigit => num()
        case c => fail(s"unexpected character '$c'")

    private def obj(): Json =
      pos += 1
      val b = VectorMap.newBuilder[String, Json]
      skipWs()
      if pos < s.length && s.charAt(pos) == '}' then
        pos += 1
        return Obj(VectorMap.empty)
      var more = true
      while more do
        skipWs()
        if pos >= s.length || s.charAt(pos) != '"' then fail("expected object key")
        val k = string()
        skipWs()
        expect(":")
        b += k -> value()
        skipWs()
        if pos < s.length && s.charAt(pos) == ',' then pos += 1
        else if pos < s.length && s.charAt(pos) == '}' then { pos += 1; more = false }
        else fail("expected ',' or '}'")
      Obj(b.result())

    private def arr(): Json =
      pos += 1
      val b = Vector.newBuilder[Json]
      skipWs()
      if pos < s.length && s.charAt(pos) == ']' then
        pos += 1
        return Arr(Vector.empty)
      var more = true
      while more do
        b += value()
        skipWs()
        if pos < s.length && s.charAt(pos) == ',' then pos += 1
        else if pos < s.length && s.charAt(pos) == ']' then { pos += 1; more = false }
        else fail("expected ',' or ']'")
      Arr(b.result())

    private def string(): String =
      pos += 1
      val sb = new java.lang.StringBuilder
      while
        if pos >= s.length then fail("unterminated string")
        val c = s.charAt(pos)
        pos += 1
        c match
          case '"' => false
          case '\\' =>
            if pos >= s.length then fail("unterminated escape")
            val e = s.charAt(pos)
            pos += 1
            e match
              case '"'  => sb.append('"')
              case '\\' => sb.append('\\')
              case '/'  => sb.append('/')
              case 'b'  => sb.append('\b')
              case 'f'  => sb.append('\f')
              case 'n'  => sb.append('\n')
              case 'r'  => sb.append('\r')
              case 't'  => sb.append('\t')
              case 'u' =>
                if pos + 4 > s.length then fail("bad unicode escape")
                val hex = s.substring(pos, pos + 4)
                if !hex.forall(ch => Character.digit(ch, 16) >= 0) then fail("bad unicode escape")
                sb.append(Integer.parseInt(hex, 16).toChar)
                pos += 4
              case other => fail(s"bad escape '\\$other'")
            true
          case c if c < 0x20 => fail("control character in string")
          case c =>
            sb.append(c)
            true
      do ()
      sb.toString

    private def num(): Json =
      val start = pos
      if s.charAt(pos) == '-' then pos += 1
      def digits(): Unit =
        val d0 = pos
        while pos < s.length && s.charAt(pos).isDigit do pos += 1
        if pos == d0 then fail("expected digit")
      digits()
      if pos < s.length && s.charAt(pos) == '.' then { pos += 1; digits() }
      if pos < s.length && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E') then
        pos += 1
        if pos < s.length && (s.charAt(pos) == '+' || s.charAt(pos) == '-') then pos += 1
        digits()
      Num(BigDecimal(s.substring(start, pos)))

/** A value already converted to [[Json]]; lets `Json.obj("a" -> 1, "b" -> "x")` accept mixed types. */
final class JsonValue(val json: Json)
object JsonValue:
  import scala.language.implicitConversions
  implicit def fromToJson[A](a: A)(implicit enc: ToJson[A]): JsonValue = JsonValue(enc(a))

/** Type class for turning values (e.g. your `state`) into [[Json]]. Case classes can `derives ToJson`. */
trait ToJson[-A]:
  def apply(a: A): Json

object ToJson:
  def apply[A](using t: ToJson[A]): ToJson[A] = t
  def instance[A](f: A => Json): ToJson[A] = f(_)

  given ToJson[Json] = identity(_)
  given ToJson[String] = Json.Str(_)
  given ToJson[Boolean] = Json.Bool(_)
  given ToJson[Int] = i => Json.Num(BigDecimal(i))
  given ToJson[Long] = l => Json.Num(BigDecimal(l))
  given ToJson[BigDecimal] = Json.Num(_)
  given ToJson[Double] = d =>
    if d.isNaN || d.isInfinite then throw IllegalArgumentException(s"$d is not representable in JSON")
    else Json.Num(BigDecimal(d))
  given [A](using e: ToJson[A]): ToJson[Option[A]] = _.fold(Json.Null)(e(_))
  given [A](using e: ToJson[A]): ToJson[Iterable[A]] = xs => Json.Arr(xs.iterator.map(e(_)).toVector)
  given [A](using e: ToJson[A]): ToJson[Map[String, A]] = m =>
    Json.Obj(VectorMap.from(m.iterator.map((k, v) => k -> e(v))))

  /** Products become objects (field order preserved, `None` fields omitted); singleton enum cases
    * become their name; other sum-type cases are encoded as their own product. Not intended for
    * recursive types — write an instance by hand for those.
    */
  inline def derived[A](using m: Mirror.Of[A]): ToJson[A] =
    inline m match
      case p: Mirror.ProductOf[A] =>
        productEncoder[A](constValueTuple[p.MirroredElemLabels].toList.asInstanceOf[List[String]], summonAll[p.MirroredElemTypes])
      case s: Mirror.SumOf[A] =>
        sumEncoder[A](s.ordinal, summonAll[s.MirroredElemTypes])

  private def productEncoder[A](labels: List[String], encs: List[ToJson[?]]): ToJson[A] = a =>
    val values = a.asInstanceOf[Product].productIterator
    Json.Obj(VectorMap.from(labels.iterator.zip(values).zip(encs.iterator).collect {
      case ((label, v), enc) if v != None => label -> enc.asInstanceOf[ToJson[Any]](v)
    }))

  private def sumEncoder[A](ordinal: A => Int, encs: List[ToJson[?]]): ToJson[A] =
    val table = encs.toVector
    a => table(ordinal(a)).asInstanceOf[ToJson[Any]](a)

  private inline def summonAll[T <: Tuple]: List[ToJson[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonOrDerive[t] :: summonAll[ts]

  // Singletons (enum cases, case objects) are checked first: ToJson is contravariant, so the sum's own
  // instance would otherwise be found for its cases and refer to itself.
  private inline def summonOrDerive[T]: ToJson[?] =
    scala.compiletime.summonFrom {
      case _: ValueOf[T]   => singleton[T]
      case e: ToJson[T]    => e
      case m: Mirror.Of[T] => derived[T](using m)
    }

  private def singleton[T]: ToJson[T] = a => Json.Str(a.toString)
