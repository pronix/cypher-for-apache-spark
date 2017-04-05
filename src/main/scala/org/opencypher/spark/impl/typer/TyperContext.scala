package org.opencypher.spark.impl.typer

import cats.syntax.all._
import org.atnos.eff._
import org.atnos.eff.all._
import org.neo4j.cypher.internal.frontend.v3_2.ast.{Expression, Parameter}
import org.neo4j.cypher.internal.frontend.v3_2.symbols
import org.opencypher.spark.api.types._

object TyperContext {
  def empty = TyperContext(Map.empty)
}

final case class TyperContext(typings: Map[Expression, CypherType]) {

  def :+(entry: (Expression, CypherType)): TyperContext = {
    val (expr, typ) = entry
    expr match {
      case param: Parameter =>
        val realTyp = typ meet fromFrontendType(param.parameterType)
        copy(typings = typings
          .updated(expr, realTyp)
          .updated(param.copy(parameterType = symbols.CTAny)(param.position), realTyp)
        )

      case _ =>
        copy(typings = typings.updated(expr, typ))
    }
  }

  def getTypeOf[R : _keepsErrors : _hasContext](it: Expression): Eff[R, CypherType] =
    typings.get(it).map(pure[R, CypherType]).getOrElse(error(UnTypedExpr(it)))

  def putUpdated[R : _keepsErrors : _hasContext](entry: (Expression, CypherType)): Eff[R, CypherType] = {
    val (ref, newTyp) = entry
    typings.get(ref) match {
      case Some(oldTyp) if oldTyp == newTyp =>
        pure(oldTyp)

      case Some(oldTyp) =>
        put[R, TyperContext](copy(typings = typings.updated(ref, CTWildcard))) >>
        error(AlreadyTypedExpr(ref, oldTyp, newTyp))

      case None =>
        put[R, TyperContext](copy(typings = typings.updated(ref, newTyp))) >> pure(newTyp)
    }
  }
}