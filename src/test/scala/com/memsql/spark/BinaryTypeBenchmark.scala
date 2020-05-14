package com.memsql.spark

import java.sql.{Connection, DriverManager}
import java.util.Properties

import com.github.mrpowers.spark.daria.sql.SparkSessionExt._
import org.apache.spark.sql.types.{BinaryType, IntegerType}
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.util.Random

// BinaryTypeBenchmark is written to writing of the BinaryType with CPU profiler
// this feature is accessible in Ultimate version of IntelliJ IDEA
// see https://www.jetbrains.com/help/idea/async-profiler.html#profile for more details
object BinaryTypeBenchmark extends App {
  final val masterHost: String = sys.props.getOrElse("memsql.host", "localhost")
  final val masterPort: String = sys.props.getOrElse("memsql.port", "5506")

  val spark: SparkSession = SparkSession
    .builder()
    .master("local")
    .config("spark.sql.shuffle.partitions", "1")
    .config("spark.driver.bindAddress", "localhost")
    .config("spark.datasource.memsql.ddlEndpoint", s"${masterHost}:${masterPort}")
    .config("spark.datasource.memsql.database", "testdb")
    .getOrCreate()

  def jdbcConnection: Loan[Connection] = {
    val connProperties = new Properties()
    connProperties.put("user", "root")

    Loan(
      DriverManager.getConnection(
        s"jdbc:mysql://$masterHost:$masterPort",
        connProperties
      ))
  }

  def executeQuery(sql: String): Unit = {
    jdbcConnection.to(conn => Loan(conn.createStatement).to(_.execute(sql)))
  }

  executeQuery("set global default_partitions_per_leaf = 2")
  executeQuery("drop database if exists testdb")
  executeQuery("create database testdb")

  def genRandomByte(): Byte = (Random.nextInt(256) - 128).toByte
  def genRandomRow(): Array[Byte] =
    Array.fill(1000)(genRandomByte())

  val df = spark.createDF(
    List.fill(100000)(genRandomRow()).zipWithIndex,
    List(("data", BinaryType, true), ("id", IntegerType, true))
  )

  val start1 = System.nanoTime()
  df.write
    .format("memsql")
    .mode(SaveMode.Overwrite)
    .save("testdb.LoadData")

  println("Elapsed time (LoadData): " + (System.nanoTime() - start1) + "ns")

  val start2 = System.nanoTime()
  df.write
    .format("memsql")
    .option("tableKey.primary", "id")
    .option("onDuplicateKeySQL", "id = id")
    .mode(SaveMode.Overwrite)
    .save("testdb.BatchInsert")

  println("Elapsed time (BatchInsert): " + (System.nanoTime() - start2) + "ns")
}
