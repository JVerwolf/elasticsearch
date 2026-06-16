# OTel SDK traces health — local end-to-end test

Tests the OTel SDK self-monitoring metrics wired in [ES PR #150026](https://github.com/elastic/elasticsearch/pull/150026) and validates the [Kibana dashboard (o11y-resources PR #266)](https://github.com/elastic/elasticsearch-o11y-resources/pull/266) and [alerting rules (PR #269)](https://github.com/elastic/elasticsearch-o11y-resources/pull/269) against real data.

## What this tests

PR #150026 wires a `MeterProvider` into `OtlpHttpSpanExporter` and `BatchSpanProcessor` so the OTel SDK emits self-monitoring metrics for the span export pipeline. Without this change, the metrics (`otel.sdk.processor.span.queue.size`, `otel.sdk.exporter.operation.duration`, etc.) are never produced. This setup verifies those metrics flow end-to-end: ES → APM Server (OTLP HTTP) → Elasticsearch → Kibana dashboard.

## Stack

| Service | URL | Notes |
|---|---|---|
| Elasticsearch (from source) | `http://localhost:9200` | PR branch |
| APM Server (Docker) | `http://localhost:8200` | OTLP receiver |
| Kibana (Docker) | `http://localhost:5601` | Dashboard at `/app/dashboards` |

## Prerequisites

- Docker running
- Port 8200 and 5601 free (stop `prometheus-local` first if needed — it uses different ports but conflicts on a shared ES)

## Step 1 — Checkout the PR branch

```bash
git fetch origin
git checkout -b test-otel-traces-e2e origin/wire-otel-tracing-self-metrics
```

## Step 2 — Start APM Server and Kibana

```bash
cd dev-tools/otel-traces-local
docker compose up -d
```

APM Server will start immediately. Kibana needs ES to set the `kibana_system` password first, so start ES in Step 3 before Kibana finishes its health check.

## Step 3 — Start Elasticsearch with OTel traces enabled

Run from the repository root. The `--using-otel-sdk` flag enables OTel SDK metrics export; the JVM argline adds the OTel traces system property; the `tests.es.*` properties point both pipelines at the local APM Server.

```bash
./gradlew run --configuration-cache --using-otel-sdk \
    -Dtests.es.http.host=0.0.0.0 \
    -Dtests.es.xpack.ml.enabled=false \
    -Dtests.es.telemetry.tracing.enabled=true \
    -Dtests.es.telemetry.otel.traces.endpoint=http://localhost:8200/v1/traces \
    -Dtests.es.telemetry.otel.metrics.endpoint=http://localhost:8200/v1/metrics \
    -Drun.license_type=trial \
    -Dtests.heap.size=4G \
    "-Dtests.jvm.argline=-da -dsa -Dio.netty.leakDetection.level=simple -Dtelemetry.otel.traces.enabled=true"
```

Wait for the log line `[cluster_name] started` before continuing.

## Step 4 — Import the Kibana dashboard

Once Kibana is ready (the `es-otel-kibana` container passes its healthcheck), run:

```bash
cd dev-tools/otel-traces-local
./setup-kibana.sh
```

This creates a `metrics-*` data view with the ID `serverless.metrics-*` (matching what the dashboard references) and imports the dashboard from PR #266.

## Step 5 — Generate trace data

Each ES operation creates spans that go through `BatchSpanProcessor → OtlpHttpSpanExporter`. Run a burst of operations so the self-monitoring metrics have something to report:

```bash
# Create an index
curl -su elastic-admin:elastic-password -X PUT http://localhost:9200/otel-test -H "Content-Type: application/json" -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}'

# Index 50 documents to create spans
for i in $(seq 1 50); do curl -s -u elastic-admin:elastic-password -X POST http://localhost:9200/otel-test/_doc -H "Content-Type: application/json" -d "{\"msg\":\"doc-$i\",\"@timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /dev/null; done

# Run searches
for i in $(seq 1 20); do curl -s -u elastic-admin:elastic-password http://localhost:9200/otel-test/_search > /dev/null; done
```

The `BatchSpanProcessor` flushes every 10 seconds by default. Within ~30 seconds the SDK metrics should appear in APM Server and flow to ES.

## Step 6 — Verify in Kibana

**Check APM Server received traces:**
```bash
curl -sf http://localhost:8200/ | python3 -m json.tool
```

**Check ES has metrics data:**
```bash
curl -su elastic-admin:elastic-password "http://localhost:9200/metrics-apm.internal-*/_search?size=1&pretty"
```

Look for documents with fields like `otel.sdk.processor.span.queue.size`.

**Open the dashboard:**
Navigate to `http://localhost:5601/app/dashboards#/view/serverless-es-otel-sdk-health-traces` and set the time range to "Last 15 minutes". You should see:

- **Span processor queue size** — avg/max spans queued in `BatchSpanProcessor`
- **Exporter operation duration** — p50/p95/max export latency in seconds
- **Spans exported (delta sum)** — cumulative spans successfully exported
- **Spans inflight (avg & max)** — spans currently being exported

> **Note:** The "Filter by Project Id" control and the navigation link panels will show as missing — those depend on serverless infrastructure objects. The four metric charts work independently.

**Check raw data in Discover:**
1. Open Kibana Discover
2. Select data view `serverless.metrics-*` (which resolves to `metrics-*` locally)
3. Filter: `otel.sdk.processor.span.queue.size : *`
4. You should see documents with the OTel SDK self-monitoring fields

## Verifying the alert thresholds (PR #269)

The alerts fire when:
- **Slow export**: `otel.sdk.exporter.operation.duration` max > 30s in 15m window
- **Queue near capacity**: `queue.size / queue.capacity` ratio > 0.9 for 15m

To smoke-test the queue saturation alert threshold, you can temporarily check the ratio yourself:
```bash
curl -su elastic-admin:elastic-password "http://localhost:9200/metrics-apm.internal-*/_search?pretty" -H "Content-Type: application/json" -d '{"size":5,"sort":[{"@timestamp":"desc"}],"_source":["otel.sdk.processor.span.queue.size","otel.sdk.processor.span.queue.capacity","@timestamp"]}'
```

A non-zero `queue.size` and a `queue.capacity` field confirms the new wiring in PR #150026 is working.

## Field mapping limitations with APM Server 8.18

APM Server's `metrics@mappings` component template applies `"index": false` to all dynamically-discovered numeric fields. Only fields **explicitly** listed in APM Server's own component templates are indexed and aggregatable.

In APM Server 8.18.3, these OTel SDK fields are explicitly mapped (aggregatable ✅):
- `otel.sdk.processor.span.queue.size`
- `otel.sdk.processor.span.queue.capacity`
- `otel.sdk.exporter.metric_data_point.exported`
- `otel.sdk.exporter.metric_data_point.inflight`

These fields from PR #150026 are **not yet** in the APM Server 8.18 template (data is in `_source` but not aggregatable ❌):
- `otel.sdk.exporter.span.exported`
- `otel.sdk.exporter.span.inflight`
- `otel.sdk.processor.span.processed`
- `otel.sdk.exporter.operation.duration` (histogram)

This means the **"Span processor queue size"** Kibana panel renders correctly locally, but the exporter-specific panels need the APM Server template to be updated before they can aggregate.

To verify the data IS flowing (even if not aggregatable), query `_source` directly:
```bash
curl -su elastic-admin:elastic-password "http://localhost:9200/metrics-apm.app.elasticsearch-*/_search?pretty" -H "Content-Type: application/json" \
  -d '{"size":1,"sort":[{"@timestamp":"desc"}],"query":{"term":{"labels.otel_component_type":"otlp_http_span_exporter"}}}'
```

You should see `otel.sdk.exporter.span: [{"inflight": 0}, {"exported": N}]` in the `_source`.

## Troubleshooting

**No data in `metrics-apm.app.elasticsearch-*`:**
- Confirm ES started with `telemetry.otel.traces.enabled=true` in the JVM args. Search the ES logs for `OtelSdkExportTracerSupplier` to confirm it's active.
- Check APM Server logs: `docker compose logs apm-server --tail 50`
- Verify APM Server received data: `curl http://localhost:8200/` should show a server info response.

**Kibana cannot connect to ES:**
- The `kibana_settings` container sets the `kibana_system` password after ES is up. If Kibana started before ES, restart: `docker compose restart kibana kibana_settings`

**APM Server version compatibility:**
- APM Server 8.18.3 is used here. If you see index template conflicts with ES 9.x, try `docker.elastic.co/apm/apm-server:9.0.3` in the `docker-compose.yml`.

## Cleanup

```bash
cd dev-tools/otel-traces-local
docker compose down -v
```

Stop the `./gradlew run` process with `Ctrl+C`.
