# Evolution plan

Token Pilot is starting as a framework-independent Java LLM control and
accounting core with optional adapters. The long-term direction is a Java-native
embeddable gateway SDK and, later, an optional standalone runtime.

## Post-MVP workstreams

### 1. Exact token intelligence

- Add exact byte-level BPE implementations and versioned encoding assets.
- Expand Unicode and provider corpus coverage.
- Benchmark estimators with JMH and make tokenizer compatibility explicit.
- Preserve the existing safe-upper-bound contract while exact implementations mature.

### 2. Provider-independent gateway slice

- Define provider-neutral request, response, usage, and error contracts.
- Add an optional OpenAI-compatible HTTP gateway surface.
- Keep the core accounting and budget interfaces reusable by direct SDK consumers and gateway callers.

### 3. Reliable routing and execution

- Add retries, fallback, circuit breakers, and provider attempt identity.
- Reconcile cost and usage at attempt level.
- Define streaming cancellation and partial-usage semantics before enabling enforcement for streaming calls.

### 4. Durable multi-tenant operations

- Add durable reservation, accounting, notification, and audit stores.
- Support multi-instance atomicity and restart replay.
- Add tenant policy administration and security hardening.
- Add OpenTelemetry integration where it complements, rather than duplicates, Spring AI telemetry.

These workstreams must not be presented as implemented features until their
contracts, tests, and external-consumer evidence land.
