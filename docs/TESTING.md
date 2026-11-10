# Testing

These successful GitHub Actions runs cover earlier committed revisions, not the current uncommitted startup candidate:

- [CI](https://github.com/arakhlin/ark-investment/actions/runs/42705596451): Java unit and PostgreSQL integration tests, Node tests, and OpenAPI lint.
- [Fresh Docker Demo Acceptance](https://github.com/arakhlin/ark-investment/actions/runs/42705596389): clean checkout, fresh Docker startup, repeated startup, seed, live API verification, repeated seed and verification, and cleanup.

## Run Automated Tests

From the repository root, with Java 21, Node.js 22+, and Docker running:

**Java tests** (including PostgreSQL integration tests via Testcontainers):

```sh
./mvnw -B clean verify
```

**Node tests:**

```sh
node --test docs/api/monetary-validation.test.cjs docs/demo/fixtures.test.cjs docs/demo/start.test.cjs
```

**OpenAPI lint** (requires npm/npx):

```sh
npx --yes @redocly/cli@2.75.0 lint docs/api/openapi.yaml
```

## Run the Live Demo Verification

With Docker and Node.js 22+:

```sh
node docs/demo.cjs start
node docs/demo.cjs seed
node docs/demo.cjs verify
```

The verifier exercises authenticated endpoints, financial reporting, Client isolation, authorization, and audit behavior against PostgreSQL and Keycloak. Repeat `start`, `seed`, and `verify` to check rerun behavior.

Stop the demo without deleting data:

```sh
node docs/demo.cjs down
```

See [Getting Started](GETTING_STARTED.md) for setup and API examples. The demo is intended for local evaluation, not production deployment.

## Local candidate validation — November 9, 2026

The current candidate retains a custom Keycloak 26.8.2 image with build-time augmentation and `start --optimized --import-realm`. It uses a 64–512 MiB JVM heap, explicit local HTTP, a local cache, and a persisted `dev-file` database. The heap budget is not a container memory limit. The API starts after PostgreSQL becomes healthy, alongside Keycloak; the CLI waits for all three healthchecks before provisioning. Application security rules and database schema are unchanged. This remains a local demo, not a production deployment.

### Startup measurements

All candidate trials used fresh volumes and credentials. Fresh volumes do **not** imply uncached image builds. Startup timings run from CLI launch through provisioning and command exit, including image pulls/checks, builds, and service readiness; `READY` alone is not the endpoint.

| Measurement | Image cache and Docker workload | Startup through provisioning | Complete setup including seeding |
| --- | --- | ---: | ---: |
| Supplied original baseline | Conditions not controlled against candidate | 350.65 s | Not recorded |
| Previous candidate, augmentation layer uncached | API layers and upstream images cached; two other stacks running | 352.94 s | Not recorded |
| Previous candidate, cached build | Image layers cached; two other stacks running | 193.72 s | Not recorded |
| Latest controlled candidate | Image layers cached; no unrelated containers running | **48.49 s** | **49.30 s** |

The latest startup time was 74.97% shorter than the previous cached run, but workload differed. The measurements do not isolate the optimization's effect. The earlier augmentation-uncached run was 0.65% slower than the supplied baseline and did not meet the 25% reduction target. No proven uncached-build performance improvement exists; truly uncached builds may take several minutes and were not measured by these trials.

The controlled run used Docker Desktop on arm64 with 8 CPUs and 3.825 GiB RAM. Host compression and swapping remained active despite the reduced Docker workload.

| Controlled phase | Duration | Elapsed from CLI launch |
| --- | ---: | ---: |
| Combined image build invocation, cached | Approximately 2.54 s | 3.72 s |
| PostgreSQL container start to healthy | 6.37 s | 11.38 s |
| Keycloak container start to healthy | 35.05 s | 40.44 s |
| API container start to healthy | 21.84 s | 34.08 s |
| All services ready, CLI reports `READY` | — | 41.02 s |
| Provisioning after `READY` | 7.46 s | 48.47 s |
| Startup command exit | — | 48.49 s |
| Optional seeding | 0.80 s | 49.30 s |

Service startup overlaps, so these durations must not be added together. Timings include small orchestration gaps. Runtime samples through acceptance peaked at 640.70 MiB for Keycloak, 407.00 MiB for the API, and 77.88 MiB for PostgreSQL. Aggregate sampled peaks were 1,125.58 MiB and 633.58% CPU (about 6.34 cores). Samples were approximately five seconds apart; they exclude builder usage and may miss brief peaks. No startup failures, container restarts, OOM kills, or token-acquisition retries were observed in the controlled run.

### Acceptance results and evidence

The controlled candidate passed initial startup and seeding, verification, repeated startup and seeding, repeated verification, and live administrative security-hardening checks. Each verification passed 263 HTTP checks and 184 exact financial reports, covering six roles, Client isolation, API writes, and audits. Repeated startup took 4.94 seconds with existing healthy containers and persisted data; this is not a fresh-volume measurement.

Earlier local candidate validation also recorded 165 passing Java tests, 163 passing Node tests, and OpenAPI lint with two existing warnings (missing license and localhost server URL). Those are historical local results, not GitHub CI validation of the current working tree or of the subsequently edited documentation and workflow.

Local evidence remains under `.ark-demo/reduced-load-diagnostics-20261109/` and `.ark-demo/cold-start-diagnostics-20261109/`. These ignored directories contain timings, logs, resource samples, and acceptance results; they are not included in a clean checkout. Generated credentials remain private and must not be submitted.

### Reproduction

Choose a new project name and unused ports for each fresh-volume trial. Retain cache-hit output and record Docker resources, competing workloads, startup time, and seed time separately. Do not clear shared image caches or reuse existing data volumes to label a run uncached.

```sh
export DEMO_PROJECT=ark-demo-review-unique
export DEMO_API_PORT=38082
export DEMO_OIDC_PORT=39082
time node docs/demo.cjs start
time node docs/demo.cjs seed
node docs/demo.cjs verify
node docs/demo.cjs start
node docs/demo.cjs seed
node docs/demo.cjs verify
node docs/demo/hardening.cjs
```

Retain the startup package as a locally validated demo setup, without claiming proven uncached-build gains. A baseline/candidate comparison with equivalent cache state and workload would be needed to establish a causal performance improvement.
