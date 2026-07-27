# TokenPilot

> One Java gateway to control LLM providers, routing, fallback, tokens, and costs.

TokenPilot helps Java applications control an LLM call before it is sent and
reconcile its cost after it completes. It brings token estimation, context
checks, budget enforcement, and actual usage-based accounting into one layer.

## Why TokenPilot?

Adding an LLM to a service is more than calling a provider API. Applications
need to know whether a request fits the model context window, whether it can
stay within a budget, and what it actually cost after the provider responds.

Without a common layer, each service rebuilds token counting, pricing, budget,
and fallback rules in its own way. TokenPilot provides a single place to apply
those controls.

## What It Controls

- Estimates tokens and checks context limits before an LLM call.
- Enforces spending limits before a provider is invoked.
- Reconciles estimated cost with provider-reported usage after a call.
- Records usage, cost, policy decisions, and budget lifecycle events.
- Integrates with Spring AI while keeping LLM control and accounting concerns
  separate from application business logic.

## How It Works

```text
LLM request
→ token and context preflight
→ cost and budget decision
→ provider call
→ actual usage normalization
→ final cost reconciliation
```

## Gateway Direction

TokenPilot starts with LLM usage control and cost accounting. It is evolving
into a Java LLM Gateway for unified provider access, model routing, reliable
fallback, and operational policy control.

Spring AI is the first integration path. The long-term goal is to let Java
applications use the same control layer regardless of the provider or client
library behind it.

## Project Status

TokenPilot 0.1.0 is under active development. The current foundation includes
Spring AI usage integration, model cost calculation, metrics, basic budget
policy, and Spring Boot autoconfiguration. The MVP is extending this foundation
with preflight control, atomic budget reservation, and actual usage
reconciliation.

## License

Token Pilot is licensed under the [MIT License](LICENSE).
