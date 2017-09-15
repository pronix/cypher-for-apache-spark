package org.opencypher.caps.demo

import java.net.{URI, URLEncoder}

import org.neo4j.driver.v1.Session
import org.opencypher.caps.api.spark.CAPSSession
import org.opencypher.caps.test.BaseTestSuite
import org.opencypher.caps.test.fixture.{MiniDFSClusterFixture, Neo4jServerFixture, SparkSessionFixture}

import scala.collection.JavaConversions._

class GCDemoTest
  extends BaseTestSuite
    with SparkSessionFixture
    with Neo4jServerFixture
    with MiniDFSClusterFixture
{


  protected override val dfsTestGraphPath = "/gc_demo"

  ignore("the demo") {
    implicit val caps: CAPSSession = CAPSSession.create(session)

    val SN_US = caps.graphAt(neoURIforRegion("US"))
    val SN_EU = caps.graphAt(neoURIforRegion("EU"))
    val PRODUCTS = caps.graphAt(hdfsURI)

    // Using GRAPH OF
    val CITYFRIENDS_US = SN_US.cypher(
      """MATCH (a:Person)-[:LIVES_IN]->(city:City)<-[:LIVES_IN]-(b:Person), (a)-[:KNOWS*1..2]->(b)
        |WHERE city.name = "New York City" OR city.name = "San Francisco"
        |RETURN GRAPH result OF (a)-[:ACQUAINTED]->(b)
      """.stripMargin)

    // Using DML
    val CITYFRIENDS_EU = SN_EU.cypher(
      """MATCH (a:Person)-[:LIVES_IN]->(city:City)<-[:LIVES_IN]-(b:Person), (a)-[:KNOWS*1..2]->(b)
        |WHERE city.name = "Malmö" OR city.name = "Berlin"
        |CREATE GRAPH result
        |INTO GRAPH result
        |CREATE (a)-[:ACQUAINTED]->(b)
        |RETURN result
      """.stripMargin)

    val ALL_CITYFRIENDS = CITYFRIENDS_EU.graphs("result") union CITYFRIENDS_US.graphs("result")

    caps.persistGraphAt(ALL_CITYFRIENDS, "/friends")

    val LINKS = caps.cypher(
      s"""FROM GRAPH friends AT '/friends'
         |MATCH (p:Person)
         |FROM GRAPH products AT '$hdfsURI'
         |MATCH (c:Customer) WHERE c.name = p.name
         |RETURN GRAPH result OF (c)-[:IS]->(p)
      """.stripMargin)

    val RECO = ALL_CITYFRIENDS union PRODUCTS union LINKS.graphs("result")

    val result =RECO.cypher(
      """MATCH (a:Person)-[:ACQUAINTED]-(b:Person)-[:HAS_INTEREST]->(i:Interest),
        |      (a)<-[:IS]-(x:Customer)-[r:BOUGHT]->(p:Product {category: i.name})
        |WHERE r.rating >= 4 AND r.helpful/r.votes > 0.6
        |RETURN DISTINCT p.title AS product, b.name AS name ORDER BY p.rank LIMIT 100
      """.stripMargin)


    // Write back to Neo
    withBoltSession { session =>
      result.records.data.toLocalIterator().toIterator.foreach { row =>
        session.run(s"MATCH (p:Person {name: ${row.getString(1)}) SET p.should_buy = ${row.getString(0)}")
      }
    }
  }

  private def withBoltSession[T](f: Session => T): T = {
    val driver = org.neo4j.driver.v1.GraphDatabase.driver(neo4jHost)
    val session = driver.session()
    try {
      f(session)
    }
    finally {
      session.close()
    }
  }

  private def neoURIforRegion(region: String) = {
    val nodeQuery = URLEncoder.encode(s"MATCH (n {region: '$region'}) RETURN n", "UTF-8")
    val relQuery = URLEncoder.encode(s"MATCH ()-[r {region: '$region'}]->() RETURN r", "UTF-8")
    val uri = URI.create(s"$neo4jHost?$nodeQuery;$relQuery")
    uri
  }
  override def dataFixture = ""
}
