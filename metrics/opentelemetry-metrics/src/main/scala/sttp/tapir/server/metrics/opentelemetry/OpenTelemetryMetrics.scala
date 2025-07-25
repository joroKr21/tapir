package sttp.tapir.server.metrics.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.{DoubleHistogram, LongCounter, LongUpDownCounter, Meter}
import io.opentelemetry.semconv.{ErrorAttributes, HttpAttributes, UrlAttributes}
import sttp.tapir.AnyEndpoint
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.metrics.{EndpointMetric, Metric, MetricLabels}
import sttp.tapir.server.model.ServerResponse

import java.time.{Duration, Instant}

import OpenTelemetryMetrics._

case class OpenTelemetryMetrics[F[_]](meter: Meter, metrics: List[Metric[F, _]]) {

  /** Registers a `request_active{path, method}` up-down-counter (assuming default labels). */
  def addRequestsActive(labels: MetricLabels = OpenTelemetryAttributes): OpenTelemetryMetrics[F] =
    copy(metrics = metrics :+ requestActive(meter, labels))

  /** Registers a `request_total{path, method, status}` counter (assuming default labels). */
  def addRequestsTotal(labels: MetricLabels = OpenTelemetryAttributes): OpenTelemetryMetrics[F] =
    copy(metrics = metrics :+ requestTotal(meter, labels))

  /** Registers a `request_duration_seconds{path, method, status, phase}` histogram (assuming default labels). */
  def addRequestsDuration(labels: MetricLabels = OpenTelemetryAttributes): OpenTelemetryMetrics[F] =
    copy(metrics = metrics :+ requestDuration(meter, labels))

  /** Registers a custom metric. */
  def addCustom(m: Metric[F, _]): OpenTelemetryMetrics[F] = copy(metrics = metrics :+ m)

  /** The interceptor which can be added to a server's options, to enable metrics collection. */
  def metricsInterceptor(ignoreEndpoints: Seq[AnyEndpoint] = Seq.empty): MetricsRequestInterceptor[F] =
    new MetricsRequestInterceptor[F](metrics, ignoreEndpoints)
}

object OpenTelemetryMetrics {

  /** Default labels for OpenTelemetry-compliant metrics, as recommended here:
    * https://opentelemetry.io/docs/specs/semconv/http/http-metrics/#http-server
    *
    *   - `http.request.method` - HTTP request method (e.g., GET, POST).
    *   - `url.scheme` - the scheme of the request URL (e.g., http, https).
    *   - `http.route` - the request path or route template.
    *   - `http.response.status_code` - HTTP response status code (200, 404, etc.).
    */
  lazy val OpenTelemetryAttributes: MetricLabels = MetricLabels(
    forRequest = List(
      HttpAttributes.HTTP_REQUEST_METHOD.getKey -> { case (_, req) => req.method.method },
      UrlAttributes.URL_SCHEME.getKey -> { case (_, req) => req.uri.scheme.getOrElse("unknown") },
      HttpAttributes.HTTP_ROUTE.getKey -> { case (ep, _) => ep.showPathTemplate(showQueryParam = None) }
    ),
    forResponse = List(
      HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey -> {
        case Right(r) => Some(r.code.code.toString)
        // Default to 500 for exceptions
        case Left(_) => Some("500")
      },
      ErrorAttributes.ERROR_TYPE.getKey -> {
        case Left(ex) => Some(ex.getClass.getName) // Exception class name for errors
        case Right(_) => None
      }
    )
  )

  def apply[F[_]](meter: Meter): OpenTelemetryMetrics[F] = apply(meter, Nil)
  def apply[F[_]](otel: OpenTelemetry): OpenTelemetryMetrics[F] = apply(defaultMeter(otel), Nil)
  def apply[F[_]](otel: OpenTelemetry, metrics: List[Metric[F, _]]): OpenTelemetryMetrics[F] = apply(defaultMeter(otel), metrics)

  /** Using the default labels, registers the following metrics:
    *
    *   - `request_active{path, method}` (up-down-counter)
    *   - `request_total{path, method, status}` (counter)
    *   - `request_duration{path, method, status, phase}` (histogram)
    *
    * Status is by default the status code class (1xx, 2xx, etc.), and phase can be either `headers` or `body` - request duration is
    * measured separately up to the point where the headers are determined, and then once again when the whole response body is complete.
    */
  def default[F[_]](otel: OpenTelemetry): OpenTelemetryMetrics[F] =
    default(defaultMeter(otel), OpenTelemetryAttributes)

  /** Registers default metrics (see other variants) using custom labels. */
  def default[F[_]](otel: OpenTelemetry, labels: MetricLabels): OpenTelemetryMetrics[F] = default(defaultMeter(otel), labels)

  /** Using the default labels, registers the following metrics:
    *
    *   - `request_active{path, method}` (up-down-counter)
    *   - `request_total{path, method, status}` (counter)
    *   - `request_duration{path, method, status, phase}` (histogram)
    *
    * Status is by default the status code class (1xx, 2xx, etc.), and phase can be either `headers` or `body` - request duration is
    * measured separately up to the point where the headers are determined, and then once again when the whole response body is complete.
    */
  def default[F[_]](meter: Meter): OpenTelemetryMetrics[F] = default(meter, OpenTelemetryAttributes)

  /** Registers default metrics (see other variants) using custom labels. */
  def default[F[_]](meter: Meter, labels: MetricLabels = OpenTelemetryAttributes): OpenTelemetryMetrics[F] =
    OpenTelemetryMetrics(
      meter,
      List[Metric[F, _]](
        requestActive(meter, labels),
        requestTotal(meter, labels),
        requestDuration(meter, labels)
      )
    )

  def requestActive[F[_]](meter: Meter, labels: MetricLabels): Metric[F, LongUpDownCounter] =
    Metric[F, LongUpDownCounter](
      meter
        .upDownCounterBuilder("http.server.active_requests")
        .setDescription("Active HTTP requests")
        .setUnit("1")
        .build(),
      onRequest = (req, counter, m) => {
        m.unit {
          EndpointMetric()
            .onEndpointRequest { ep => m.eval(counter.add(1, asOpenTelemetryAttributes(labels, ep, req))) }
            .onResponseBody { (ep, _) => m.eval(counter.add(-1, asOpenTelemetryAttributes(labels, ep, req))) }
            .onException { (ep, _) => m.eval(counter.add(-1, asOpenTelemetryAttributes(labels, ep, req))) }
        }
      }
    )

  def requestTotal[F[_]](meter: Meter, labels: MetricLabels): Metric[F, LongCounter] =
    Metric[F, LongCounter](
      meter
        .counterBuilder("http.server.request.total")
        .setDescription("Total HTTP requests")
        .setUnit("1")
        .build(),
      onRequest = (req, counter, m) => {
        m.unit {
          EndpointMetric()
            .onResponseBody { (ep, res) =>
              m.eval {
                val otLabels =
                  merge(asOpenTelemetryAttributes(labels, ep, req), asOpenTelemetryAttributes(labels, Right(res), None))

                counter.add(1, otLabels)
              }
            }
            .onException { (ep, ex) =>
              m.eval {
                val otLabels =
                  merge(asOpenTelemetryAttributes(labels, ep, req), asOpenTelemetryAttributes(labels, Left(ex), None))
                counter.add(1, otLabels)
              }
            }
        }
      }
    )

  def requestDuration[F[_]](meter: Meter, labels: MetricLabels): Metric[F, DoubleHistogram] =
    Metric[F, DoubleHistogram](
      meter
        .histogramBuilder("http.server.request.duration")
        .setDescription("Duration of HTTP requests")
        .setUnit("ms")
        .build(),
      onRequest = (req, recorder, m) =>
        m.eval {
          val requestStart = Instant.now()
          def duration = Duration.between(requestStart, Instant.now()).toMillis.toDouble
          EndpointMetric()
            .onResponseHeaders { (ep, res) =>
              m.eval {
                val otLabels =
                  merge(
                    asOpenTelemetryAttributes(labels, ep, req),
                    asOpenTelemetryAttributes(labels, Right(res), Some(labels.forResponsePhase.headersValue))
                  )
                recorder.record(duration, otLabels)
              }
            }
            .onResponseBody { (ep, res) =>
              m.eval {
                val otLabels =
                  merge(
                    asOpenTelemetryAttributes(labels, ep, req),
                    asOpenTelemetryAttributes(labels, Right(res), Some(labels.forResponsePhase.bodyValue))
                  )
                recorder.record(duration, otLabels)
              }
            }
            .onException { (ep, ex) =>
              m.eval {
                val otLabels =
                  merge(asOpenTelemetryAttributes(labels, ep, req), asOpenTelemetryAttributes(labels, Left(ex), None))
                recorder.record(duration, otLabels)
              }
            }
        }
    )

  private def defaultMeter(otel: OpenTelemetry): Meter = otel.meterBuilder("tapir").setInstrumentationVersion("1.0.0").build()

  private def asOpenTelemetryAttributes(l: MetricLabels, ep: AnyEndpoint, req: ServerRequest): Attributes =
    l.forRequest.foldLeft(Attributes.builder())((b, label) => { b.put(label._1, label._2(ep, req)) }).build()

  private def asOpenTelemetryAttributes(l: MetricLabels, res: Either[Throwable, ServerResponse[_]], phase: Option[String]): Attributes = {
    val builder = Attributes.builder()
    l.forResponse.foreach { case (key, valueFn) =>
      valueFn(res).foreach(value => builder.put(key, value))
    }
    phase.foreach(v => builder.put(l.forResponsePhase.name, v))
    builder.build()
  }

  private def merge(a1: Attributes, a2: Attributes): Attributes = a1.toBuilder.putAll(a2).build()
}
