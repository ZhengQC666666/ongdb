/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.internal.cypher.acceptance

import java.io.{File, PrintWriter}
import java.net.{URL, URLConnection, URLStreamHandler, URLStreamHandlerFactory}
import java.nio.file.Files
import java.util.Collections.emptyMap

import org.neo4j.cypher._
import org.neo4j.cypher.internal.runtime.CreateTempFileTestSupport
import org.neo4j.graphdb.QueryExecutionException
import org.neo4j.graphdb.config.Configuration
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.graphdb.security.URLAccessRule
import org.neo4j.internal.cypher.acceptance.comparisonsupport.ComparePlansWithAssertion
import org.neo4j.internal.cypher.acceptance.comparisonsupport.Configs
import org.neo4j.internal.cypher.acceptance.comparisonsupport.CypherComparisonSupport
import org.neo4j.test.{TestEnterpriseGraphDatabaseFactory, TestGraphDatabaseFactory}
import org.neo4j.cypher.internal.v3_6.util.helpers.StringHelper.RichString
import org.scalatest.BeforeAndAfterAll

import scala.collection.JavaConverters._

class LoadCsvAcceptanceTest
  extends ExecutionEngineFunSuite
    with BeforeAndAfterAll
    with QueryStatisticsTestSupport
    with CreateTempFileTestSupport
    with CypherComparisonSupport
    with RunWithConfigTestSupport
    with ResourceTracking {

  private val expectedToFail = Configs.All - Configs.Compiled - Configs.Cost2_3

  override protected def initTest(): Unit = {
    super.initTest()
    trackResources(graph)
  }

  private def csvUrls(f: PrintWriter => Unit): Seq[String] = Seq(
    createCSVTempFileURL(f),
    createGzipCSVTempFileURL(f),
    createZipCSVTempFileURL(f)
  )

  test("import three rows with headers and match from import using index hint") {
    // Given
    val urls = csvUrls({
      writer =>
        writer.println("USERID,OrderId,field1,field2")
        writer.println("1, '4', 1, '4'")
        writer.println("2, '5', 2, '5'")
        writer.println("3, '6', 3, '6'")
    })

    graph.execute(
      s"""LOAD CSV WITH HEADERS FROM '${urls.head}' AS row
          | CREATE (user:User{userID: row.USERID})
          | CREATE (order:Order{orderID: row.OrderId})
          | CREATE (user)-[acc:ORDERED]->(order)
          | RETURN count(*)""".stripMargin
    ).resultAsString()

    resourceMonitor.assertClosedAndClear(1)

    graph.createIndex("User", "userID")

    // when & then
    for (url <- urls) {
      val result = executeWith(Configs.InterpretedAndSlotted - Configs.Version2_3,
        s"""LOAD CSV WITH HEADERS FROM '$url' AS row
            | MATCH (user:User{userID: row.USERID}) USING INDEX user:User(userID)
            | MATCH (order:Order{orderID: row.OrderId})
            | MATCH (user)-[acc:ORDERED]->(order)
            | SET acc.field1=row.field1,
            | acc.field2=row.field2
            | RETURN count(*); """.stripMargin,
        planComparisonStrategy = ComparePlansWithAssertion(_ should includeSomewhere.atLeastNTimes(1, aPlan("NodeIndexSeek").containingVariables("user"))
          , expectPlansToFail = Configs.RulePlanner))

      resourceMonitor.assertClosedAndClear(1)
      assertStats(result, propertiesWritten = 6)
      result.executionPlanDescription() should includeSomewhere.atLeastNTimes(1, aPlan("NodeIndexSeek").containingVariables("user"))
    }
  }

  test("import should not be eager") {
    createNode(Map("OrderId" -> "4", "field1" -> "REPLACE_ME"))

    val url = createCSVTempFileURL({
      writer =>
        writer.println("OrderId,field1")
        writer.println("4,hi")
        writer.println("5,yo")
        writer.println("6,bye")
    })

    val result = executeWith(Configs.UpdateConf,
      s"""LOAD CSV WITH HEADERS FROM '$url' AS row
         | WITH row.field1 as field, row.OrderId as order
         | MATCH (o) WHERE o.OrderId = order
         | SET o.field1 = field""".stripMargin,
      planComparisonStrategy = ComparePlansWithAssertion(_ should not( includeSomewhere.aPlan("Eager")),expectPlansToFail = Configs.Cost3_1))

    resourceMonitor.assertClosedAndClear(1)
    assertStats(result, nodesCreated = 0, propertiesWritten = 1)
  }

  test("import three strings") {
    val urls = csvUrls({
      writer =>
        writer.println("'Foo'")
        writer.println("'Foo'")
        writer.println("'Foo'")
    })

    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line CREATE (a {name: line[0]}) RETURN a.name")
      resourceMonitor.assertClosedAndClear(1)
      assertStats(result, nodesCreated = 3, propertiesWritten = 3)
    }
  }

  test("make sure to release all possible locks/references on input files") {
    val path = Files.createTempFile("file",".csv")

    Files.write(path,"foo".getBytes)
    assert(Files.exists(path))

    val filePathForQuery = path.normalize().toUri
    val result = execute(s"LOAD CSV FROM '$filePathForQuery' AS line CREATE (a {name: line[0]}) RETURN a.name")
    assertStats(result, nodesCreated = 1, propertiesWritten = 1)
    resourceMonitor.assertClosedAndClear(1)

    assert(Files.deleteIfExists(path))
  }

  test("import three numbers") {
    val urls = csvUrls({
      writer =>
        writer.println("1")
        writer.println("2")
        writer.println("3")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line CREATE (a {number: line[0]}) RETURN a.number")
      assertStats(result, nodesCreated = 3, propertiesWritten = 3)
      resourceMonitor.assertClosedAndClear(1)

      result.columnAs[Long]("a.number").toList === List("")
    }
  }

  test("import three rows numbers and strings") {
    val urls = csvUrls({
      writer =>
        writer.println("1, 'Aadvark'")
        writer.println("2, 'Babs'")
        writer.println("3, 'Cash'")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line CREATE (a {name: line[0]}) RETURN a.name")
      assertStats(result, nodesCreated = 3, propertiesWritten = 3)
      resourceMonitor.assertClosedAndClear(1)
    }
  }

  test("import three rows with headers") {
    val urls = csvUrls({
      writer =>
        writer.println("id,name")
        writer.println("1, 'Aadvark'")
        writer.println("2, 'Babs'")
        writer.println("3, 'Cash'")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf,
        s"LOAD CSV WITH HEADERS FROM '$url' AS line CREATE (a {id: line.id, name: line.name}) RETURN a.name"
      )

      resourceMonitor.assertClosedAndClear(1)
      assertStats(result, nodesCreated = 3, propertiesWritten = 6)
    }
  }

  test("import three rows with headers messy data") {

    val urls = csvUrls({
      writer =>
        writer.println("id,name,x")
        writer.println("1,'Aardvark',0")
        writer.println("2,'Babs'")
        writer.println("3,'Cash',1")
        writer.println("4,'Dice',\"\"")
        writer.println("5,'Emerald',")
    })
    for (url <- urls) {
      val result =executeWith(Configs.UpdateConf, s"LOAD CSV WITH HEADERS FROM '$url' AS line RETURN line.x")
      resourceMonitor.assertClosedAndClear(1)
      assert(result.toList === List(
        Map("line.x" -> "0"),
        Map("line.x" -> null),
        Map("line.x" -> "1"),
        Map("line.x" -> ""),
        Map("line.x" -> null))
      )
    }
  }

  test("import three rows with headers messy data with predicate") {
    val urls = csvUrls({
      writer =>
        writer.println("id,name,x")
        writer.println("1,'Aardvark',0")
        writer.println("2,'Babs'")
        writer.println("3,'Cash',1")
        writer.println("4,'Dice',\"\"")
        writer.println("5,'Emerald',")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf + Configs.SlottedRuntime, s"LOAD CSV WITH HEADERS FROM '$url' AS line WITH line WHERE line.x IS NOT NULL RETURN line.name")
      resourceMonitor.assertClosedAndClear(1)
      assert(result.toList === List(
        Map("line.name" -> "'Aardvark'"),
        Map("line.name" -> "'Cash'"),
        Map("line.name" -> "'Dice'"))
      )
    }
  }

  test("should handle quotes") {
    val urls = csvUrls({
      writer =>
        writer.println("String without quotes")
        writer.println("'String, with single quotes'")
        writer.println("\"String, with double quotes\"")
        writer.println( """"String with ""quotes"" in it"""")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN line as string").toList
      resourceMonitor.assertClosedAndClear(1)
      assert(result === List(
        Map("string" -> Seq("String without quotes")),
        Map("string" -> Seq("'String", " with single quotes'")),
        Map("string" -> Seq("String, with double quotes")),
        Map("string" -> Seq( """String with "quotes" in it"""))))
    }
  }

  test("should handle crlf line termination") {
    val urls = csvUrls({
      writer =>
        writer.print("1,'Aadvark',0\r\n")
        writer.print("2,'Babs'\r\n")
        writer.print("3,'Cash',1\r\n")
    })

    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN line")
      resourceMonitor.assertClosedAndClear(1)
      assert(result.toList === List(Map("line" -> Seq("1", "'Aadvark'", "0")), Map("line" -> Seq("2", "'Babs'")),
        Map("line" -> Seq("3", "'Cash'", "1"))))
    }
  }

  test("should handle lf line termination") {
    val urls = csvUrls({
      writer =>
        writer.print("1,'Aadvark',0\n")
        writer.print("2,'Babs'\n")
        writer.print("3,'Cash',1\n")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN line")
      resourceMonitor.assertClosedAndClear(1)
      assert(result.toList === List(Map("line" -> Seq("1", "'Aadvark'", "0")), Map("line" -> Seq("2", "'Babs'")),
        Map("line" -> Seq("3", "'Cash'", "1"))))
    }
  }

  test("should handle cr line termination") {
    val urls = csvUrls({
      writer =>
        writer.print("1,'Aadvark',0\r")
        writer.print("2,'Babs'\r")
        writer.print("3,'Cash',1\r")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN line")
      resourceMonitor.assertClosedAndClear(1)
      assert(result.toList === List(Map("line" -> Seq("1", "'Aadvark'", "0")), Map("line" -> Seq("2", "'Babs'")),
        Map("line" -> Seq("3", "'Cash'", "1"))))
    }
  }

  test("should handle custom field terminator") {
    val urls = csvUrls({
      writer =>
        writer.println("1;'Aadvark';0")
        writer.println("2;'Babs'")
        writer.println("3;'Cash';1")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line FIELDTERMINATOR ';' RETURN line")
      resourceMonitor.assertClosedAndClear(1)
      assert(result.toList === List(Map("line" -> Seq("1", "'Aadvark'", "0")), Map("line" -> Seq("2", "'Babs'")),
        Map("line" -> Seq("3", "'Cash'", "1"))))
    }
  }

  test("should open file containing strange chars with '") {
    val filename = ensureNoIllegalCharsInWindowsFilePath("cypher '%^&!@#_)(098.:,;[]{}\\~$*+-")
    val url = createCSVTempFileURL(filename)({
      writer =>
        writer.println("something")
    })

    val result = executeWith(Configs.UpdateConf, "LOAD CSV FROM \"" + url + "\" AS line RETURN line as string").toList
    resourceMonitor.assertClosedAndClear(1)
    assert(result === List(Map("string" -> Seq("something"))))
  }

  test("should open file containing strange chars with \"") {
    val filename = ensureNoIllegalCharsInWindowsFilePath("cypher \"%^&!@#_)(098.:,;[]{}\\~$*+-")
    val url = createCSVTempFileURL(filename)({
      writer =>
        writer.println("something")
    })

    val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN line as string").toList
    resourceMonitor.assertClosedAndClear(1)
    assert(result === List(Map("string" -> Seq("something"))))
  }

  test("empty file does not create anything") {
    val urls = csvUrls(writer => {})
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line CREATE (a {name: line[0]}) RETURN a.name")
      resourceMonitor.assertClosedAndClear(1)
      assertStats(result, nodesCreated = 0)
    }
  }

  test("should be able to open relative paths with dot") {
    val url = createCSVTempFileURL(filename = "cypher", dir = "./")(
        writer =>
            writer.println("something")
    ).cypherEscape

    val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line CREATE (a {name: line[0]}) RETURN a.name")
    resourceMonitor.assertClosedAndClear(1)
    assertStats(result, nodesCreated = 1, propertiesWritten = 1)
  }

  test("should be able to open relative paths with dotdot") {
    val url = createCSVTempFileURL(filename = "cypher", dir = "../")(
        writer =>
            writer.println("something")
    ).cypherEscape

    val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line CREATE (a {name: line[0]}) RETURN a.name")
    resourceMonitor.assertClosedAndClear(1)
    assertStats(result, nodesCreated = 1, propertiesWritten = 1)
  }

  test("should handle null keys in maps as result value") {
    val urls = csvUrls({
      writer =>
        writer.println("DEPARTMENT ID;DEPARTMENT NAME;")
        writer.println("010-1010;MFG Supplies;")
        writer.println("010-1011;Corporate Procurement;")
        writer.println("010-1015;MFG - Engineering HQ;")
    })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf, s"LOAD CSV WITH HEADERS FROM '$url' AS line FIELDTERMINATOR ';' RETURN *").toList
      resourceMonitor.assertClosedAndClear(1)
      assert(result === List(
        Map("line" -> Map("DEPARTMENT ID" -> "010-1010", "DEPARTMENT NAME" -> "MFG Supplies",
          null.asInstanceOf[String] -> null)),
        Map("line" -> Map("DEPARTMENT ID" -> "010-1011", "DEPARTMENT NAME" -> "Corporate Procurement",
          null.asInstanceOf[String] -> null)),
        Map("line" -> Map("DEPARTMENT ID" -> "010-1015", "DEPARTMENT NAME" -> "MFG - Engineering HQ",
          null.asInstanceOf[String] -> null))
      ))
    }
  }

  test("should handle returning null keys") {
    val urls = csvUrls({
      writer =>
        writer.println("DEPARTMENT ID;DEPARTMENT NAME;")
        writer.println("010-1010;MFG Supplies;")
        writer.println("010-1011;Corporate Procurement;")
        writer.println("010-1015;MFG - Engineering HQ;")
    })

    for (url <- urls) {
      //Using innerExecuteDeprecated because different versions has different ordering for keys
      val result =  executeSingle(s"LOAD CSV WITH HEADERS FROM '$url' AS line FIELDTERMINATOR ';' RETURN keys(line)").toList

      assert(result === List(
        Map("keys(line)" -> List(null, "DEPARTMENT ID", "DEPARTMENT NAME" )),
        Map("keys(line)" -> List(null, "DEPARTMENT ID", "DEPARTMENT NAME" )),
        Map("keys(line)" -> List(null, "DEPARTMENT ID", "DEPARTMENT NAME" ))
      ))
    }
  }

  test("should fail gracefully when loading missing file") {
    failWithError(expectedToFail, "LOAD CSV FROM 'file:///./these_are_not_the_droids_you_are_looking_for.csv' AS line CREATE (a {name:line[0]})",
      List("Couldn't load the external resource at: file:/./these_are_not_the_droids_you_are_looking_for.csv"))
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should be able to download data from the web") {
    val url = s"http://127.0.0.1:$port/test.csv".cypherEscape

    val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN count(line)")
    resourceMonitor.assertClosedAndClear(1)
    result.columnAs[Long]("count(line)").toList should equal(List(3))
  }

  test("should be able to download from a website when redirected and cookies are set") {
    val url = s"http://127.0.0.1:$port/redirect_test.csv".cypherEscape

    val result = executeWith(Configs.UpdateConf, s"LOAD CSV FROM '$url' AS line RETURN count(line)")
    resourceMonitor.assertClosedAndClear(1)
    result.columnAs[Long]("count(line)").toList should equal(List(3))
  }

  test("should fail gracefully when getting 404") {
    failWithError(expectedToFail, s"LOAD CSV FROM 'http://127.0.0.1:$port/these_are_not_the_droids_you_are_looking_for/' AS line CREATE (a {name:line[0]})",
      List("Couldn't load the external resource at"))
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should fail gracefully when loading non existent (local) site") {
    failWithError(expectedToFail, "LOAD CSV FROM 'http://127.0.0.1:9999/these_are_not_the_droids_you_are_looking_for/' AS line CREATE (a {name:line[0]})",
      List("Couldn't load the external resource at"))
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should reject URLs that are not valid") {

    failWithError(expectedToFail, s"LOAD CSV FROM 'morsecorba://sos' AS line CREATE (a {name:line[0]})",
      List("Invalid URL 'morsecorba://sos': unknown protocol: morsecorba"))
    resourceMonitor.assertClosedAndClear(0)

    failWithError(expectedToFail, s"LOAD CSV FROM '://' AS line CREATE (a {name:line[0]})",
      List("Invalid URL '://': no protocol: ://"))
    resourceMonitor.assertClosedAndClear(0)

    failWithError(expectedToFail, s"LOAD CSV FROM 'foo.bar' AS line CREATE (a {name:line[0]})",
      List("Invalid URL 'foo.bar': no protocol: foo.bar"))
    resourceMonitor.assertClosedAndClear(0)

    failWithError(expectedToFail, s"LOAD CSV FROM 'jar:file:///tmp/bar.jar' AS line CREATE (a {name:line[0]})",
      List("Invalid URL 'jar:file:///tmp/bar.jar': no !/ in spec"))
    resourceMonitor.assertClosedAndClear(0)

    failWithError(expectedToFail, "LOAD CSV FROM 'file://./blah.csv' AS line CREATE (a {name:line[0]})",
      List("Cannot load from URL 'file://./blah.csv': file URL may not contain an authority section (i.e. it should be 'file:///')"))
    resourceMonitor.assertClosedAndClear(0)

    failWithError(expectedToFail, "LOAD CSV FROM 'file:///tmp/blah.csv?q=foo' AS line CREATE (a {name:line[0]})",
      List("Cannot load from URL 'file:///tmp/blah.csv?q=foo': file URL may not contain a query component"))
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should deny URLs for blocked protocols") {
    failWithError(expectedToFail, s"LOAD CSV FROM 'jar:file:///tmp/bar.jar!/blah/foo.csv' AS line CREATE (a {name:line[0]})",
      List("Cannot load from URL 'jar:file:///tmp/bar.jar!/blah/foo.csv': loading resources via protocol 'jar' is not permitted"))
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should fail for file urls if local file access disallowed") {
    val db = acceptanceTestDatabaseBuilder
      .setConfig(GraphDatabaseSettings.allow_file_urls, "false")
      .newGraphDatabase()
    try {
      intercept[QueryExecutionException] {
        db.execute(s"LOAD CSV FROM 'file:///tmp/blah.csv' AS line CREATE (a {name:line[0]})", emptyMap())
      }.getMessage should endWith(": configuration property 'dbms.security.allow_csv_import_from_file_urls' is false")
    } finally {
      db.shutdown()
    }
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should allow paths relative to authorized directory") {
    val dir = createTempDirectory("loadcsvroot")
    pathWrite(dir.resolve("tmp/blah.csv"))(
      writer =>
        writer.println("something")
    )

    val db = acceptanceTestDatabaseBuilder
      .setConfig(GraphDatabaseSettings.load_csv_file_url_root, dir.toString)
      .newGraphDatabase()

    trackResources(db)

    try {
      val result = db.execute(s"LOAD CSV FROM 'file:///tmp/blah.csv' AS line RETURN line[0] AS field", emptyMap())
      result.asScala.map(_.asScala).toList should equal(List(Map("field" -> "something")))
      result.close()
    } finally {
      db.shutdown()
    }
    resourceMonitor.assertClosedAndClear(1)
  }

  test("should restrict file urls to be rooted within an authorized directory") {
    val dir = createTempDirectory("loadcsvroot")

    val db = acceptanceTestDatabaseBuilder
      .setConfig(GraphDatabaseSettings.load_csv_file_url_root, dir.toString)
      .newGraphDatabase()

    trackResources(db)

    try {
      intercept[QueryExecutionException] {
        db.execute(s"LOAD CSV FROM 'file:///../foo.csv' AS line RETURN line[0] AS field", emptyMap()).asScala.size
      }.getMessage should endWith(" file URL points outside configured import directory").or(include("Couldn't load the external resource at"))
    } finally {
      db.shutdown()
    }
    resourceMonitor.assertClosedAndClear(0)
  }

  test("should apply protocol rules set at db construction") {
    val url = createCSVTempFileURL({
      writer =>
        writer.println("something")
    })

    URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory {
      override def createURLStreamHandler(protocol: String): URLStreamHandler =
        if (protocol != "testproto")
          null
        else
          new URLStreamHandler {
            override def openConnection(u: URL): URLConnection = new URL(url).openConnection()
          }
    })

    val db = new TestGraphDatabaseFactory()
      .addURLAccessRule( "testproto", new URLAccessRule {
        override def validate(config: Configuration, url: URL): URL = url
      }).newImpermanentDatabaseBuilder(acceptanceDbFolder).newGraphDatabase()

    trackResources(db)

    try {
      val result = db.execute(s"LOAD CSV FROM 'testproto://foo.bar' AS line RETURN line[0] AS field", emptyMap())
      result.asScala.map(_.asScala).toList should equal(List(Map("field" -> "something")))
      resourceMonitor.assertClosedAndClear(1)
    } finally {
      db.shutdown()
    }
  }

  test("eager queries should be handled correctly") {
    val urls = csvUrls({
      writer =>
        writer.println("id,title,country,year")
        writer.println("1,Wall Street,USA,1987")
        writer.println("2,The American President,USA,1995")
        writer.println("3,The Shawshank Redemption,USA,1994")
    })
    for (url <- urls) {
      val query =
        s"""LOAD CSV WITH HEADERS FROM '$url' AS csvLine
           |MERGE (country:Country {name: csvLine.country})
           |CREATE (movie:Movie {id: toInt(csvLine.id), title: csvLine.title, year:toInt(csvLine.year)})
           |CREATE (movie)-[:MADE_IN]->(country)""".stripMargin
      executeSingle(query, Map.empty)
      resourceMonitor.assertClosedAndClear(1)

      //make sure three unique movies are created
      val result = executeWith(Configs.All, "match (m:Movie) return m.id AS id ORDER BY m.id").toList

      result should equal(List(Map("id" -> 1), Map("id" -> 2), Map("id" -> 3)))
      //empty database
      executeSingle("MATCH (n) DETACH DELETE n", Map.empty)
    }
  }

  test("should be able to use expression as url") {
    val url = createCSVTempFileURL({
      writer =>
        writer.println("'Foo'")
        writer.println("'Foo'")
        writer.println("'Foo'")
    }).cypherEscape
    val first = url.substring(0, url.length / 2)
    val second = url.substring(url.length / 2)
    createNode(Map("prop" -> second))

    val result = executeWith(Configs.UpdateConf, s"MATCH (n) WITH n, '$first' as prefix LOAD CSV FROM prefix + n.prop AS line CREATE (a {name: line[0]}) RETURN a.name")
    resourceMonitor.assertClosedAndClear(1)
    assertStats(result, nodesCreated = 3, propertiesWritten = 3)
  }

  test("should not project too much when there is an aggregation on a with after load csv") {
    val url = createCSVTempFileURL({
      writer =>
        writer.println("10")
    }).cypherEscape
    val query  = s"""LOAD CSV FROM '$url' as row
                   |WITH row where row[0] = 10
                   |WITH distinct toInt(row[0]) as data
                   |MERGE (c:City {data:data})
                   |RETURN count(*) as c""".stripMargin

    val result = executeWith(Configs.UpdateConf, query)
    resourceMonitor.assertClosedAndClear(1)
    result.columnAs("c").toList should equal(List(0))
  }

  test("empty headers file should not throw") {
    val urls = csvUrls({ _ => {} })
    for (url <- urls) {
      val result = executeWith(Configs.UpdateConf,
        s"LOAD CSV WITH HEADERS FROM '$url' AS line RETURN count(*)"
      )

      resourceMonitor.assertClosedAndClear(1)
      result.toList should equal(List(Map("count(*)" -> 0)))
    }
  }

  test("should give nice error message when overflowing the buffer") {
    runWithConfig(GraphDatabaseSettings.csv_buffer_size -> (1 * 1024 * 1024).toString) { db =>

      trackResources(db)

      val longName  = "f"* 6000000
      val urls = csvUrls({
        writer =>
          writer.println("\"prop\"")
          writer.println(longName)
      })
      for (url <- urls) {
        //TODO this message should mention `dbms.import.csv.buffer_size` in 3.5
        val error = intercept[QueryExecutionException](db.execute(
          s"""LOAD CSV WITH HEADERS FROM '$url' AS row
             |RETURN row.prop""".stripMargin).next().get("row.prop"))
        error.getMessage should startWith(
          """Tried to read a field larger than buffer size 1048576.""".stripMargin)
        resourceMonitor.assertClosedAndClear(1)
      }
    }
  }

  test("should be able to configure db to handle huge fields") {
    runWithConfig(GraphDatabaseSettings.csv_buffer_size -> (4 * 1024 * 1024).toString) { db =>

      trackResources(db)

      val longName  = "f"* 6000000
      val urls = csvUrls({
        writer =>
          writer.println("\"prop\"")
          writer.println(longName)
      })
      for (url <- urls) {
        val result = db.execute(
          s"""LOAD CSV WITH HEADERS FROM '$url' AS row
             |RETURN row.prop""".stripMargin)
        result.next().get("row.prop") should equal(longName)
        resourceMonitor.assertClosedAndClear(1)
      }
    }
  }

  private def ensureNoIllegalCharsInWindowsFilePath(filename: String) = {
    // isWindows?
    if ('\\' == File.separatorChar) {
      // http://msdn.microsoft.com/en-us/library/windows/desktop/aa365247%28v=vs.85%29.aspxs
      val illegalCharsInWindowsFilePath = "/?<>\\:*|\""
      // just replace the illegal chars with a 'a'
      illegalCharsInWindowsFilePath.foldLeft(filename)((current, c) => current.replace(c, 'a'))
    } else {
      filename
    }
  }

  private val CSV_DATA_CONTENT = "1,1,1\n2,2,2\n3,3,3\n".getBytes
  private val CSV_PATH = "/test.csv"
  private val CSV_COOKIE_PATH = "/cookie_test.csv"
  private val CSV_REDIRECT_PATH = "/redirect_test.csv"
  private val MAGIC_COOKIE = "neoCookie=Magic"
  private var httpServer: HttpServerTestSupport = _
  private var port = -1

  override def beforeAll() {
    val  builder = new HttpServerTestSupportBuilder()
    builder.onPathReplyWithData(CSV_PATH, CSV_DATA_CONTENT)

    builder.onPathReplyWithData(CSV_COOKIE_PATH, CSV_DATA_CONTENT)
    builder.onPathReplyOnlyWhen(CSV_COOKIE_PATH, HttpServerTestSupport.hasCookie(MAGIC_COOKIE))

    builder.onPathRedirectTo(CSV_REDIRECT_PATH, CSV_COOKIE_PATH)
    builder.onPathTransformResponse(CSV_REDIRECT_PATH, HttpServerTestSupport.setCookie(MAGIC_COOKIE))

    httpServer = builder.build()
    httpServer.start()
    port = httpServer.boundInfo.getPort
    assert(port > 0)
  }

  override def afterAll() {
    httpServer.stop()
  }

  private def acceptanceTestDatabaseBuilder = {
    new TestEnterpriseGraphDatabaseFactory().newImpermanentDatabaseBuilder(acceptanceDbFolder)
  }

  private def acceptanceDbFolder = {
    new File("target/test-data/acceptance-db")
  }

  private def createFile(filename: String = "cypher", dir: String = null)(f: PrintWriter => Unit): String =
    createTempFileURL(filename, ".csv")(f)
}
