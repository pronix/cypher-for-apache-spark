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
package org.opencypher.spark.impl

import org.apache.spark.sql.Row
import org.opencypher.okapi.testing.Bag
import org.opencypher.spark.impl
import org.opencypher.spark.impl.DataFrameOps._
import org.opencypher.spark.testing.CAPSTestSuite
import org.opencypher.spark.testing.fixture.{GraphConstructionFixture, RecordsVerificationFixture, TeamDataFixture}

class CAPSUnionGraphTest extends CAPSTestSuite
  with GraphConstructionFixture
  with RecordsVerificationFixture
  with TeamDataFixture {

  import CAPSGraphTestData._

  def testGraph1 = initGraph("CREATE (:Person {name: 'Mats'})")
  def testGraph2 = initGraph("CREATE (:Person {name: 'Phil'})")

  it("supports UNION ALL") {
    testGraph1.unionAll(testGraph2).cypher("""MATCH (n) RETURN DISTINCT id(n)""").getRecords.size should equal(2)
  }

  test("Node scan from single node CAPSRecords") {
    val inputGraph = initGraph(`:Person`)
    val inputNodes = inputGraph.nodes("n")

    val patternGraph = CAPSUnionGraph(CAPSGraph.create(inputNodes, inputGraph.schema))
    val nodes = patternGraph.nodes("n")

    val cols = Seq(
      n,
      nHasLabelPerson,
      nHasLabelSwedish,
      nHasPropertyLuckyNumber,
      nHasPropertyName
    )
    val data = Bag(
      Row(0L, true, true, 23L, "Mats"),
      Row(1L, true, false, 42L, "Martin"),
      Row(2L, true, false, 1337L, "Max"),
      Row(3L, true, false, 9L, "Stefan")
    )

    verify(nodes, cols, data)
  }

  test("Node scan from multiple single node CAPSRecords") {
    val unionGraph = impl.CAPSUnionGraph(initGraph(`:Person`), initGraph(`:Book`))
    val nodes = unionGraph.nodes("n")
    val cols = Seq(
      n,
      nHasLabelBook,
      nHasLabelPerson,
      nHasLabelSwedish,
      nHasPropertyLuckyNumber,
      nHasPropertyName,
      nHasPropertyTitle,
      nHasPropertyYear
    )
    val data = Bag(
      Row(0L, false, true, true, 23L, "Mats", null, null),
      Row(1L, false, true, false, 42L, "Martin", null, null),
      Row(2L, false, true, false, 1337L, "Max", null, null),
      Row(3L, false, true, false, 9L, "Stefan", null, null),
      Row(0L.setTag(1), true, false, false, null, null, "1984", 1949L),
      Row(1L.setTag(1), true, false, false, null, null, "Cryptonomicon", 1999L),
      Row(2L.setTag(1), true, false, false, null, null, "The Eye of the World", 1990L),
      Row(3L.setTag(1), true, false, false, null, null, "The Circle", 2013L)
    )

    verify(nodes, cols, data)
  }

  test("Returns only distinct results") {
    val scanGraph1 = CAPSGraph.create(personTable)
    val scanGraph2 = CAPSGraph.create(personTable)

    val unionGraph = CAPSUnionGraph(scanGraph1, scanGraph2)
    val nodes = unionGraph.nodes("n")

    val cols = Seq(
      n,
      nHasLabelPerson,
      nHasLabelSwedish,
      nHasPropertyLuckyNumber,
      nHasPropertyName
    )
    val data = Bag(
      Row(1L, true, true, 23L, "Mats"),
      Row(2L, true, false, 42L, "Martin"),
      Row(3L, true, false, 1337L, "Max"),
      Row(4L, true, false, 9L, "Stefan"),
      Row(1L.setTag(1), true, true, 23L, "Mats"),
      Row(2L.setTag(1), true, false, 42L, "Martin"),
      Row(3L.setTag(1), true, false, 1337L, "Max"),
      Row(4L.setTag(1), true, false, 9L, "Stefan")
    )

    verify(nodes, cols, data)
  }

  it("assigns non-conflicting tags to graphs") {
    val scanGraph1 = CAPSGraph.create(personTable)
    val scanGraph2 = CAPSGraph.create(personTable)

  }

  private def initPersonReadsBookGraph: CAPSGraph = {
    impl.CAPSUnionGraph(
      initGraph(`:READS`),
      impl.CAPSUnionGraph(initGraph(`:Book`),
        impl.CAPSUnionGraph(initGraph(`:Person`))))
  }
}
