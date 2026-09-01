# Architecture notes

Why the system is put together the way it is. The [README](../README.md) covers how to run
it; this covers the decisions behind it.

## 1. Who owns what

The single most important boundary is between **the routing** and **the business record**.

| Concern | Owner |
|---|---|
| Which step a complaint is at, and where it goes next | the Camunda process instance |
| Who the customer is, what they complained about, what was answered, when, by whom | the `claims` table |
| Which agent may act on a task | the application's roles, mapped onto the model's candidate groups |
| Deadlines | computed by the application, written to both sides so they agree |

The application never second-guesses the engine's routing. When a task is completed, it
hands Camunda the gateway variables and lets Camunda decide the branch; it then updates its
own row to match. It can update the row without waiting because it knows the same
transition table the model was drawn from — and a reconciler catches the cases where the
two could still drift.

## 2. One transition table, three consumers

`ClaimWorkflow` states the process once, in Java:

```java
map.put(WorkflowStep.FRONT_OFFICE, Map.of(
        TaskDecision.ANSWER,   new Outcome(WorkflowStep.VALIDATION,    ClaimStatus.IN_VALIDATION),
        TaskDecision.ESCALATE, new Outcome(WorkflowStep.MIDDLE_OFFICE, ClaimStatus.IN_MIDDLE_OFFICE)));
```

Three things read it, so they cannot disagree:

1. **The API** — which decisions a step offers, and what a decision means for the claim.
   The completion form's buttons come from here, so the UI can only offer what the engine
   will accept.
2. **The simulator** — the in-memory engine evaluates the same conditions.
3. **The tests** — the transition table is asserted directly against the diagram.

A fourth consumer is the `.bpmn` file itself, and that one is checked rather than shared:
`BpmnModelConsistencyTest` parses the model on every build and fails if an element id, a
candidate group or a gateway condition no longer matches what the code assumes. That test
is the reason renaming a task in the Modeler cannot silently break the application.

## 3. The gateway seam

```
service layer  ──▶  CamundaGateway (9 methods)  ──┬──▶ CamundaClientGateway   → real cluster
                                                  └──▶ SimulatedCamundaGateway → in-memory
```

No class outside `ma.cdg.claims.camunda` imports anything from `io.camunda`. That was worth
enforcing for three reasons:

- **The product could be built and demonstrated before the cluster credentials existed.**
  The simulator is not a mock returning canned answers — it evaluates the real gateway
  conditions, creates tasks with the real candidate groups, and computes the real due
  dates. Switching to the cluster changes one setting.
- **The tests do not need infrastructure.** All 36 run in about twelve seconds with no
  database server and no Camunda.
- **Camunda's API is young and moving.** 8.8 unified the Zeebe, Tasklist and Operate APIs
  into one REST v2 surface; 8.10 added `businessId` on user tasks. Keeping the SDK behind
  an interface means a version bump touches one class.

## 4. Correlating a task back to a complaint

Each process instance is started with the complaint reference as its **business key**:

```java
camundaClient.newCreateInstanceCommand()
        .bpmnProcessId("reclamation-client-cdg")
        .latestVersion()
        .businessId(claim.getReference())      // REC-2026-000042
        .variables(variables)
        .execute();
```

Camunda 8.10 returns `businessId` on the user task itself, so joining a task to a complaint
is a lookup by reference rather than a scan. The process instance key is kept as a fallback
for instances started elsewhere (from the Modeler, say, during a demonstration).

Business data is also copied into process variables — subject, customer, category,
priority. It is denormalised on purpose: it makes an instance readable in **Operate**
without a second system to consult, which is what makes the process observable to the
department rather than only to the application.

## 5. Deadlines on both sides

A step's budget comes from `cdg.sla.steps`, scaled by the complaint's priority (urgent
×0.35, low ×1.5). When a task is completed, the deadline of the *next* step is computed and
sent as a process variable alongside the gateway condition:

```java
variables.put("slaMiddleOffice", sla.deadlineFor(MIDDLE_OFFICE, priority, now).toString());
```

The model reads it in the next task's `zeebe:taskSchedule`, so Camunda's due date and the
application's `sla_due_at` are the same instant. Computing it at completion time — rather
than all five at the start — is what makes the validation loop behave: a complaint sent back
to qualification gets a fresh deadline instead of inheriting an expired one.

The expressions in the model are written defensively:

```
=if is defined(slaMiddleOffice) then slaMiddleOffice else string(now() + duration("PT48H"))
```

so an instance started by hand from the Modeler, with no variables, cannot raise an
incident during a demonstration.

## 6. Keeping the projection honest

Completing a task is two writes that cannot be one transaction: a call to the engine, then
a row update. If the process crashed between them, or somebody completed a task directly in
Tasklist, the two would disagree.

`WorkflowSyncService` runs once a minute: it lists the open complaints, lists the engine's
open tasks, and realigns any complaint whose step, assignee or terminal state no longer
matches. Complaints whose instance has ended without the application noticing are closed;
complaints sitting at the wrong step are moved and given a fresh deadline. The same job
flags deadlines that have just passed and notifies the unit holding them.

This is the reason the engine call comes first and the row update second: a lost row update
is self-healing, whereas a row updated for a task the engine never accepted would not be.

## 7. Roles and queues

One role per user, mapped to exactly one candidate group:

| Role | Candidate group | Step |
|---|---|---|
| `QUALIFICATION` | `qualification` | Qualification |
| `FO` / `MO` / `BO` | `front-office` / `middle-office` / `back-office` | the three offices |
| `VALIDATION` | `validation` | Validation |
| `SUPERVISOR`, `ADMIN` | *(all)* | oversight |

The inbox is a user-task search filtered by the caller's visible groups — the engine
decides what they can see, not a `WHERE` clause on our own table. Authorisation is checked
again on every write: the step's role must be the caller's role, and a task held by someone
else cannot be completed by anyone but an oversight role.

Accounts are local to the application, with JWT bearer tokens. Camunda Identity / SSO would
be the next step for production; it would let a user's Camunda group membership drive their
queues directly instead of being mirrored here.

## 8. Choices worth naming

**Why not `camunda-spring-boot-starter`?** The 8.10 starter is compiled against Spring Boot
4 and brings `spring-boot-health:4.0.7` with it, which registers a bean the Boot 3.5
actuator already registers — the context refuses to start. The plain
`camunda-client-java` has no Spring dependency, so the client is built explicitly in
`CamundaConfiguration`. As a side effect the connection settings live in the application's
own `cdg.camunda.*` namespace, next to everything else, instead of a second one.

**Why H2 by default?** So `./mvnw spring-boot:run` works on any machine with nothing
installed — which matters for a demonstration on somebody else's laptop. PostgreSQL is one
profile away, with a Flyway migration generated from the JPA mapping and Hibernate set to
`validate`, so the schema is managed rather than guessed.

**Why hand-written SVG charts?** The dashboard needs six charts; a charting library would
have been the larger dependency and the harder thing to make consistent. The palette is
validated for colour-vision deficiency, single-series charts use one hue with the value
labelled at the end of each bar, and the two-series trend carries a legend — identity is
never conveyed by colour alone.

**Why is e-mail off by default?** Because the application will at some point be pointed at
real complaint data, and the first thing a misconfiguration would do is mail actual CDG
customers. It logs the message instead, which demonstrates the flow just as well.
