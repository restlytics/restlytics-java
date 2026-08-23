# restlytics Spring (Java) SDK

Framework-native tracing for **Spring Boot 3** apps. Captures one trace per HTTP
request — a root SERVER span plus CLIENT child spans for DB queries and outbound HTTP
calls — and ships them as **OTLP/JSON** to the restlytics ingestion service,
**fire-and-forget**, off the request thread.

> One contract, every language. This SDK emits the exact same OTLP/JSON wire format as
> every other restlytics SDK — see [`../SPEC.md`](../SPEC.md) for the authoritative
> cross-language contract.

- **Maven:** `com.restlytics:restlytics-spring`
- **Java:** 17+
- **License:** MIT
- Zero added request latency: spans are buffered in-request and the gzipped POST runs
  on a background executor after the response.

---

## Install

```xml
<dependency>
    <groupId>com.restlytics</groupId>
    <artifactId>restlytics-spring</artifactId>
    <version>0.1.0</version>
</dependency>
```

That's it for the request + DB instrumentation: the SDK auto-configures via Spring
Boot (`META-INF/spring/...AutoConfiguration.imports`). With a key set, every request
produces a trace.

---

## Configure

All keys have env-var and `application.properties` forms (SPEC §7). The property prefix
is `restlytics.`.

### `application.properties`

```properties
restlytics.key=YOUR_INGEST_KEY
restlytics.ingest-url=https://ingest.restlytics.com
restlytics.service-name=my-service
restlytics.env=production
restlytics.sample-rate=1.0
restlytics.transport=http
restlytics.timeout-ms=2000
restlytics.capture-sql=false
restlytics.instrument-db=true
restlytics.instrument-http=true
restlytics.instrument-cache=true
restlytics.max-spans=2000
restlytics.enabled=true
# optional lists:
# restlytics.ignore-paths=/actuator,/actuator/*,/health,/healthz,/favicon.ico
# restlytics.redact-query-keys=token,api_key,password,secret,access_token
```

### Environment variables

```bash
export RESTLYTICS_KEY=YOUR_INGEST_KEY
export RESTLYTICS_INGEST_URL=https://ingest.restlytics.com
export RESTLYTICS_SERVICE_NAME=my-service
export RESTLYTICS_ENV=production
export RESTLYTICS_SAMPLE_RATE=1.0
export RESTLYTICS_TRANSPORT=http            # http | null
export RESTLYTICS_TIMEOUT_MS=2000
export RESTLYTICS_CAPTURE_SQL=false
export RESTLYTICS_INSTRUMENT_DB=true
export RESTLYTICS_INSTRUMENT_HTTP=true
export RESTLYTICS_INSTRUMENT_CACHE=true
export RESTLYTICS_MAX_SPANS=2000
```

When the key is blank the SDK quietly disables delivery (a `NullTransport` is wired),
so it is safe to ship the dependency before provisioning a key.

| Key | Env | Default | Notes |
|---|---|---|---|
| `restlytics.key` | `RESTLYTICS_KEY` | `""` | Sent as `X-Restlytics-Key`. Blank disables delivery. |
| `restlytics.ingest-url` | `RESTLYTICS_INGEST_URL` | `https://ingest.restlytics.com` | POSTs to `{url}/v1/traces`. |
| `restlytics.service-name` | `RESTLYTICS_SERVICE_NAME` | `spring` | `service.name` resource attr. |
| `restlytics.env` | `RESTLYTICS_ENV` | `production` | `deployment.environment`. |
| `restlytics.sample-rate` | `RESTLYTICS_SAMPLE_RATE` | `1.0` | Head-based, per-trace. |
| `restlytics.transport` | `RESTLYTICS_TRANSPORT` | `http` | `http` or `null`. |
| `restlytics.timeout-ms` | `RESTLYTICS_TIMEOUT_MS` | `2000` | Hard send timeout. |
| `restlytics.capture-sql` | `RESTLYTICS_CAPTURE_SQL` | `false` | Raw SQL (`db.query.text`, capped 2048). Bindings are NEVER sent regardless. |
| `restlytics.instrument-db/http/cache` | `RESTLYTICS_INSTRUMENT_*` | `true` | Per-instrument toggles. |
| `restlytics.max-spans` | `RESTLYTICS_MAX_SPANS` | `2000` | In-request buffer cap. |
| `restlytics.enabled` | — | `true` | Master switch for the auto-config. |

---

## What gets captured

Per request (SPEC §1):

- a **root SERVER span** (`kind=2`, `restlytics.category="app"`) with
  `http.request.method`, `http.route` (the **route TEMPLATE** from
  `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, e.g. `/users/{id}` — never the raw
  URL), `http.response.status_code`, `restlytics.db_query_count`, and
  `restlytics.self_ns.{db,http,cache,app}` self-time computed by interval-union;
- **DB CLIENT spans** (`kind=3`, `restlytics.category="db"`) via the Hibernate
  `StatementInspector`, carrying `db.query.summary` (normalized, literal-free — the N+1
  grouping key) and `restlytics.bindings_count` (count only);
- **HTTP CLIENT spans** (`kind=3`, `restlytics.category="http"`) via an optional
  `RestTemplate` interceptor (`url.full` redacted).

### Outbound HTTP (opt-in)

Register the interceptor on your `RestTemplate`:

```java
@Bean
RestTemplate restTemplate(Tracer tracer, RestlyticsConfig config) {
    RestTemplate rt = new RestTemplate();
    rt.getInterceptors().add(new RestlyticsClientHttpInterceptor(tracer, config));
    return rt;
}
```

For `WebClient`, the equivalent is an `ExchangeFilterFunction` applying the same
attribute rules (best-effort).

### DB timing note

The Hibernate `StatementInspector` runs just before execution and only sees the SQL
string, so the bundled hook records the N+1 grouping key (`db.query.summary`) and the
query count at inspection time. For precise per-query **durations**, wrap your
`DataSource`/`Connection`/`Statement` and time `execute*` calls, calling
`tracer.addChildSpan(name, startNs, endNs, Span.KIND_CLIENT)` with the same `db.*`
attributes. Both approaches feed the same span buffer.

---

## Safety (SPEC §6)

- **Fire-and-forget**: the gzipped OTLP POST runs on a bounded daemon executor with a
  ~2s timeout; the request thread never waits on it.
- **Swallows all errors**: if ingest is down/slow the batch is dropped — the host app
  is never affected, never blocked, never thrown into.
- **Redaction**: bindings counted, never sent; SQL normalized (literal-free); every
  outbound `url.full` query value scrubbed; credentials/fragments, headers, bodies,
  and exception content are never exported.
- **Thread isolation**: per-request state lives in a `ThreadLocal`, cleared with
  `remove()` in a `finally` so pooled servlet threads never leak state between requests.
- **Bounded memory**: the in-request span buffer is capped (default 2000 spans).
- **Head-based sampling**: the keep/drop decision is made once per trace from the
  trace id, so all spans in a trace share the same fate.

---

## Architecture

The SDK splits into a **dependency-free core** (only `java.*`, compiles with plain
`javac`) and **Spring/Hibernate-dependent** classes.

**Core** (`com.restlytics.*`):
`Ids` · `Sql` · `Intervals` · `Otlp` (+ tiny internal JSON writer) · `Transport` /
`HttpTransport` / `NullTransport` · `Tracer` · `Span` · `RestlyticsConfig` ·
`Redaction`.

**Spring/Hibernate** (`com.restlytics.spring.*`):
`RestlyticsFilter` (`OncePerRequestFilter`) · `RestlyticsStatementInspector` ·
`RestlyticsClientHttpInterceptor` · `RestlyticsAutoConfiguration`.

---

## Build & test

```bash
mvn -q test       # runs the JUnit + offline contract tests
mvn -q package    # builds the jar
```

The two mandatory contract tests (`SqlTest`, `IntervalsTest`) are dependency-free and
also run offline without Maven:

```bash
javac -d /tmp/out \
  $(find src/main/java/com/restlytics -maxdepth 1 -name '*.java') \
  src/test/java/com/restlytics/Assert.java \
  src/test/java/com/restlytics/SqlTest.java \
  src/test/java/com/restlytics/IntervalsTest.java
java -cp /tmp/out com.restlytics.SqlTest
java -cp /tmp/out com.restlytics.IntervalsTest
```

## Cross-language conformance

CI pins [`restlytics/sdk-conformance@v1.1.0`](https://github.com/restlytics/sdk-conformance)
and compares the vendored fixture before testing. The dependency-free suite proves exact semantic OTLP
output, W3C propagation, root sampling, source redaction, and error-status behavior shared by all seven
SDKs. This is the wire-level gate; real Spring Boot/Hibernate application validation is tracked separately.
