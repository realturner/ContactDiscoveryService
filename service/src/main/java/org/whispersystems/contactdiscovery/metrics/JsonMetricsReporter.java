package org.whispersystems.contactdiscovery.metrics;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metered;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class JsonMetricsReporter extends ScheduledReporter {

  private static final Pattern SIMPLE_NAMES = Pattern.compile("[^a-zA-Z0-9_.\\-~]");

  private final Logger logger  = LoggerFactory.getLogger(JsonMetricsReporter.class);
  private final JsonFactory factory = new JsonFactory();

  // metricsHost is the host we send Wavefront-formatted metrics to.
  private final String  metricsHost;
  // metricsPort is the port on the metricsHost we send Wavefront metrics to.
  private final Integer metricsPort;
  // sourceHost is the FQDN of the machine this code is running on (or "localhost") and used as the "source" in Wavefront metrics.
  private final String  sourceHost;

  public JsonMetricsReporter(MetricRegistry registry, String metricsHost, Integer metricsPort,
                             MetricFilter filter, TimeUnit rateUnit, TimeUnit durationUnit)
  {
    super(registry, "json-reporter", filter, rateUnit, durationUnit);
    this.metricsHost = Objects.requireNonNull(metricsHost,"No metrics hostname specified.");
    this.metricsPort = Objects.requireNonNull(metricsPort, "No metrics port specified.");
    this.sourceHost  = Optional.of(System.getenv("CDS_FQDN")).orElse("localhost");
  }

  @Override
  public void report(SortedMap<String, Gauge>     stringGaugeSortedMap,
                     SortedMap<String, Counter>   stringCounterSortedMap,
                     SortedMap<String, Histogram> stringHistogramSortedMap,
                     SortedMap<String, Meter>     stringMeterSortedMap,
                     SortedMap<String, Timer>     stringTimerSortedMap)
  {
    try {
      logger.debug("Reporting metrics as host " + sourceHost + "...");
      URL url = new URL("http", metricsHost, metricsPort, String.format("/report/metrics?h=%s", sourceHost));
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();

      connection.setDoOutput(true);
      connection.addRequestProperty("Content-Type", "application/json");

      OutputStream  outputStream = connection.getOutputStream();
      JsonGenerator json         = factory.createGenerator(outputStream, JsonEncoding.UTF8);

      json.writeStartObject();

      for (Map.Entry<String, Gauge> gauge : stringGaugeSortedMap.entrySet()) {
        reportGauge(json, gauge.getKey(), gauge.getValue());
      }

      for (Map.Entry<String, Counter> counter : stringCounterSortedMap.entrySet()) {
        reportCounter(json, counter.getKey(), counter.getValue());
      }

      for (Map.Entry<String, Histogram> histogram : stringHistogramSortedMap.entrySet()) {
        reportHistogram(json, histogram.getKey(), histogram.getValue());
      }

      for (Map.Entry<String, Meter> meter : stringMeterSortedMap.entrySet()) {
        reportMeter(json, meter.getKey(), meter.getValue());
      }

      for (Map.Entry<String, Timer> timer : stringTimerSortedMap.entrySet()) {
        reportTimer(json, timer.getKey(), timer.getValue());
      }

      json.writeEndObject();
      json.close();

      outputStream.close();

      logger.debug("Metrics server response: " + connection.getResponseCode());
    } catch (IOException e) {
      logger.warn("Error sending metrics", e);
    } catch (Exception e) {
      logger.warn("error", e);
    }
  }

  private void reportGauge(JsonGenerator json, String name, Gauge gauge) throws IOException {
    Object gaugeValue = evaluateGauge(gauge);

    if (gaugeValue instanceof Number) {
      json.writeFieldName(sanitize(name));
      json.writeObject(gaugeValue);
    }
  }

  private void reportCounter(JsonGenerator json, String name, Counter counter) throws IOException {
    json.writeFieldName(sanitize(name));
    json.writeNumber(counter.getCount());
  }

  private void reportHistogram(JsonGenerator json, String name, Histogram histogram) throws IOException {
    Snapshot snapshot = histogram.getSnapshot();
    json.writeFieldName(sanitize(name));
    json.writeStartObject();
    json.writeNumberField("count", histogram.getCount());
    writeSnapshot(json, snapshot);
    json.writeEndObject();
  }

  private void reportMeter(JsonGenerator json, String name, Meter meter) throws IOException {
    json.writeFieldName(sanitize(name));
    json.writeStartObject();
    writeMetered(json, meter);
    json.writeEndObject();
  }

  private void reportTimer(JsonGenerator json, String name, Timer timer) throws IOException {
    json.writeFieldName(sanitize(name));
    json.writeStartObject();
    json.writeFieldName("rate");
    json.writeStartObject();
    writeMetered(json, timer);
    json.writeEndObject();
    json.writeFieldName("duration");
    json.writeStartObject();
    writeTimedSnapshot(json, timer.getSnapshot());
    json.writeEndObject();
    json.writeEndObject();
  }

  private Object evaluateGauge(Gauge gauge) {
    try {
      return gauge.getValue();
    } catch (RuntimeException e) {
      logger.warn("Error reading gauge", e);
      return "error reading gauge";
    }
  }

  private void writeTimedSnapshot(JsonGenerator json, Snapshot snapshot) throws IOException {
    json.writeNumberField("max", convertDuration(snapshot.getMax()));
    json.writeNumberField("mean", convertDuration(snapshot.getMean()));
    json.writeNumberField("min", convertDuration(snapshot.getMin()));
    json.writeNumberField("stddev", convertDuration(snapshot.getStdDev()));
    json.writeNumberField("median", convertDuration(snapshot.getMedian()));
    json.writeNumberField("p75", convertDuration(snapshot.get75thPercentile()));
    json.writeNumberField("p95", convertDuration(snapshot.get95thPercentile()));
    json.writeNumberField("p98", convertDuration(snapshot.get98thPercentile()));
    json.writeNumberField("p99", convertDuration(snapshot.get99thPercentile()));
    json.writeNumberField("p999", convertDuration(snapshot.get999thPercentile()));
  }

  private void writeSnapshot(JsonGenerator json, Snapshot snapshot) throws IOException {
    json.writeNumberField("max", snapshot.getMax());
    json.writeNumberField("mean", snapshot.getMean());
    json.writeNumberField("min", snapshot.getMin());
    json.writeNumberField("stddev", snapshot.getStdDev());
    json.writeNumberField("median", snapshot.getMedian());
    json.writeNumberField("p75", snapshot.get75thPercentile());
    json.writeNumberField("p95", snapshot.get95thPercentile());
    json.writeNumberField("p98", snapshot.get98thPercentile());
    json.writeNumberField("p99", snapshot.get99thPercentile());
    json.writeNumberField("p999", snapshot.get999thPercentile());
  }

  private void writeMetered(JsonGenerator json, Metered meter) throws IOException {
    json.writeNumberField("count", convertRate(meter.getCount()));
  }

  private String sanitize(String metricName) {
    return SIMPLE_NAMES.matcher(metricName).replaceAll("_");
  }

  public static Builder forRegistry(MetricRegistry registry) {
    return new Builder(registry);
  }

  public static class Builder {

    private final MetricRegistry registry;
    private       MetricFilter   filter       = MetricFilter.ALL;
    private       TimeUnit       rateUnit     = TimeUnit.SECONDS;
    private       TimeUnit       durationUnit = TimeUnit.MILLISECONDS;
    private       String         hostname;
    private       Integer        port;

    private Builder(MetricRegistry registry) {
      this.registry     = registry;
      this.rateUnit     = TimeUnit.SECONDS;
      this.durationUnit = TimeUnit.MILLISECONDS;
      this.filter       = MetricFilter.ALL;
    }

    public Builder convertRatesTo(TimeUnit rateUnit) {
      this.rateUnit = rateUnit;
      return this;
    }

    public Builder convertDurationsTo(TimeUnit durationUnit) {
      this.durationUnit = durationUnit;
      return this;
    }

    public Builder filter(MetricFilter filter) {
      this.filter = filter;
      return this;
    }

    public Builder withPort(Integer port) {
      this.port = port;
      return this;
    }

    public Builder withHostname(String hostname) {
      this.hostname = hostname;
      return this;
    }

    public JsonMetricsReporter build() throws UnknownHostException {
      return new JsonMetricsReporter(registry, hostname, port, filter, rateUnit, durationUnit);
    }
  }
}
