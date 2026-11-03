# Local telemetry stack

A local Grafana stack to look at the OpenTelemetry traces and metrics of kotlin-editor (the desktop client
`kotlin-editor-desktop` and the server `kotlin-editor-server`):

- **Alloy** receives OTLP on `localhost:4318` (HTTP) and `localhost:4317` (gRPC), sends traces to Tempo and
  metrics to Mimir (Prometheus remote write). Its UI is on http://localhost:12345.
- **Tempo** stores the traces (local disk).
- **Mimir** stores the metrics (monolithic, filesystem, no multi-tenancy).
- **Grafana** on http://localhost:3000 (anonymous admin, no login), with the Tempo and Mimir data sources and the
  *Kotlin Editor performance* dashboard already provisioned.

All ports are bound to `127.0.0.1` only. It needs Docker with the Compose plugin.

## Start

```sh
cd tools/telemetry
docker compose up -d
```

Open http://localhost:3000: the home page is the *Kotlin Editor performance* dashboard (p50/p95/p99 per operation,
error and request rates, RPC client vs server vs network, startup time per version, recent traces).

## Point the IDE at it

`http://localhost:4318` is the default OTLP endpoint, so nothing is needed when the stack runs on the same machine.
Otherwise set it explicitly before starting the IDE (or the server):

```sh
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

Then enable telemetry in the IDE settings; the data shows up in Grafana within a minute (metrics are exported
periodically).

Metrics carry the resource attributes as labels (`service_name`, `service_version`, `service_instance_id`,
`os_type`), so the dashboard can filter by version. The metrics must use cumulative temporality (the OpenTelemetry
SDK default).

## Stop and clean

```sh
docker compose down      # stop, keep the data
docker compose down -v   # stop and delete all traces, metrics and Grafana state
```
