/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.runtime.stream.sql

import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.connector.ChangelogMode
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.factories.TestValuesTableFactory.{changelogRow, registerData}
import org.apache.flink.table.planner.plan.utils.JavaUserDefinedAggFunctions.{ConcatDistinctAggFunction, WeightedAvg}
import org.apache.flink.table.planner.plan.utils.WindowEmitStrategy.{TABLE_EXEC_EMIT_ALLOW_LATENESS, TABLE_EXEC_EMIT_LATE_FIRE_DELAY, TABLE_EXEC_EMIT_LATE_FIRE_ENABLED}
import org.apache.flink.table.planner.runtime.utils._
import org.apache.flink.table.planner.runtime.utils.StreamingWithStateTestBase.{HEAP_BACKEND, ROCKSDB_BACKEND, StateBackendMode}
import org.apache.flink.table.planner.runtime.utils.TimeTestUtil.TimestampAndWatermarkWithOffset
import org.apache.flink.table.types.DataType
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}
import org.apache.flink.types.Row

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.extension.ExtendWith

import java.lang.{Long => JLong}
import java.time.{Duration, LocalDateTime, ZoneId, ZoneOffset}
import java.util

import scala.collection.JavaConversions._

@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class GroupWindowITCase(mode: StateBackendMode, useTimestampLtz: Boolean)
  extends StreamingWithStateTestBase(mode) {

  val SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai")

  val upsertSourceCurrencyData = List(
    changelogRow("+U", "Euro", "no1", JLong.valueOf(114L), localDateTime(1L)),
    changelogRow("+U", "US Dollar", "no1", JLong.valueOf(100L), localDateTime(1L)),
    changelogRow("+U", "US Dollar", "no1", JLong.valueOf(102L), localDateTime(2L)),
    changelogRow("+U", "Yen", "no1", JLong.valueOf(1L), localDateTime(3L)),
    changelogRow("+U", "RMB", "no1", JLong.valueOf(702L), localDateTime(4L)),
    changelogRow("+U", "Euro", "no1", JLong.valueOf(118L), localDateTime(18L)),
    changelogRow("+U", "US Dollar", "no1", JLong.valueOf(104L), localDateTime(4L)),
    changelogRow("-D", "RMB", "no1", JLong.valueOf(702L), localDateTime(4L))
  )

  @BeforeEach
  override def before(): Unit = {
    super.before()

    val timestampDataId = TestValuesTableFactory.registerData(TestData.timestampData)
    val timestampLtzDataId = TestValuesTableFactory.registerData(TestData.timestampLtzData)

    tEnv.getConfig.setLocalTimeZone(SHANGHAI_ZONE)
    tEnv.executeSql(
      s"""
         |CREATE TABLE testTable (
         | `ts` ${if (useTimestampLtz) "BIGINT" else "STRING"},
         | `int` INT,
         | `double` DOUBLE,
         | `float` FLOAT,
         | `bigdec` DECIMAL(10, 2),
         | `string` STRING,
         | `name` STRING,
         | `rowtime` AS
         | ${if (useTimestampLtz) "TO_TIMESTAMP_LTZ(`ts`, 3)" else "TO_TIMESTAMP(`ts`)"},
         | proctime as PROCTIME(),
         | WATERMARK for `rowtime` AS `rowtime` - INTERVAL '0.01' SECOND
         |) WITH (
         | 'connector' = 'values',
         | 'data-id' = '${if (useTimestampLtz) timestampLtzDataId else timestampDataId}',
         | 'failing-source' = 'true'
         |)
         |""".stripMargin)
  }

  @TestTemplate
  def testEventTimeSlidingWindow(): Unit = {
    tEnv.createTemporarySystemFunction("concat_distinct_agg", classOf[ConcatDistinctAggFunction])
    val sql =
      """
        |SELECT
        |  `string`,
        |  HOP_START(rowtime, INTERVAL '0.004' SECOND, INTERVAL '0.005' SECOND),
        |  HOP_ROWTIME(rowtime, INTERVAL '0.004' SECOND, INTERVAL '0.005' SECOND),
        |  COUNT(1),
        |  SUM(1),
        |  COUNT(`int`),
        |  COUNT(DISTINCT `float`),
        |  concat_distinct_agg(name)
        |FROM testTable
        |GROUP BY `string`, HOP(rowtime, INTERVAL '0.004' SECOND, INTERVAL '0.005' SECOND)
      """.stripMargin

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()

    val expected = if (useTimestampLtz) {
      Seq(
        "Hallo,1970-01-01T00:00,1969-12-31T16:00:00.004Z,1,1,1,1,a",
        "Hello world,1970-01-01T00:00:00.004,1969-12-31T16:00:00.008Z,1,1,1,1,a",
        "Hello world,1970-01-01T00:00:00.008,1969-12-31T16:00:00.012Z,1,1,1,1,a",
        "Hello world,1970-01-01T00:00:00.012,1969-12-31T16:00:00.016Z,1,1,1,1,b",
        "Hello world,1970-01-01T00:00:00.016,1969-12-31T16:00:00.020Z,1,1,1,1,b",
        "Hello,1970-01-01T00:00,1969-12-31T16:00:00.004Z,2,2,2,2,a",
        "Hello,1970-01-01T00:00:00.004,1969-12-31T16:00:00.008Z,3,3,3,2,a|b",
        "Hi,1970-01-01T00:00,1969-12-31T16:00:00.004Z,1,1,1,1,a",
        "null,1970-01-01T00:00:00.028,1969-12-31T16:00:00.032Z,1,1,1,1,null",
        "null,1970-01-01T00:00:00.032,1969-12-31T16:00:00.036Z,1,1,1,1,null"
      )
    } else {
      Seq(
        "Hallo,1970-01-01T00:00,1970-01-01T00:00:00.004,1,1,1,1,a",
        "Hello world,1970-01-01T00:00:00.004,1970-01-01T00:00:00.008,1,1,1,1,a",
        "Hello world,1970-01-01T00:00:00.008,1970-01-01T00:00:00.012,1,1,1,1,a",
        "Hello world,1970-01-01T00:00:00.012,1970-01-01T00:00:00.016,1,1,1,1,b",
        "Hello world,1970-01-01T00:00:00.016,1970-01-01T00:00:00.020,1,1,1,1,b",
        "Hello,1970-01-01T00:00,1970-01-01T00:00:00.004,2,2,2,2,a",
        "Hello,1970-01-01T00:00:00.004,1970-01-01T00:00:00.008,3,3,3,2,a|b",
        "Hi,1970-01-01T00:00,1970-01-01T00:00:00.004,1,1,1,1,a",
        "null,1970-01-01T00:00:00.028,1970-01-01T00:00:00.032,1,1,1,1,null",
        "null,1970-01-01T00:00:00.032,1970-01-01T00:00:00.036,1,1,1,1,null"
      )
    }
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCascadingTumbleWindow(): Unit = {

    val sql =
      """
        |SELECT SUM(cnt)
        |FROM (
        |  SELECT COUNT(1) AS cnt, TUMBLE_ROWTIME(rowtime, INTERVAL '10' SECOND) AS ts
        |  FROM testTable
        |  GROUP BY `int`, `string`, TUMBLE(rowtime, INTERVAL '10' SECOND)
        |)
        |GROUP BY TUMBLE(ts, INTERVAL '10' SECOND)
        |""".stripMargin

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()

    val expected = Seq("9")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testMinMaxWithTumblingWindow(): Unit = {
    tEnv.getConfig.set("table.exec.emit.early-fire.enabled", "true")
    tEnv.getConfig.set("table.exec.emit.early-fire.delay", "1000 ms")

    val sql =
      """
        |SELECT
        | MAX(max_ts),
        | MIN(min_ts),
        | `string`
        |FROM(
        | SELECT
        | `string`,
        | `int`,
        | MAX(rowtime) as max_ts,
        | MIN(rowtime) as min_ts
        | FROM testTable
        | GROUP BY `string`, `int`, TUMBLE(rowtime, INTERVAL '10' SECOND))
        |GROUP BY `string`
      """.stripMargin
    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()
    val expected = if (useTimestampLtz) {
      Seq(
        "1969-12-31T16:00:00.001Z,1969-12-31T16:00:00.001Z,Hi",
        "1969-12-31T16:00:00.002Z,1969-12-31T16:00:00.002Z,Hallo",
        "1969-12-31T16:00:00.007Z,1969-12-31T16:00:00.003Z,Hello",
        "1969-12-31T16:00:00.016Z,1969-12-31T16:00:00.008Z,Hello world",
        "1969-12-31T16:00:00.032Z,1969-12-31T16:00:00.032Z,null"
      )
    } else {
      Seq(
        "1970-01-01T00:00:00.001,1970-01-01T00:00:00.001,Hi",
        "1970-01-01T00:00:00.002,1970-01-01T00:00:00.002,Hallo",
        "1970-01-01T00:00:00.007,1970-01-01T00:00:00.003,Hello",
        "1970-01-01T00:00:00.016,1970-01-01T00:00:00.008,Hello world",
        "1970-01-01T00:00:00.032,1970-01-01T00:00:00.032,null"
      )
    }
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  // used to verify compile works normally when constants exists in group window key (FLINK-17553)
  @TestTemplate
  def testWindowAggregateOnConstantValue(): Unit = {
    val sql =
      """
        |SELECT TUMBLE_END(rowtime, INTERVAL '0.003' SECOND), COUNT(name)
        |FROM testTable
        | GROUP BY 'a', TUMBLE(rowtime, INTERVAL '0.003' SECOND)
      """.stripMargin
    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()
    val expected = Seq(
      "1970-01-01T00:00:00.003,2",
      "1970-01-01T00:00:00.006,2",
      "1970-01-01T00:00:00.009,3",
      "1970-01-01T00:00:00.018,1",
      "1970-01-01T00:00:00.033,0")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProctimeCascadeWindowAgg: Unit = {
    val sql =
      """
        | SELECT
        |  cnt AS key,
        |  TUMBLE_START(pt1, INTERVAL '0.01' SECOND) AS window_start,
        |  TUMBLE_END(pt1, INTERVAL '0.01' SECOND) AS window_start,
        |  TUMBLE_PROCTIME(pt1, INTERVAL '0.01' SECOND) as window_proctime,
        |  MAX(s1) AS v1,
        |  MAX(e1) AS v2
        | FROM
        | (SELECT
        |   TUMBLE_START(proctime, INTERVAL '0.005' SECOND) as s1,
        |   TUMBLE_END(proctime, INTERVAL '0.005' SECOND) e1,
        |   TUMBLE_PROCTIME(proctime, INTERVAL '0.005' SECOND) as pt1,
        |   COUNT(name) as cnt
        |  FROM testTable
        |  GROUP BY 'a', TUMBLE(proctime, INTERVAL '0.005' SECOND)
        |  ) as T
        | GROUP BY cnt, TUMBLE(pt1, INTERVAL '0.01' SECOND)
      """.stripMargin
    val resolvedSchema = tEnv.sqlQuery(sql).getResolvedSchema
    // due to the non-deterministic of proctime() function, the result isn't checked here
    assertThat(resolvedSchema.toString)
      .isEqualTo(
        s"""
           |(
           |  `key` BIGINT NOT NULL,
           |  `window_start` TIMESTAMP(3) NOT NULL,
           |  `window_start0` TIMESTAMP(3) NOT NULL,
           |  `window_proctime` TIMESTAMP_LTZ(3) NOT NULL *PROCTIME*,
           |  `v1` TIMESTAMP(3) NOT NULL,
           |  `v2` TIMESTAMP(3) NOT NULL
           |)
         """.stripMargin.trim
      )
  }

  @TestTemplate
  def testEventTimeSessionWindow(): Unit = {
    if (useTimestampLtz) {
      return
    }
    // To verify the "merge" functionality, we create this test with the following characteristics:
    // 1. set the Parallelism to 1, and have the test data out of order
    // 2. create a waterMark with 10ms offset to delay the window emission by 10ms
    val sessionData = List(
      (1L, 1, "Hello", "a"),
      (2L, 2, "Hello", "b"),
      (8L, 8, "Hello", "a"),
      (9L, 9, "Hello World", "b"),
      (4L, 4, "Hello", "c"),
      (16L, 16, "Hello", "d"))

    val stream = failingDataSource(sessionData)
      .assignTimestampsAndWatermarks(
        new TimestampAndWatermarkWithOffset[(Long, Int, String, String)](10L))
    val table = stream.toTable(tEnv, 'rowtime.rowtime, 'int, 'string, 'name)
    tEnv.createTemporaryView("T1", table)

    val sql =
      """
        |SELECT
        |  `string`,
        |  SESSION_START(rowtime, INTERVAL '0.005' SECOND),
        |  SESSION_ROWTIME(rowtime, INTERVAL '0.005' SECOND),
        |  COUNT(1),
        |  SUM(1),
        |  COUNT(`int`),
        |  SUM(`int`),
        |  COUNT(DISTINCT name)
        |FROM T1
        |GROUP BY `string`, SESSION(rowtime, INTERVAL '0.005' SECOND)
      """.stripMargin

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()

    val expected = Seq(
      "Hello World,1970-01-01T00:00:00.009,1970-01-01T00:00:00.013,1,1,1,9,1",
      "Hello,1970-01-01T00:00:00.016,1970-01-01T00:00:00.020,1,1,1,16,1",
      "Hello,1970-01-01T00:00:00.001,1970-01-01T00:00:00.012,4,4,4,15,3"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testEventTimeTumblingWindowWithAllowLateness(): Unit = {
    if (useTimestampLtz) {
      return
    }
    // wait 10 millisecond for late elements
    tEnv.getConfig.set(TABLE_EXEC_EMIT_ALLOW_LATENESS, Duration.ofMillis(10))
    // emit result without delay after watermark
    withLateFireDelay(tEnv.getConfig, Duration.ofMillis(0))
    val data = List(
      (1L, 1, "Hi"),
      (2L, 2, "Hello"),
      (4L, 2, "Hello"),
      (8L, 3, "Hello world"),
      (4L, 3, "Hello"), // out of order
      (16L, 3, "Hello world"),
      (9L, 4, "Hello world"), // out of order
      (3L, 1, "Hi")
    ) // too late, drop

    val stream = failingDataSource(data)
      .assignTimestampsAndWatermarks(new TimestampAndWatermarkWithOffset[(Long, Int, String)](0L))
    val table = stream.toTable(tEnv, 'long, 'int, 'string, 'rowtime.rowtime)
    tEnv.createTemporaryView("T1", table)
    tEnv.createTemporarySystemFunction("weightAvgFun", classOf[WeightedAvg])

    val sql =
      """
        |SELECT
        |  `string`,
        |  TUMBLE_START(rowtime, INTERVAL '0.005' SECOND) as w_start,
        |  TUMBLE_END(rowtime, INTERVAL '0.005' SECOND),
        |  COUNT(DISTINCT `long`),
        |  COUNT(`int`),
        |  CAST(AVG(`int`) AS INT),
        |  weightAvgFun(`long`, `int`),
        |  MIN(`int`),
        |  MAX(`int`),
        |  SUM(`int`)
        |FROM T1
        |GROUP BY `string`, TUMBLE(rowtime, INTERVAL '0.005' SECOND)
      """.stripMargin

    val result = tEnv.sqlQuery(sql)

    val fieldTypes: List[DataType] = List(
      DataTypes.STRING,
      DataTypes.TIMESTAMP(3).bridgedTo(classOf[LocalDateTime]),
      DataTypes.TIMESTAMP(3).bridgedTo(classOf[LocalDateTime]),
      DataTypes.BIGINT,
      DataTypes.BIGINT,
      DataTypes.INT,
      DataTypes.BIGINT,
      DataTypes.INT,
      DataTypes.INT,
      DataTypes.INT
    )
    val fieldNames = fieldTypes.indices.map("f" + _).toList

    TestSinkUtil.addValuesSink(
      tEnv,
      "MySink",
      fieldNames,
      fieldTypes,
      ChangelogMode.upsert(),
      fieldNames.take(2)
    )
    result.executeInsert("MySink").await()

    val expected = Seq(
      "+I[Hi, 1970-01-01T00:00, 1970-01-01T00:00:00.005, 1, 1, 1, 1, 1, 1, 1]",
      "+I[Hello, 1970-01-01T00:00, 1970-01-01T00:00:00.005, 2, 3, 2, 3, 2, 3, 7]",
      "+I[Hello world, 1970-01-01T00:00:00.015, 1970-01-01T00:00:00.020, 1, 1, 3, 16, 3, 3, 3]",
      "+I[Hello world, 1970-01-01T00:00:00.005, 1970-01-01T00:00:00.010, 2, 2, 3, 8, 3, 4, 7]"
    )
    assertThat(
      TestValuesTableFactory
        .getResultsAsStrings("MySink")
        .sorted
        .mkString("\n"))
      .isEqualTo(expected.sorted.mkString("\n"))
  }

  @TestTemplate
  def testWindowAggregateOnUpsertSource(): Unit = {
    env.setParallelism(1)
    val upsertSourceDataId = registerData(upsertSourceCurrencyData)
    tEnv.executeSql(s"""
                       |CREATE TABLE upsert_currency (
                       |  currency STRING,
                       |  currency_no STRING,
                       |  rate  BIGINT,
                       |  currency_time TIMESTAMP(3),
                       |  WATERMARK FOR currency_time AS currency_time - interval '5' SECOND,
                       |  PRIMARY KEY(currency) NOT ENFORCED
                       |) WITH (
                       |  'connector' = 'values',
                       |  'changelog-mode' = 'UA,D',
                       |  'data-id' = '$upsertSourceDataId'
                       |)
                       |""".stripMargin)
    val sql =
      """
        |SELECT
        |currency,
        |COUNT(1) AS cnt,
        |MAX(rate),
        |TUMBLE_START(currency_time, INTERVAL '5' SECOND) as w_start,
        |TUMBLE_END(currency_time, INTERVAL '5' SECOND) as w_end
        |FROM upsert_currency
        |GROUP BY currency, TUMBLE(currency_time, INTERVAL '5' SECOND)
        |""".stripMargin
    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()
    val expected = Seq(
      "US Dollar,1,102,1970-01-01T00:00,1970-01-01T00:00:05",
      "Yen,1,1,1970-01-01T00:00,1970-01-01T00:00:05",
      "Euro,1,118,1970-01-01T00:00:15,1970-01-01T00:00:20",
      "RMB,1,702,1970-01-01T00:00,1970-01-01T00:00:05"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testWindowAggregateOnUpsertSourceWithAllowLateness(): Unit = {
    // wait 15 second for late elements
    tEnv.getConfig.set(TABLE_EXEC_EMIT_ALLOW_LATENESS, Duration.ofSeconds(15))
    // emit result without delay after watermark
    withLateFireDelay(tEnv.getConfig, Duration.ofMillis(0))
    val upsertSourceDataId = registerData(upsertSourceCurrencyData)
    tEnv.executeSql(s"""
                       |CREATE TABLE upsert_currency (
                       |  currency STRING,
                       |  currency_no STRING,
                       |  rate  BIGINT,
                       |  currency_time TIMESTAMP(3),
                       |  WATERMARK FOR currency_time AS currency_time - interval '5' SECOND,
                       |  PRIMARY KEY(currency) NOT ENFORCED
                       |) WITH (
                       |  'connector' = 'values',
                       |  'changelog-mode' = 'UA,D',
                       |  'data-id' = '$upsertSourceDataId'
                       |)
                       |""".stripMargin)
    val sql =
      """
        |SELECT
        |currency,
        |COUNT(1) AS cnt,
        |MAX(rate),
        |TUMBLE_START(currency_time, INTERVAL '5' SECOND) as w_start,
        |TUMBLE_END(currency_time, INTERVAL '5' SECOND) as w_end
        |FROM upsert_currency
        |GROUP BY currency, TUMBLE(currency_time, INTERVAL '5' SECOND)
        |""".stripMargin
    val table = tEnv.sqlQuery(sql)
    val schema = table.getSchema
    TestSinkUtil.addValuesSink(
      tEnv,
      "MySink1",
      util.Arrays.asList(schema.getFieldNames: _*).toList,
      schema.getFieldDataTypes.map(_.nullable()).toList,
      ChangelogMode.all()
    )
    table.executeInsert("MySink1").await()

    val expected = Seq(
      "+I[US Dollar, 1, 104, 1970-01-01T00:00, 1970-01-01T00:00:05]",
      "+I[Yen, 1, 1, 1970-01-01T00:00, 1970-01-01T00:00:05]",
      "+I[Euro, 1, 118, 1970-01-01T00:00:15, 1970-01-01T00:00:20]"
    )
    assertThat(
      TestValuesTableFactory
        .getResultsAsStrings("MySink1")
        .sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testWindowAggregateOnUpsertSourcePushdownWatermark(): Unit = {
    env.setParallelism(1)
    val upsertSourceDataId = registerData(upsertSourceCurrencyData)
    tEnv.executeSql(s"""
                       |CREATE TABLE upsert_currency (
                       |  currency STRING,
                       |  currency_no STRING,
                       |  rate  BIGINT,
                       |  currency_time TIMESTAMP(3),
                       |  WATERMARK FOR currency_time AS currency_time - interval '5' SECOND,
                       |  PRIMARY KEY(currency) NOT ENFORCED
                       |) WITH (
                       |  'connector' = 'values',
                       |  'changelog-mode' = 'UA,D',
                       |  'data-id' = '$upsertSourceDataId'
                       |)
                       |""".stripMargin)
    val sql =
      """
        |SELECT
        |TUMBLE_START(currency_time, INTERVAL '5' SECOND) as w_start,
        |TUMBLE_END(currency_time, INTERVAL '5' SECOND) as w_end,
        |MAX(rate) AS max_rate
        |FROM upsert_currency
        |GROUP BY TUMBLE(currency_time, INTERVAL '5' SECOND)
        |""".stripMargin
    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()
    val expected =
      Seq("1970-01-01T00:00,1970-01-01T00:00:05,702", "1970-01-01T00:00:15,1970-01-01T00:00:20,118")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testWindowAggregateOnRetractStream(): Unit = {
    val sql =
      """
        |SELECT
        |`string`,
        |TUMBLE_START(rowtime, INTERVAL '0.005' SECOND) as w_start,
        |TUMBLE_END(rowtime, INTERVAL '0.005' SECOND) as w_end,
        |COUNT(1) AS cnt
        |FROM
        | (
        | SELECT `string`, rowtime
        | FROM (
        |  SELECT *,
        |  ROW_NUMBER() OVER (PARTITION BY `string` ORDER BY rowtime DESC) as rowNum
        |   FROM testTable
        | )
        | WHERE rowNum = 1
        |)
        |GROUP BY `string`, TUMBLE(rowtime, INTERVAL '0.005' SECOND)
        |""".stripMargin
    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()
    val expected = Seq(
      "Hi,1970-01-01T00:00,1970-01-01T00:00:00.005,1",
      "Hallo,1970-01-01T00:00,1970-01-01T00:00:00.005,1",
      "Hello,1970-01-01T00:00:00.005,1970-01-01T00:00:00.010,1",
      "Hello world,1970-01-01T00:00:00.015,1970-01-01T00:00:00.020,1",
      "null,1970-01-01T00:00:00.030,1970-01-01T00:00:00.035,1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctAggWithMergeOnEventTimeSessionGroupWindow(): Unit = {
    if (useTimestampLtz) {
      return
    }
    // create a watermark with 10ms offset to delay the window emission by 10ms to verify merge
    val sessionWindowTestData = List(
      (1L, 2, "Hello"), // (1, Hello)       - window
      (2L, 2, "Hello"), // (1, Hello)       - window, deduped
      (8L, 2, "Hello"), // (2, Hello)       - window, deduped during merge
      (10L, 3, "Hello"), // (2, Hello)       - window, forwarded during merge
      (9L, 9, "Hello World"), // (1, Hello World) - window
      (4L, 1, "Hello"), // (1, Hello)       - window, triggering merge
      (16L, 16, "Hello")
    ) // (3, Hello)       - window (not merged)

    val stream = failingDataSource(sessionWindowTestData)
      .assignTimestampsAndWatermarks(new TimestampAndWatermarkWithOffset[(Long, Int, String)](10L))
    val table = stream.toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)
    tEnv.createTemporaryView("MyTable", table)

    val sqlQuery =
      """
        |SELECT c,
        |   COUNT(DISTINCT b),
        |   SESSION_END(rowtime, INTERVAL '0.005' SECOND)
        |FROM MyTable
        |GROUP BY c, SESSION(rowtime, INTERVAL '0.005' SECOND)
      """.stripMargin
    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = Seq(
      "Hello World,1,1970-01-01T00:00:00.014", // window starts at [9L] till {14L}
      "Hello,1,1970-01-01T00:00:00.021", // window starts at [16L] till {21L}, not merged
      "Hello,3,1970-01-01T00:00:00.015" // window starts at [1L,2L],
      //   merged with [8L,10L], by [4L], till {15L}
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  private def withLateFireDelay(tableConfig: TableConfig, interval: Duration): Unit = {
    val intervalInMillis = interval.toMillis
    val lateFireDelay: Duration =
      tableConfig.getOptional(TABLE_EXEC_EMIT_LATE_FIRE_DELAY).orElse(null)
    if (lateFireDelay != null && (lateFireDelay.toMillis != intervalInMillis)) {
      // lateFireInterval of the two query config is not equal and not the default
      throw new RuntimeException(
        "Currently not support different lateFireInterval configs in one job")
    }
    tableConfig.set(TABLE_EXEC_EMIT_LATE_FIRE_ENABLED, Boolean.box(true))
    tableConfig.set(TABLE_EXEC_EMIT_LATE_FIRE_DELAY, Duration.ofMillis(intervalInMillis))
  }

  private def localDateTime(epochSecond: Long): LocalDateTime = {
    LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC)
  }
}

object GroupWindowITCase {

  @Parameters(name = "StateBackend={0}, UseTimestampLtz = {1}")
  def parameters(): util.Collection[Array[java.lang.Object]] = {
    Seq[Array[AnyRef]](
      Array(HEAP_BACKEND, java.lang.Boolean.TRUE),
      Array(HEAP_BACKEND, java.lang.Boolean.FALSE),
      Array(ROCKSDB_BACKEND, java.lang.Boolean.TRUE),
      Array(ROCKSDB_BACKEND, java.lang.Boolean.FALSE)
    )
  }
}
