/*
 * Copyright 2012 Twitter Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.twitter.zipkin.query

import com.twitter.conversions.time._
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Trace => FTrace}
import com.twitter.logging.Logger
import com.twitter.ostrich.admin.Service
import com.twitter.util.Future
import com.twitter.zipkin.adapter.ThriftQueryAdapter
import com.twitter.zipkin.gen
import com.twitter.zipkin.query.adjusters.Adjuster
import com.twitter.zipkin.storage.{Aggregates, TraceIdDuration, Index, Storage}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.thrift.TException
import scala.collection.Set

/**
 * Able to respond to users queries regarding the traces. Usually does so
 * by lookup the information in the index and then fetch the required trace data
 * from the storage.
 */
class QueryService(storage: Storage, index: Index, aggregates: Aggregates, adjusterMap: Map[gen.Adjust, Adjuster],
                   statsReceiver: StatsReceiver = NullStatsReceiver) extends gen.ZipkinQuery.FutureIface with Service {
  private val log = Logger.get
  private val running = new AtomicBoolean(false)

  private val stats = statsReceiver.scope("QueryService")
  private val methodStats = stats.scope("methods")
  private val errorStats = stats.scope("errors")
  private val timingStats = stats.scope("timing")

  // how to sort the trace summaries
  private val OrderByDurationDesc = {
    (a: TraceIdDuration, b: TraceIdDuration) => a.duration > b.duration
  }
  private val OrderByDurationAsc = {
    (a: TraceIdDuration, b: TraceIdDuration) => a.duration < b.duration
  }
  private val OrderByTimestampDesc = {
    (a: TraceIdDuration, b: TraceIdDuration) => a.startTimestamp > b.startTimestamp
  }
  private val OrderByTimestampAsc = {
    (a: TraceIdDuration, b: TraceIdDuration) => a.startTimestamp < b.startTimestamp
  }

  // this is how many trace durations we fetch in one request
  // TODO config
  var traceDurationFetchBatchSize = 500

  def start() {
    running.set(true)
  }

  def shutdown() {
    running.set(false)
    storage.close
  }

  def getTraceIdsBySpanName(serviceName: String, spanName: String, endTs: Long,
                        limit: Int, order: gen.Order): Future[Seq[Long]] = {
    val method = "getTraceIdsBySpanName"
    log.debug("%s. serviceName: %s spanName: %s endTs: %s limit: %s order: %s".format(method, serviceName, spanName,
      endTs, limit, order))
    call(method) {
      if (serviceName == null || "".equals(serviceName)) {
        errorStats.counter("%s_no_service".format(method)).incr()
        return Future.exception(gen.QueryException("No service name provided"))
      }

      // do we have a valid span name to query indexes by?
      val span = convertToOption(spanName)

      FTrace.recordBinary("serviceName", serviceName)
      FTrace.recordBinary("spanName", spanName)
      FTrace.recordBinary("endTs", endTs)
      FTrace.recordBinary("limit", limit)
      FTrace.recordBinary("order", order)

      val traceIds = index.getTraceIdsByName(serviceName, span, endTs, limit)
      sortTraceIds(traceIds, limit, order)
    }
  }

  def getTraceIdsByServiceName(serviceName: String, endTs: Long,
                               limit: Int, order: gen.Order): Future[Seq[Long]] = {
    val method = "getTraceIdsByServiceName"
    log.debug("%s. serviceName: %s endTs: %s limit: %s order: %s".format(method, serviceName, endTs, limit, order))
    call(method) {
      if (serviceName == null || "".equals(serviceName)) {
        errorStats.counter("%s_no_service".format(method)).incr()
        return Future.exception(gen.QueryException("No service name provided"))
      }

      FTrace.recordBinary("serviceName", serviceName)
      FTrace.recordBinary("endTs", endTs)
      FTrace.recordBinary("limit", limit)
      FTrace.recordBinary("order", order)

      val traceIds = index.getTraceIdsByName(serviceName, None, endTs, limit)
      sortTraceIds(traceIds, limit, order)
    }
  }


  def getTraceIdsByAnnotation(serviceName: String, annotation: String, value: ByteBuffer, endTs: Long,
                              limit: Int, order: gen.Order): Future[Seq[Long]] = {
    val method = "getTraceIdsByAnnotation"
    log.debug("%s. serviceName: %s annotation: %s value: %s endTs: %s limit: %s order: %s".format(method, serviceName,
      annotation, value, endTs, limit, order))
    call(method) {
      if (annotation == null || "".equals(annotation)) {
        errorStats.counter("%s_no_annotation").incr()
        return Future.exception(gen.QueryException("No annotation provided"))
      }

      // do we have a valid annotation value to query indexes by?
      val valueOption = convertToOption(value)

      FTrace.recordBinary("serviceName", serviceName)
      FTrace.recordBinary("annotation", annotation)
      FTrace.recordBinary("endTs", endTs)
      FTrace.recordBinary("limit", limit)
      FTrace.recordBinary("order", order)

      val traceIds = index.getTraceIdsByAnnotation(serviceName, annotation, valueOption, endTs, limit)
      sortTraceIds(traceIds, limit, order)
    }
  }

  def getTracesByIds(traceIds: Seq[Long], adjust: Seq[gen.Adjust]): Future[Seq[gen.Trace]] = {
    log.debug("getTracesByIds. " + traceIds + " adjust " + adjust)
    call("getTracesByIds") {
      val adjusters = getAdjusters(adjust)
      FTrace.recordBinary("numIds", traceIds.length)

      storage.getTracesByIds(traceIds).map { traces =>
        traces.map { trace =>
          ThriftQueryAdapter(adjusters.foldLeft(trace)((t, adjuster) => adjuster.adjust(t)))
        }
      }
    }
  }

  def getTraceTimelinesByIds(traceIds: Seq[Long],
                             adjust: Seq[gen.Adjust]): Future[Seq[gen.TraceTimeline]] = {
    log.debug("getTraceTimelinesByIds. " + traceIds + " adjust " + adjust)
    call("getTraceTimelinesByIds") {
      val adjusters = getAdjusters(adjust)
      FTrace.recordBinary("numIds", traceIds.length)

      storage.getTracesByIds(traceIds).map { traces =>
        traces.flatMap { trace =>
          TraceTimeline(adjusters.foldLeft(trace)((t, adjuster) => adjuster.adjust(t))).map(ThriftQueryAdapter(_))
        }
      }
    }
  }

  def getTraceSummariesByIds(traceIds: Seq[Long],
                             adjust: Seq[gen.Adjust]): Future[Seq[gen.TraceSummary]] = {
    log.debug("getTraceSummariesByIds. traceIds: " + traceIds + " adjust " + adjust)
    call("getTraceSummariesByIds") {
      val adjusters = getAdjusters(adjust)
      FTrace.recordBinary("numIds", traceIds.length)

      storage.getTracesByIds(traceIds.toList).map { traces =>
        traces.flatMap { trace =>
          TraceSummary(adjusters.foldLeft(trace)((t, adjuster) => adjuster.adjust(t))).map(ThriftQueryAdapter(_))
        }
      }
    }
  }

  def getTraceCombosByIds(traceIds: Seq[Long], adjust: Seq[gen.Adjust]): Future[Seq[gen.TraceCombo]] = {
    log.debug("getTraceComboByIds. traceIds: " + traceIds + " adjust " + adjust)
    call("getTraceComboByIds") {
      val adjusters = getAdjusters(adjust)
      FTrace.recordBinary("numIds", traceIds.length)

      storage.getTracesByIds(traceIds).map { traces =>
        traces.map { trace =>
          ThriftQueryAdapter(TraceCombo(adjusters.foldLeft(trace)((t, adjuster) => adjuster.adjust(t))))
        }
      }
    }
  }

  def getDataTimeToLive: Future[Int] = {
    log.debug("getDataTimeToLive")
    call("getDataTimeToLive") {
      Future(storage.getDataTimeToLive)
    }
  }

  def getServiceNames: Future[Set[String]] = {
    log.debug("getServiceNames")
    call("getServiceNames") {
      index.getServiceNames
    }
  }

  def getSpanNames(service: String): Future[Set[String]] = {
    log.debug("getSpanNames")
    call("getSpanNames") {
      index.getSpanNames(service)
    }
  }

  def setTraceTimeToLive(traceId: Long, ttlSeconds: Int): Future[Unit] = {
    log.debug("setTimeToLive: " + traceId + " " + ttlSeconds)
    call("setTraceTimeToLive") {
      storage.setTimeToLive(traceId, ttlSeconds.seconds)
    }
  }

  def getTraceTimeToLive(traceId: Long): Future[Int] = {
    log.debug("getTimeToLive: " + traceId)
    call("getTraceTimeToLive") {
      storage.getTimeToLive(traceId).map(_.inSeconds)
    }
  }

  def getTopAnnotations(serviceName: String): Future[Seq[String]] = {
    log.debug("getTopAnnotations: " + serviceName)
    call("getTopAnnotations") {
      aggregates.getTopAnnotations(serviceName)
    }
  }

  def getTopKeyValueAnnotations(serviceName: String): Future[Seq[String]] = {
    log.debug("getTopKeyValueAnnotations: " + serviceName)
    call("getTopKeyValueAnnotations") {
      aggregates.getTopKeyValueAnnotations(serviceName)
    }
  }

  private def checkIfRunning() = {
    if (!running.get) {
      log.warning("Server not running, throwing exception")
      throw new TException("Server not running")
    }
  }

  private[this] def call[T](name: String)(f: => Future[T]): Future[T] = {
    checkIfRunning()
    methodStats.counter(name).incr()

    timingStats.timeFuture(name) {
      f rescue {
        case e: Exception => {
          log.error(e, "%s failed".format(name))
          errorStats.counter(name).incr()
          Future.exception(gen.QueryException(e.toString))
        }
      }
    }
  }

  /**
   * Convert incoming Thrift order by enum into sort function.
   */
  private def getOrderBy(order: gen.Order) = {
    order match {
      case gen.Order.DurationDesc => OrderByDurationDesc
      case gen.Order.DurationAsc => OrderByDurationAsc
      case gen.Order.TimestampDesc => OrderByTimestampDesc
      case gen.Order.TimestampAsc => OrderByTimestampAsc
    }
  }

  private def getAdjusters(adjusters: Seq[gen.Adjust]): Seq[Adjuster] = {
    adjusters.flatMap { adjusterMap.get(_) }
  }

  /**
   * Do we have a valid object to query indexes by?
   */
  private def convertToOption[O](param: O): Option[O] = {
    param match {
      case null => None
      case "" => None
      case s => Some(s)
    }
  }

  /**
   * Given a sequence of traceIds get their durations
   */
  private def getTraceIdDurations(
    traceIds: Future[Seq[Long]]
  ): Future[Seq[TraceIdDuration]] = {
    traceIds.map { t =>
      Future.collect {
        t.grouped(traceDurationFetchBatchSize)
        .toSeq
        .map {index.getTracesDuration(_)}
      }
    }.flatten.map {_.flatten}
  }

  private def sortTraceIds(
    traceIds: Future[Seq[Long]],
    limit: Int,
    order: gen.Order
  ): Future[Seq[Long]] = {

    // No sorting wanted
    if (order == gen.Order.None) {
      traceIds
    } else {
      val durations = getTraceIdDurations(traceIds)
      durations map { d =>
        d.sortWith(getOrderBy(order)).slice(0, limit).map(_.traceId)
      }
    }
  }

}
