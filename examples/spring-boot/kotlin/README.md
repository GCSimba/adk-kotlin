# ADK Kotlin: Spring Boot WebFlux example in Kotlin

An order-support service written in Kotlin with Spring Boot and WebFlux. It
runs an ADK workflow, which combines Gemini agents, function nodes that run
business rules in code, and tools backed by a Spring bean, and serves it two
ways: a request that gets the finished reply, and a request that streams the
reply as server-sent events (SSE). It uses the Kotlin API directly:
KSP-generated tools, `node { }` function nodes, and coroutines end to end, as
[How it works](#how-it-works) describes.

```
START ─▶ triage ─▶ check_order
                     ├─ "tracking" ─▶ shipping ─────────────────────┐
                     ├─ "refund"   ─▶ refund_policy                 │
                     │                  ├─ true  ─▶ refunds ────────┤
                     │                  └─ false ─▶ refund_denied ──┤
                     └─ otherwise  ─▶ ask_for_order ────────────────┴─▶ reply
```

-   **triage**, an `LlmAgent`, classifies the message and extracts the order
    number as structured output.
-   **check_order**, a function node, takes that output, looks up the order in
    `OrderService`, and routes on the customer's intent. It passes on the
    order's number and item.
-   **refund_policy**, a function node, applies the refund policy in code and
    routes on the decision: `true` for an approved refund, `false` for a denied
    one.
-   **shipping** and **refunds**, `LlmAgent`s, write the reply, calling the
    `OrderService` functions annotated with `@Tool` to track the order or to
    create a return label.
-   **refund_denied** and **ask_for_order**, function nodes, reply without a
    model: the first explains a denied refund, and the second asks for an order
    number when there is no order or the request is unsupported.
-   **reply**, the function node that every branch leads to, returns the reply
    it receives as the workflow's output.

The code:

-   [`SupportWorkflowConfiguration.kt`](src/main/kotlin/com/google/adk/kt/examples/springboot/SupportWorkflowConfiguration.kt)
    defines the workflow as a Spring bean: the agents, the function nodes,
    and the routes.
-   [`OrderService.kt`](src/main/kotlin/com/google/adk/kt/examples/springboot/OrderService.kt)
    is the Spring bean the workflow uses: order lookups, the refund policy, and
    the `@Tool` functions.
-   [`SupportService.kt`](src/main/kotlin/com/google/adk/kt/examples/springboot/SupportService.kt)
    is the Spring `@Service` that runs the workflow, in a session of its own for
    each request.
-   [`SupportController.kt`](src/main/kotlin/com/google/adk/kt/examples/springboot/SupportController.kt)
    maps the two endpoints onto the service.
-   [`AdkConfiguration.kt`](src/main/kotlin/com/google/adk/kt/examples/springboot/AdkConfiguration.kt)
    defines the other Spring beans: the `Gemini` model, the `SessionService`,
    the `App`, and the `Runner`.
    [`application.yaml`](src/main/resources/application.yaml) sets the model and
    the Vertex AI project.
-   [`SupportControllerTest.kt`](src/test/kotlin/com/google/adk/kt/examples/springboot/SupportControllerTest.kt)
    tests both endpoints offline, with a scripted model in place of Gemini.

## Run

On Vertex AI, with Application Default Credentials:

```shell
gcloud auth application-default login
GOOGLE_CLOUD_PROJECT=my-project ./gradlew :google-adk-kotlin-examples-spring-boot-kotlin:bootRun
```

On the Gemini API, with `GOOGLE_CLOUD_PROJECT` unset:

```shell
GOOGLE_API_KEY=... ./gradlew :google-adk-kotlin-examples-spring-boot-kotlin:bootRun
```

The service listens on `localhost:8080`. `GOOGLE_CLOUD_LOCATION` defaults to
`global`. To try another model, change `adk.model` in `application.yaml` or pass
it on the command line, for example `--args='--adk.model=gemini-3.8-flash'`.

The sample orders in `OrderService` are 1042, which is still out for delivery;
1043, delivered 5 days ago; and 1044, delivered 45 days ago. Only 1043 can be
refunded, since the policy allows a refund within 30 days of delivery.

## Case 1: request and response

`POST /support` calls `SupportService.answer`, a `suspend` function that runs
the workflow and reads the reply from the `reply` node's output. It returns the
reply as a `SupportReply`, which WebFlux writes as JSON:

```shell
curl -s localhost:8080/support -H 'Content-Type: application/json' \
    -d '{"message": "Please refund order 1043, the machine arrived broken."}'
```

```json
{"text": "I am sorry to hear that your espresso machine arrived broken, and I can confirm that your refund for order 1043 has been approved. I have created a prepaid return label for you, and the label number is RL-1043. Please use this label to send the item back to us so we can process your refund."}
```

## Case 2: streaming with server-sent events

`POST /support/stream` calls `SupportService.streamAnswer`, which runs the
workflow and returns a `Flow<AgentMessage>`. The controller sends each message
as an SSE event as soon as it is ready. Each message has a `type`:

-   `progress`: a message that a workflow node publishes while it works, such as
    `check_order`'s "Found order 1042 (noise-cancelling headphones)." or
    `refund_policy`'s "Checking the refund policy...".
-   `reply`: the whole reply from the `reply` node's output, which is the text
    that `POST /support` returns.

The service forwards every partial event as progress without knowing which node
published it, since no model streams tokens and the nodes' messages are the only
partial events:

```shell
curl -N localhost:8080/support/stream -H 'Content-Type: application/json' \
    -d '{"message": "Where is my order 1042?"}'
```

```
data:{"type":"progress","text":"Found order 1042 (noise-cancelling headphones)."}

data:{"type":"progress","text":"Checking the carrier's tracking..."}

data:{"type":"reply","text":"Your order #1042 was last scanned in Brooklyn, NY and is currently out for delivery with Example Express. It is estimated to arrive today by 8 pm."}
```

## How it works

-   **Refund policy in code.** A model never decides a refund: `refund_policy`
    asks `OrderService` for the decision, so the refunds agent runs only for an
    approved refund, and `refund_denied` explains a denial without a model call.
    `createReturnLabel` checks the policy again, so a model cannot create a
    label that the policy forbids.
-   **Conditional routing.** A function node picks the next node by setting
    `context.routes`: `check_order` emits the customer's intent as a string, and
    `refund_policy` emits the decision as a boolean. Each `route { }` block maps
    those routes to nodes, and `otherwise` runs `ask_for_order` when no route
    matches or `check_order` emits none.
-   **Spring beans as tools.** At build time, the KSP processor
    (`google-adk-kotlin-processor`) generates a tool class for each
    `OrderService` function annotated with `@Tool`, such as `TrackShipmentTool`,
    which calls the bean it is given. The function's KDoc becomes the tool's
    description, and each `@Param` describes an argument.
-   **Node outputs, not session state.** Each `LlmAgent` node sets `mode` to
    `AgentMode.SINGLE_TURN`, the default for an agent with no parent or
    sub-agents: its input is its last user message, and its final answer is its
    output. `check_order` receives the triage agent's `outputSchema` answer as a
    `Triage` data class, so it routes on fields rather than on free text. It
    then passes on the order as an `OrderFacts` data class, which
    kotlinx.serialization writes as JSON for the next agent. The shipping and
    refunds agents set `includeContents` so they also see the customer's
    message.
-   **One final node.** Every branch ends at `reply`, which passes on its String
    input: a single-turn agent outputs the text of its final answer, and the
    function nodes return Strings. The service reads the reply from that node's
    output alone, which it finds by `Event.nodeName`.
-   **Progress from the nodes.** A `node { }` body can emit events as well as
    return its output. `check_order` and `refund_policy` emit partial message
    events that say what they found and what happens next. The runner streams
    partial events to the caller but never stores them, so no agent sees them
    in its history.
-   **Function nodes as lambdas.** `node` builds a `FunctionNode` from a
    suspending lambda.
-   **Non-blocking runs.** The service calls `Runner.runAsync` directly. It
    returns a cold `Flow<Event>`, which the service collects inside the
    `suspend` handler or the `Flow` that WebFlux streams, so no thread blocks
    while the agents work. When a client disconnects, WebFlux cancels the
    coroutine, which cancels the run and its model calls.
-   **One session per request.** The service calls the `suspend`
    `SessionService` directly: it creates a session, runs the workflow in it,
    and deletes it in a `finally` block when the run completes, fails, or is
    canceled. The deletion runs in `NonCancellable`, so it completes even when
    a canceled request triggers it.
-   **Model errors.** A model call that does not finish normally, such as a
    blocked reply, arrives as an event with an error code rather than as an
    exception. An exception from the model API, such as a quota error, also
    arrives as an error event first: the workflow emits one for the failed node
    and rethrows the exception only when the run ends. The service fails the run
    with an `AgentFailedException` at the first error event, as it does when a
    run ends without a reply. The controller answers `POST /support` with 502
    Bad Gateway; the stream has already sent its 200 status, so it ends with an
    `error` event instead.
-   **Spring's Kotlin support.** The `kotlin("plugin.spring")` compiler plugin
    opens the Spring-annotated classes, which Kotlin makes final.
    `jackson-module-kotlin` reads JSON into data classes, and Spring needs
    `kotlin-reflect` and `kotlinx-coroutines-reactor` to call `suspend` handlers
    and stream `Flow` responses.
-   **Spring Boot dependency versions.** The build imports the Spring Boot BOM
    with Gradle's `platform()`, where the highest requested version of a library
    wins. The `io.spring.dependency-management` plugin would instead force the
    BOM's versions, such as an older `kotlinx-coroutines` than the ADK needs.

The workflow API is experimental (`@ExperimentalWorkflowApi`) and may change.
The endpoints have no authentication, so the service binds loopback; put
authentication in front of it before setting `server.address` to expose it.
