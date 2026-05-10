# PlatformFlow Android SDK

A workflow orchestration SDK for KYC and identity verification flows on Android. PlatformFlow executes multi-step, server-defined workflows as directed acyclic graphs (DAGs), routing between pluggable components based on conditional rules and flowing data between steps via scoped references.

## Architecture

```
PlatformFlow (singleton facade)
  └─ WorkflowEngine (per-session, suspending)
       ├─ ComponentRegistry  — resolves component type → FlowComponent
       ├─ SessionStore       — in-memory state ($input, $context, node outputs)
       ├─ DataResolver       — translates scope references to values
       └─ RuleEvaluator      — evaluates edge rules for conditional routing
```

**Engine loop:** validate graph &rarr; resolve node inputs &rarr; execute component &rarr; record outputs &rarr; evaluate outgoing edge rules &rarr; apply data mappings &rarr; advance to next node &rarr; repeat until terminal.

## Project Structure

| Module | Description |
|---|---|
| `platformflow-core` | The SDK library |
| `app` | Demo app exercising the engine end-to-end |

```
platformflow-core/
  api/          Public facade, config, callbacks, errors, results
  engine/       WorkflowEngine, SessionStore, DataResolver, RuleEvaluator
  model/        WorkflowDefinition, WorkflowNode, WorkflowEdge, rules
  component/    FlowComponent interface, ComponentResult, Verdict
  components/   Built-in component implementations
  registry/     Component registry
  network/      Workflow fetching and result reporting
  ui/           ComponentHost bridge, PlatformFlowActivity
```

## Quick Start

### Initialization

```kotlin
PlatformFlow.initialize(
    PlatformFlowConfig(
        apiKey   = "your-api-key",
        baseUrl  = "https://api.example.com",
    )
)
```

### Register Custom Components

```kotlin
PlatformFlow.registerComponent(MyCustomComponent())
```

### Launch a Workflow

```kotlin
PlatformFlow.start(
    context    = this,
    workflowId = "kyc-workflow-v2",
    input      = mapOf("firstName" to "Jane", "selfie" to selfieBytes),
    callbacks  = object : PlatformFlowCallbacks {
        override fun onStepComplete(nodeId: String, step: Int, total: Int) { }
        override fun onComplete(result: SessionResult) { }
        override fun onError(error: PlatformFlowError) { }
        override fun onCancelled() { }
    },
)
```

## Workflow Model

A workflow is defined by nodes and edges:

```kotlin
WorkflowDefinition(
    workflowId = "sample-kyc",
    version    = 1,
    tenantId   = "tenant-1",
    name       = "Sample KYC",
    entryNode  = "collect_data",
    nodes      = listOf(
        WorkflowNode(id = "collect_data", componentType = "DATA_FORM", ...),
        WorkflowNode(id = "nin_verify",   componentType = "NIN_VERIFICATION", ...),
    ),
    edges      = listOf(
        WorkflowEdge(id = "e1", from = "collect_data", to = "nin_verify", default = true),
    ),
)
```

### Scope References

Data flows between nodes using scope references in `inputMapping` and edge `dataMapping`:

| Scope | Example | Description |
|---|---|---|
| `$input` | `$input.selfie` | Host app's input bag (immutable) |
| `$context` | `$context.idPhoto` | Temporary storage written by edge data mappings |
| `$<nodeId>` | `$nin_verify.verdict` | Output of a previously executed node |

### Edge Rules

Edges support conditional routing with boolean rule trees:

```kotlin
WorkflowEdge(
    from = "collect_data",
    to   = "nin_verify",
    rule = EdgeRule(
        operator   = RuleOperator.AND,
        conditions = listOf(
            RuleItem.Condition(
                field      = "\$collect_data.nationality",
                comparator = Comparator.EQ,
                value      = "NG",
            ),
        ),
    ),
)
```

Comparators: `EQ`, `NEQ`, `GT`, `LT`, `GTE`, `LTE`, `IN`, `NOT_IN`, `EXISTS`, `NOT_EXISTS`.

## Components

### FlowComponent Interface

```kotlin
interface FlowComponent {
    val type: String
    suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult
}
```

Returns `ComponentResult.Success(outputs)` or `ComponentResult.Failure(reason, code, retryable)`.

### Built-in Components

| Type | Description |
|---|---|
| `DATA_FORM` | Dynamic form capture |
| `NIN_VERIFICATION` | Nigerian National ID verification |
| `BVN_VERIFICATION` | Bank Verification Number check |
| `FACE_MATCH` | Compare selfie to ID photo |
| `FACE_LIVENESS` | Facial liveness detection |
| `PASSPORT_SCAN` | Passport document OCR |
| `ID_VERIFICATION` | Generic ID document verification |
| `FINGERPRINT` | Biometric fingerprint capture |
| `PORTRAIT` | Selfie/portrait capture |
| `STATUS_AGGREGATOR` | Terminal node that aggregates upstream verdicts |

Custom components with the same `type` override built-ins.

## Requirements

- **Min SDK:** 29 (Android 10)
- **Compile SDK:** 36
- **Kotlin:** 2.0.21
- **Java:** 11

## Building

```bash
./gradlew :platformflow-core:build
```

Run the demo app:

```bash
./gradlew :app:installDebug
```

## Testing

```bash
./gradlew :platformflow-core:test
```

Tests cover the engine, data resolution, rule evaluation, session store, component registry, network layer, caching, and the public API facade. All tests use direct dependency injection with fakes — no mocking frameworks required.
