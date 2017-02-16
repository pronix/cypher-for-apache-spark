package org.opencypher.spark.impl.prototype

import java.util.concurrent.atomic.AtomicLong

import org.neo4j.cypher.internal.frontend.v3_2.ast
import org.neo4j.cypher.internal.frontend.v3_2.ast._

import scala.collection.{SortedSet, mutable}

object QueryReprBuilder {
  def from(s: Statement, q: String, tokenDefs: TokenDefs, params: Set[String]): QueryRepresentation = {
    val builder = new QueryReprBuilder(q, tokenDefs, params)
    val blocks = s match {
      case Query(_, part) => part match {
        case SingleQuery(clauses) => clauses.foldLeft(BlockRegistry.empty) {
          case (reg, c) => builder.add(c, reg)
        }
      }
      case _ => ???
    }

    builder.build(blocks)
  }
}

object BlockRegistry {
  val empty = BlockRegistry(Seq.empty)
}

case class BlockRegistry(reg: Seq[(BlockRef, BlockDef)]) {

  def register(blockDef: BlockDef): (BlockRef, BlockRegistry) = {
    val ref = BlockRef(generateName(blockDef.blockType))
    ref -> copy(reg = reg :+ ref -> blockDef)
  }

  val c = new AtomicLong()

  private def generateName(t: BlockType) = s"${t.name}_${c.incrementAndGet()}"
}

class QueryReprBuilder(query: String, tokenDefs: TokenDefs, paramNames: Set[String]) {
  val exprConverter = new ExpressionConverter(tokenDefs)
  val patternConverter = new PatternConverter

  var firstBlock: Option[BlockRef] = None

  val rColumns: mutable.Buffer[(ast.Expression, Field)] = mutable.Buffer.empty

  def add(c: Clause, blockRegistry: BlockRegistry): BlockRegistry = {
    c match {
      case Match(_, pattern, _, where) =>
        val entities = convert(pattern)
        val preds = convertWhere(where)

        val sig = BlockSignature(blockRegistry.reg.headOption.map(_._1).toSet, Set.empty, Set.empty)

        val block = MatchBlock(sig, entities, preds)
        val (ref, reg) = blockRegistry.register(block)

        reg
      case With(_, _, _, _, _, _) => throw new IllegalArgumentException("With")
      case Return(_, ReturnItems(_, items), _, _, _, _) =>
        items.foreach(addReturn)
        blockRegistry
    }
  }

  private def convert(p: ast.Pattern) = {
    patternConverter.convert(p)
  }

  private def convert(e: ast.Expression) = {
    exprConverter.convert(e)
  }

  private def convertWhere(where: Option[Where]): Set[Expr] = where match {
    case Some(Where(expr)) => convert(expr) match {
      case Ands(exprs) => exprs
      case e => Set(e)
    }
    case None => Set.empty
  }

  private def addReturn(r: ReturnItem) = {
    r match {
      case AliasedReturnItem(expr, variable) => rColumns += expr -> Field(variable.name)
      case UnaliasedReturnItem(expr, text) => rColumns += expr -> Field(text)
    }
  }

  def build(blocks: BlockRegistry): QueryRepresentation = {

    val blockStructure = BlockStructure(blocks.reg.toMap, blocks.reg.head._1)

    val parameters = paramNames.map(s => ParameterNameGenerator.generate(s) -> s).toMap

    val root = RootBlockImpl(rColumns.toMap.values.toSet, parameters.keySet, Set.empty, tokenDefs, blockStructure)

    QueryRepr(query, null, parameters, root)
  }
}

object ParameterNameGenerator {
  def generate(n: String): Param = Param(n)
}
