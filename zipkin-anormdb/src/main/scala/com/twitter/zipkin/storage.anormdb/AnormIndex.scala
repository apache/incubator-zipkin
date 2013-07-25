/*
 * Copyright 2013 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.zipkin.storage.anormdb

import com.twitter.zipkin.common.Span
import com.twitter.zipkin.storage.{Index, IndexedTraceId, TraceIdDuration}
import com.twitter.util.Future
import com.twitter.zipkin.util.Util
import java.nio.ByteBuffer
import anorm._
import anorm.SqlParser._
import java.sql.Connection

/**
 * Retrieve and store trace and span information.
 *
 * This is one of two places where Zipkin interacts directly with the database,
 * the other one being AnormStorage.
 *
 * We're ignoring TTL for now since unlike Cassandra and Redis, SQL databases
 * don't have that built in and it shouldn't be a big deal for most sites.
 *
 * The index methods are stubs since SQL databases don't use NoSQL-style
 * indexing.
 */
case class AnormIndex(db: DB, openCon: Option[Connection] = None) extends Index {
  // Database connection object
  private implicit val conn = openCon match {
    case None => db.getConnection()
    case Some(con) => con
  }

  /**
   * Close the index
   */
  def close() { conn.close() }

  /**
   * Get the trace ids for this particular service and if provided, span name.
   * Only return maximum of limit trace ids from before the endTs.
   */
  def getTraceIdsByName(serviceName: String, spanName: Option[String],
                        endTs: Long, limit: Int): Future[Seq[IndexedTraceId]] = {
    val result:List[(Long, Long)] = SQL(
      """SELECT trace_id, MAX(created_ts)
        |FROM zipkin_annotations
        |WHERE service_name = {service_name}
        |  AND (span_name = {span_name} OR span_name = '')
        |  AND timestamp < {end_ts}
        |GROUP BY trace_id
        |ORDER BY timestamp DESC
        |LIMIT {limit}
      """.stripMargin)
      .on("service_name" -> serviceName)
      .on("span_name" -> (if (spanName.isEmpty) "" else spanName.get))
      .on("end_ts" -> endTs)
      .on("limit" -> limit)
      .as((long("trace_id") ~ long("end_ts") map flatten) *)
    Future(result map { case (tId, ts) =>
      IndexedTraceId(traceId = tId, timestamp = ts)
    })
  }

  /**
   * Get the trace ids for this annotation before endTs. If value is also
   * passed we expect both the annotation key and value to be present in index
   * for a match to be returned.
   * Only return maximum of limit trace ids from before the endTs.
   */
  def getTraceIdsByAnnotation(serviceName: String, annotation: String, value: Option[ByteBuffer],
                              endTs: Long, limit: Int): Future[Seq[IndexedTraceId]] = {
    val result:List[(Long, Long)] = value match {
      // Binary annotations
      case Some(bytes) => {
        SQL(
          """SELECT zba.trace_id, s.created_ts
            |FROM zipkin_binary_annotations AS zba
            |LEFT JOIN zipkin_spans AS s
            |  ON zba.trace_id = s.trace_id
            |WHERE zba.service_name = {service_name}
            |  AND zba.key = {annotation}
            |  AND zba.value = {value}
            |  AND s.created_ts < {end_ts}
            |GROUP BY zba.trace_id
            |ORDER BY s.created_ts DESC
            |LIMIT {limit}
          """.stripMargin)
          .on("service_name" -> serviceName)
          .on("annotation" -> annotation)
          .on("value" -> Util.getArrayFromBuffer(bytes))
          .on("end_ts" -> endTs)
          .on("limit" -> limit)
          .as((long("trace_id") ~ long("created_ts") map flatten) *)
      }
      // Normal annotations
      case None => {
        SQL(
          """SELECT trace_id, MAX(timestamp)
            |FROM zipkin_annotations
            |WHERE service_name = {service_name}
            |  AND value = {annotation}
            |  AND timestamp < {end_ts}
            |ORDER BY timestamp DESC
            |LIMIT {limit}
          """.stripMargin)
          .on("service_name" -> serviceName)
          .on("annotation" -> annotation)
          .on("end_ts" -> endTs)
          .on("limit" -> limit)
          .as((long("trace_id") ~ long("end_ts") map flatten) *)
      }
    }
    Future(result map { case (tId, ts) =>
      IndexedTraceId(traceId = tId, timestamp = ts)
    })
  }

  /**
   * Fetch the duration or an estimate thereof from the traces.
   *
   * Duration returned in microseconds.
   */
  def getTracesDuration(traceIds: Seq[Long]): Future[Seq[TraceIdDuration]] = {
    val result:List[(Long, Option[Long], Long)] = SQL(
      """SELECT trace_id, duration, created_ts
        |FROM zipkin_spans
        |WHERE trace_id IN (%s)
        |GROUP BY trace_id
        |ORDER BY created_ts DESC
      """.stripMargin.format(traceIds.mkString(",")))
      .as((long("trace_id") ~ get[Option[Long]]("duration") ~ long("created_ts") map flatten) *)
    Future(result map { case (traceId, duration, startTs) =>
      // trace ID, duration, start TS
      TraceIdDuration(traceId, duration.getOrElse(0), startTs)
    })
  }

  /**
   * Get all the service names.
   */
  def getServiceNames: Future[Set[String]] = {
    Future(SQL(
      """SELECT service_name
        |FROM zipkin_annotations
        |GROUP BY service_name
        |ORDER BY service_name ASC
      """.stripMargin)
      .as(str("service") *).toSet)
  }

  /**
   * Get all the span names for a particular service.
   */
  def getSpanNames(service: String): Future[Set[String]] = {
    Future(SQL(
      """SELECT span_name
        |FROM zipkin_annotations
        |WHERE service_name = {service}
        |GROUP BY span_name
        |ORDER BY span_name ASC
      """.stripMargin)
      .on("service" -> service)
      .as(str("service") *)
      .toSet
    )
  }

  /**
   * Index a trace id on the service and name of a specific Span
   */
  def indexTraceIdByServiceAndName(span: Span) : Future[Unit] = {
    Future.Unit
  }

  /**
   * Index the span by the annotations attached
   */
  def indexSpanByAnnotations(span: Span) : Future[Unit] = {
    Future.Unit
  }

  /**
   * Store the service name so that we can easily find out which services have
   * been called.
   */
  def indexServiceName(span: Span) : Future[Unit] = {
    Future.Unit
  }

  /**
   * Index the span name on the service name.
   *
   * This is so we can get a list of span names when given a service name.
   * Mainly for UI purposes
   */
  def indexSpanNameByService(span: Span) : Future[Unit] = {
    Future.Unit
  }

  /**
   * Index a span's duration. This is so we can look up the trace duration.
   */
  def indexSpanDuration(span: Span): Future[Void] = {
    Future.Void
  }
}
