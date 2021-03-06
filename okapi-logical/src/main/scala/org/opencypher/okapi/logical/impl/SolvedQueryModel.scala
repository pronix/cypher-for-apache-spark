/*
 * Copyright (c) 2016-2018 "Neo4j Sweden, AB" [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.okapi.logical.impl

import org.opencypher.okapi.api.types.{CTBoolean, CTRelationship}
import org.opencypher.okapi.impl.exception.IllegalArgumentException
import org.opencypher.okapi.ir.api.block.Block
import org.opencypher.okapi.ir.api.expr.{Expr, HasType, Ors}
import org.opencypher.okapi.ir.api.pattern.Pattern
import org.opencypher.okapi.ir.api.{IRField, RelType}
import org.opencypher.okapi.ir.impl.util.VarConverters.toVar

case class SolvedQueryModel(
  fields: Set[IRField],
  predicates: Set[Expr] = Set.empty[Expr]
) {

  // extension
  def withField(f: IRField): SolvedQueryModel = copy(fields = fields + f)
  def withFields(fs: IRField*): SolvedQueryModel = copy(fields = fields ++ fs)
  def withPredicate(pred: Expr): SolvedQueryModel = copy(predicates = predicates + pred)
  def withPredicates(preds: Expr*): SolvedQueryModel = copy(predicates = predicates ++ preds)

  def ++(other: SolvedQueryModel): SolvedQueryModel =
    copy(fields ++ other.fields, predicates ++ other.predicates)

  // containment
  def contains(blocks: Block[Expr]*): Boolean = contains(blocks.toSet)
  def contains(blocks: Set[Block[Expr]]): Boolean = blocks.forall(contains)
  def contains(block: Block[Expr]): Boolean = {
    val bindsFields = block.binds.fields subsetOf fields
    val preds = block.where subsetOf predicates

    bindsFields && preds
  }

  def solves(f: IRField): Boolean = fields(f)
  def solves(p: Pattern[Expr]): Boolean = p.fields.subsetOf(fields)

  def solveRelationship(r: IRField): SolvedQueryModel = {
    r.cypherType match {
      case CTRelationship(types, _) if types.isEmpty =>
        withField(r)
      case CTRelationship(types, _) =>
        val predicate =
          if (types.size == 1)
            HasType(r, RelType(types.head))(CTBoolean)
          else
            Ors(types.map(t => HasType(r, RelType(t))(CTBoolean)).toSeq: _*)
        withField(r).withPredicate(predicate)
      case _ =>
        throw IllegalArgumentException("a relationship variable", r)
    }
  }
}

object SolvedQueryModel {
  def empty: SolvedQueryModel = SolvedQueryModel(Set.empty, Set.empty)
}
