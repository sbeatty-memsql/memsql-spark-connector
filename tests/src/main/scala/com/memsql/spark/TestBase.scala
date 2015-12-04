// scalastyle:off magic.number file.size.limit regex

package com.memsql.spark

import java.net.InetAddress
import java.security.MessageDigest
import java.sql.{Statement, DriverManager, Connection}

import com.memsql.spark.connector.util.{Loan, MemSQLConnectionInfo}
import org.apache.spark.sql.memsql.MemSQLContext
import org.apache.spark.{SparkContext, SparkConf}

import com.memsql.spark.connector.util.JDBCImplicits._

abstract class TestBase {
  val dbName: String = {
    // Generate a unique database name based on this machine
    // This is so that multiple people can run tests against
    // the same MemSQL cluster.
    val hostMD5 = MessageDigest.getInstance("md5").digest(
      InetAddress.getLocalHost.getAddress)
    "connector_tests_" + hostMD5.slice(0, 2).map("%02x".format(_)).mkString
  }

  val masterConnectionInfo: MemSQLConnectionInfo =
    MemSQLConnectionInfo("127.0.0.1", 3306, "root", "", dbName)

  def getConnection(info: MemSQLConnectionInfo): Connection =
    DriverManager.getConnection(info.toJDBCAddress, info.user, info.password)

  def withConnection[T](handle: Connection => T): T =
    withConnection[T](masterConnectionInfo)(handle)

  def withConnection[T](info: MemSQLConnectionInfo)(handle: Connection => T): T =
    Loan[Connection](getConnection(info)).to(handle)

  def withStatement[T](handle: Statement => T): T =
    withConnection(conn => conn.withStatement(handle))

  def recreateDatabase: Unit = {
    withConnection(masterConnectionInfo.copy(dbName=""))(conn => {
      conn.withStatement(stmt => {
        stmt.execute("DROP DATABASE IF EXISTS " + dbName)
        stmt.execute("CREATE DATABASE IF NOT EXISTS " + dbName)
      })
    })
  }

  // Override this method to implement a test.
  def runTest(sc: SparkContext, msc: MemSQLContext): Unit

  /**
    * Ok lets get this party started.
    */

  val conf = new SparkConf()
    .setAppName("MemSQL Connector Test")
    .set("memsql.host", masterConnectionInfo.dbHost)
    .set("memsql.port", masterConnectionInfo.dbPort.toString)
    .set("memsql.user", masterConnectionInfo.user)
    .set("memsql.password", masterConnectionInfo.password)
    .set("memsql.defaultDatabase", masterConnectionInfo.dbName)

  val sc = new SparkContext(conf)
  val msc = new MemSQLContext(sc)

  recreateDatabase
  runTest(sc, msc)
}