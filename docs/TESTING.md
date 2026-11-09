# Testing

Ark Investment API includes automated tests for business logic, database integrity, authorization, financial reporting, and API behavior.

## Prerequisites

- Java 21
- Running Docker with Docker Compose v2
- Node.js 22+ (npm/npx for OpenAPI lint and optional Newman)
- Ruby with YAML support

First use requires internet access for Maven dependencies, container images, and npx tools. Docker must have memory headroom for PostgreSQL integration tests and, when running the demo, the API and Keycloak. Concurrent stacks/builds can cause timeouts or OOM kills; no minimum RAM requirement has been established.

## 1. Java Tests

Run the complete Maven verification lifecycle from the repository root:

```sh
./mvnw -B clean verify
```

The suite includes:

- Unit tests for business rules and validation.
- PostgreSQL integration tests using Testcontainers.
- Transaction concurrency and rollback tests.
- Client isolation and role-based authorization tests.
- Financial reporting consistency tests.

## 2. Node Tests

Run the Node.js tests:

```sh
node --test docs/api/monetary-validation.test.cjs docs/demo/fixtures.test.cjs docs/demo/start.test.cjs
```

The three suites validate monetary contracts, deterministic demo fixtures, and startup orchestration/token retries.

## 3. OpenAPI Validation

Validate the API specification:

```sh
npx --yes @redocly/cli@2.75.0 lint docs/api/openapi.yaml
```

CI for `c5f115f` passed lint with two non-blocking warnings: `info-license` for missing license metadata and `no-server-example.com` for the localhost server URL.

## 4. Live API Verification

Start and seed the local environment as described in [Getting Started](GETTING_STARTED.md).

Then run:

```sh
node docs/demo.cjs verify
```

The live verifier exercises authenticated API operations, financial calculations, Client isolation, authorization, and audit behavior against PostgreSQL and Keycloak.

## 5. Current Submission Verification

[GitHub Actions run 42699130133](https://github.com/arakhlin/ark-investment/actions/runs/42699130133) passed on November 9, 2026 for Submission commit `c5f115fd143364030dd330fd82d9dd5e73473740`:

| Check | Result |
| --- | --- |
| Java 21 Maven verification and PostgreSQL Testcontainers | 165 tests; zero failures, errors, or skips |
| Node monetary contract suite | 124 passed |
| Node demo fixture and startup/retry suites | 34 passed; 158 Node tests total, zero failures |
| Redocly OpenAPI lint | Passed with the two warnings listed above |

The workflow is defined in [ci.yml](../.github/workflows/ci.yml). It does not run the full Keycloak demo acceptance or Newman workflow.

### Startup timeout investigation and fix

The original fresh Submission clone at `c9554bf` failed its first `start` during token acquisition after services became healthy, leaving application identities unprovisioned. A second invocation recovered, and subsequent checks passed, but this did not satisfy the first-run startup gate.

A local provisioning-only retry fix was then verified using fresh isolated Docker resources. Token acquisition recovered from actual 20-second timeouts within one `start`; repeated startup preserved table counts, row digests, and credential hashes. Live verification passed 263 HTTP checks, including 184 exact reports, all six roles, Client isolation, API writes/audits, and repeated seed. A separate real HTTP timeout experiment succeeded on its second request after a 1-second backoff (21,152 ms total). These results preceded the final response-body timeout adjustment and were retained as local investigation artifacts, not committed CI artifacts.

Commit `c5f115f` also handles response-body `AbortError` only when the request's own signal expired with `TimeoutError`. A real local HTTP regression sends headers immediately and stalls the body; separate coverage exercises timeout-caused `AbortError` and non-timeout cancellation. The focused startup suite passed 32 tests locally on Node 22.19.0; the complete current CI passed as recorded above. Provisioning token acquisition retains fresh 20-second deadlines, four total attempts, and 1/2/4-second backoff. SQL and business API operations are not retried.

**Full Docker acceptance was not repeated on final implementation commit `c5f115f`.** Earlier live retry verification and current CI are separate evidence; neither is a claim that the final commit passed a new full Docker acceptance run. Retries do not resolve OOM or persistent resource exhaustion.

## 6. Historical Development Acceptance

Development source revision `f887112185b46b3df4cee87930ba0a9a245b77cb` was tested in separate clean local clones on November 9, 2026. Its record was committed as `464b30a` in the Development Repository; these identifiers are historical provenance, not Submission revisions.

| Historical check | Recorded result |
| --- | --- |
| Java 21 Maven verification | 165 passed; zero failures, errors, or skips |
| Earlier Node contract/fixture suites | 126 passed; excludes current startup/retry coverage |
| OpenAPI lint | Passed with the two warnings listed above |
| Live demo and retained-volume restart | Passed before/after restart; 184 exact reports per pass; stored counts/digests preserved |
| Newman | 58 requests, 125 assertions, zero failures |

Those local clones used available dependency/image caches. Historical results do not establish current Submission acceptance.

## Scope

These tests provide evidence for the implemented API behavior. They do not establish production readiness, load-test capacity, or complete security coverage.

The local demo is intended for reproducible evaluation, not production deployment.
