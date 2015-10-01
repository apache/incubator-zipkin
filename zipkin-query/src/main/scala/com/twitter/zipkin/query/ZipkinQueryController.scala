package com.twitter.zipkin.query

import com.twitter.finagle.httpx.Request
import com.twitter.finagle.tracing.SpanId
import com.twitter.finatra.annotations.Flag
import com.twitter.finatra.http.Controller
import com.twitter.finatra.http.response.ResponseBuilder
import com.twitter.finatra.request.{QueryParam, RouteParam}
import com.twitter.util.Future
import com.twitter.zipkin.json.JsonSpan
import com.twitter.zipkin.query.adjusters.TimeSkewAdjuster
import com.twitter.zipkin.query.constants._
import com.twitter.zipkin.storage.{DependencyStore, SpanStore}
import javax.inject.Inject


class ZipkinQueryController @Inject()(spanStore: SpanStore,
                                      dependencyStore: DependencyStore,
                                      queryExtractor: QueryExtractor,
                                      traceIds: QueryTraceIds,
                                      response: ResponseBuilder,
                                      @Flag("zipkin.queryService.servicesMaxAge") servicesMaxAge: Int) extends Controller {

  private[this] val EmptyTraces = Future.value(Seq.empty[Seq[JsonSpan]])

  get("/api/v1/spans") { request: GetSpanNamesRequest =>
    spanStore.getSpanNames(request.serviceName)
  }

  get("/api/v1/services") { request: Request =>
    spanStore.getAllServiceNames() map { serviceNames =>
      if (serviceNames.size <= MaxServicesWithoutCaching) {
        response.ok(serviceNames);
      } else {
        response.ok(serviceNames).header("Cache-Control", s"max-age=${servicesMaxAge}, must-revalidate")
      }
    };
  }

  get("/api/v1/traces") { request: Request =>
    queryExtractor(request) match {
      case Some(qr) => traceIds(qr).flatMap(getTraces(_))
      case None => Future.value(response.badRequest)
    }
  }

  get("/api/v1/trace/:id") { request: GetTraceRequest =>
    getTraces(SpanId.fromString(request.id).map(_.toLong).toSeq).map(_.headOption)
  }

  get("/api/v1/dependencies/:from/:to") { request: GetDependenciesRequest =>
    dependencyStore.getDependencies(Some(request.from), Some(request.to)).map(_.links)
  }

  private[this] def getTraces(ids: Seq[Long]): Future[Seq[Seq[JsonSpan]]] = {
    if (ids.isEmpty) return EmptyTraces
    spanStore.getSpansByTraceIds(ids).map { spans =>
      spans.map(Trace(_))
        .map(timeSkewAdjuster.adjust)
        .map(_.spans.map(JsonSpan))
    }
  }

  private[this] val timeSkewAdjuster = new TimeSkewAdjuster()
}

case class GetSpanNamesRequest(@QueryParam serviceName: String)

case class GetDependenciesRequest(@RouteParam from: Long, @RouteParam to: Long)

case class GetTraceRequest(@RouteParam id: String)
