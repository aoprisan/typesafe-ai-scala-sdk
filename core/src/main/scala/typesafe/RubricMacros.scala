package typesafe

import scala.deriving.Mirror
import scala.quoted.*

import typesafe.rubric.{choice as choiceA, named as namedA, noul as noulA, option as optionA, score as scoreA}

/** `derives Rubric` and `derives RubricChoice`. The annotations are read here to check what can be
  * checked while compiling; their values are spliced in and read when the instance is built.
  */
private[typesafe] object RubricMacros:

  /** The API's limits, from the primitives docs: a score has 2 to 10 levels, a choice up to 255 options. */
  private val MaxScoreLevels   = 10
  private val MaxChoiceOptions = 255

  /** `isUrgent` → `is_urgent`, `NeedsHuman` → `needs_human`, `HTTPError` → `http_error`. */
  def snakeCase(name: String): String =
    val sb = new StringBuilder
    name.indices.foreach { i =>
      val c = name(i)
      if c.isUpper then
        val prev = if i > 0 then name(i - 1) else ' '
        val next = if i + 1 < name.length then name(i + 1) else ' '
        if i > 0 && (prev.isLower || prev.isDigit || (prev.isUpper && next.isLower)) then sb += '_'
        sb += c.toLower
      else sb += c
    }
    sb.toString

  def rubric[R: Type](m: Expr[Mirror.ProductOf[R]])(using Quotes): Expr[Rubric[R]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[R].typeSymbol
    val where = owner.name

    def fail(msg: String, pos: Option[Position]): Nothing =
      pos.fold(report.errorAndAbort(msg))(p => report.errorAndAbort(msg, p))

    if !owner.flags.is(Flags.Case) || owner.flags.is(Flags.Module) then
      report.errorAndAbort(s"$where: a Rubric is derived for a case class whose fields are questions.")

    val params = owner.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
    if params.isEmpty then report.errorAndAbort(s"$where has no fields, so it asks no questions.")

    def stripped(t: Term): Term = t match
      case Inlined(_, _, e) => stripped(e)
      case Typed(e, _)      => stripped(e)
      case other            => other

    def args(annot: Term): List[Term] = stripped(annot) match
      case Apply(_, as) => as.map(stripped)
      case _            => Nil

    def literal(t: Term): Option[String] = stripped(t) match
      case Literal(StringConstant(s)) => Some(s)
      case _                          => None

    // How many varargs an annotation was given, when the tree says so plainly.
    def varargCount(annot: Term): Option[Int] = args(annot) match
      case List(_)       => Some(0)
      case List(_, rest) =>
        rest match
          case Repeated(elems, _) => Some(elems.size)
          case _                  => None
      case _ => None

    val fields = params.map { param =>
      val pos = param.pos
      val field = owner.fieldMember(param.name)
      val annots = (param.annotations ++ (if field.exists then field.annotations else Nil)).distinctBy(_.tpe.show)
      def of[A: Type] = annots.filter(_.tpe <:< TypeRepr.of[A])
      val kinds = of[noulA] ++ of[choiceA] ++ of[scoreA]
      val name = of[namedA] match
        case Nil => snakeCase(param.name)
        case a :: _ =>
          args(a).headOption.flatMap(literal).getOrElse(
            fail(s"$where.${param.name}: @named takes a string literal.", pos)
          )
      val tpe = TypeRepr.of[R].memberType(field).widen.dealias
      val annot = kinds match
        case List(one) => one
        case Nil =>
          fail(s"$where.${param.name} is not a question: annotate it with @noul, @choice or @score.", pos)
        case _ => fail(s"$where.${param.name} has more than one of @noul, @choice and @score.", pos)
      val nameExpr = Expr(name)

      def expect(ok: Boolean, kind: String, types: String): Unit =
        if !ok then fail(s"$where.${param.name}: a @$kind field is $types, not ${tpe.show}.", pos)

      val question: (Expr[Question], Expr[SystemOneResponse => Any]) =
        if annot.tpe <:< TypeRepr.of[noulA] then
          val q = '{ RubricSupport.noulQuestion(${ annot.asExprOf[noulA] }) }
          if tpe =:= TypeRepr.of[NoulAnswer] then q -> '{ (r: SystemOneResponse) => RubricSupport.noul(r, $nameExpr) }
          else
            expect(tpe =:= TypeRepr.of[Double], "noul", "a NoulAnswer or a Double")
            q -> '{ (r: SystemOneResponse) => RubricSupport.noul(r, $nameExpr).noul }
        else if annot.tpe <:< TypeRepr.of[scoreA] then
          if varargCount(annot).contains(0) then
            fail(s"$where.${param.name}: a @score needs its levels, lowest first: @score(\"…\", \"Calm\", \"Angry\").", pos)
          varargCount(annot).filter(n => n == 1 || n > MaxScoreLevels).foreach { n =>
            fail(s"$where.${param.name}: a @score takes 2 to $MaxScoreLevels levels, this one has $n.", pos)
          }
          val q = '{ RubricSupport.scoreQuestion(${ annot.asExprOf[scoreA] }) }
          if tpe =:= TypeRepr.of[ScoreAnswer] then q -> '{ (r: SystemOneResponse) => RubricSupport.score(r, $nameExpr) }
          else
            expect(tpe =:= TypeRepr.of[Double], "score", "a ScoreAnswer or a Double")
            q -> '{ (r: SystemOneResponse) => RubricSupport.score(r, $nameExpr).score }
        else
          val a = annot.asExprOf[choiceA]
          val labels = varargCount(annot)
          labels.filter(_ > MaxChoiceOptions).foreach { n =>
            fail(s"$where.${param.name}: a @choice takes at most $MaxChoiceOptions options, this one has $n.", pos)
          }
          if tpe =:= TypeRepr.of[ChoiceAnswer] || tpe =:= TypeRepr.of[String] then
            if labels.contains(0) then
              fail(
                s"$where.${param.name}: a ${tpe.show} choice has no options of its own; list them, " +
                  "@choice(\"…\", \"billing\", \"technical\"), or make the field an enum deriving RubricChoice.",
                pos
              )
            val decode =
              if tpe =:= TypeRepr.of[String] then '{ (r: SystemOneResponse) => RubricSupport.choice(r, $nameExpr).choice }
              else '{ (r: SystemOneResponse) => RubricSupport.choice(r, $nameExpr) }
            '{ RubricSupport.labelsQuestion($a) } -> decode
          else
            val (enumTpe, wrapped) =
              if tpe <:< TypeRepr.of[ChoiceOf[?]] then (tpe.typeArgs.head, true) else (tpe, false)
            if labels.exists(_ > 0) then
              fail(s"$where.${param.name}: ${enumTpe.show} brings its own options; drop the labels from @choice.", pos)
            enumTpe.asType match
              case '[e] =>
                val c = Expr.summon[RubricChoice[e]].getOrElse(
                  fail(
                    s"$where.${param.name}: a @choice field is an enum deriving RubricChoice, a ChoiceOf one, " +
                      s"a ChoiceAnswer or a String, not ${tpe.show}.",
                    pos
                  )
                )
                val q = '{ RubricSupport.enumQuestion($a, $c) }
                if wrapped then q -> '{ (r: SystemOneResponse) => RubricSupport.choiceOf(r, $nameExpr, $c) }
                else q -> '{ (r: SystemOneResponse) => RubricSupport.choiceOf(r, $nameExpr, $c).value }
      (param, name, question)
    }

    fields.groupBy(_._2).collectFirst { case (name, dup @ (_ :: _ :: _)) => name -> dup }.foreach { (name, dup) =>
      fail(s"$where: ${dup.map(_._1.name).mkString(" and ")} both ask under the name \"$name\".", dup(1)._1.pos)
    }

    val built = fields.map { case (_, name, (q, d)) => '{ RubricSupport.Field(${ Expr(name) }, $q, $d) } }
    '{ RubricSupport.rubric[R](${ Expr.ofList(built) }, $m) }

  def choice[E: Type](using Quotes): Expr[RubricChoice[E]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[E].typeSymbol
    val where = owner.name
    if !owner.flags.is(Flags.Enum) && !owner.flags.is(Flags.Sealed) then
      report.errorAndAbort(s"$where: a RubricChoice is derived for an enum whose cases are the options.")
    val cases = owner.children
    if cases.isEmpty then report.errorAndAbort(s"$where has no cases, so it offers no options.")
    if cases.size > MaxChoiceOptions then
      report.errorAndAbort(s"$where: a choice takes at most $MaxChoiceOptions options, this enum has ${cases.size}.")

    val entries = cases.map { child =>
      val value =
        if child.isTerm then child
        else if child.flags.is(Flags.Module) then child.companionModule
        else report.errorAndAbort(s"$where.${child.name} carries data; an option is a case without fields.", child.pos.getOrElse(Position.ofMacroExpansion))
      val annots = (child.annotations ++ value.annotations ++ (if child.isTerm then Nil else child.companionModule.annotations))
        .distinctBy(_.tpe.show)
      val label = annots.find(_.tpe <:< TypeRepr.of[namedA]) match
        case None => snakeCase(value.name.stripSuffix("$"))
        case Some(a) =>
          a match
            case Apply(_, List(Literal(StringConstant(s)))) => s
            case _ => report.errorAndAbort(s"$where.${value.name}: @named takes a string literal.")
      val description = annots.find(_.tpe <:< TypeRepr.of[optionA]) match
        case Some(a) => '{ RubricSupport.describe(${ a.asExprOf[optionA] }) }
        case None    => '{ None: Option[String] }
      (label, description, Ref(value).asExprOf[E], value)
    }

    entries.groupBy(_._1).collectFirst { case (label, dup @ (_ :: _ :: _)) => label -> dup }.foreach { (label, dup) =>
      report.errorAndAbort(s"$where: ${dup.map(_._4.name).mkString(" and ")} are both offered as \"$label\".")
    }

    val triples = entries.map((l, d, v, _) => '{ (${ Expr(l) }, $d, $v) })
    '{ RubricChoice.of[E](${ Varargs(triples) }*) }
