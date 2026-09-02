# CDG — Customer Claims Management

A complaint-handling system for **Caisse de Dépôt et de Gestion**, built on the BPMN
process `reclamation-client-cdg` deployed to **Camunda 8**.

Camunda owns the routing; this application owns the business data around it: it registers
complaints, starts a process instance for each one, serves every user the tasks their unit
is responsible for, records what was decided at each step, and reports on the whole thing.

```
Angular 20 (SPA)  ──HTTP/JWT──▶  Spring Boot 3.5 API  ──REST v2──▶  Camunda 8 cluster
                                       │                              (Zeebe / Tasklist / Operate)
                                       ├──▶ PostgreSQL / H2   (complaints, audit trail, users)
                                       └──▶ classification service (FastAPI, optional)
```

---

## 1. Quick start

The application runs with **no infrastructure at all** — no database server, no Camunda
credentials. In that mode a built-in engine replays the same BPMN in memory, so every
screen works and the whole flow can be demonstrated.

```bash
# Terminal 1 — API on http://localhost:8080
cd backend
./mvnw spring-boot:run

# Terminal 2 — UI on http://localhost:4200
cd frontend
npm install
npm start
```

Open <http://localhost:4200> and sign in. Nine sample complaints, spread across every
stage of the process, are created on first start.

| Username     | Role               | Sees                                     |
|--------------|--------------------|------------------------------------------|
| `qualif1`    | Qualification      | complaints waiting to be qualified       |
| `fo1`        | Front Office       | the Front Office queue                   |
| `mo1`        | Middle Office      | the Middle Office queue                  |
| `bo1`        | Back Office        | the Back Office queue                    |
| `valid1`     | Validation         | answers waiting for approval             |
| `supervisor` | Supervisor         | every queue, read/act across the process |
| `admin`      | Administrator      | everything, plus administration          |

Password for all of them: `Cdg@2026` (change `cdg.demo.password`, or set
`cdg.demo.seed-users=false`, before any real deployment).

Also available: the API documentation at <http://localhost:8080/swagger-ui.html>.

---

## 2. Connecting the real Camunda 8 cluster

1. In the **Camunda Console**, open your cluster (`Rec_clients_CDG`) → **API** →
   **Create a new client**, with the Zeebe, Tasklist and Operate scopes.
2. Export the generated values — never commit them:

   ```bash
   export CAMUNDA_CLUSTER_ID=07a7b6da-a3d5-48d1-a677-f4d8fbb1750f
   export CAMUNDA_REGION=bru-2          # shown next to the cluster id in the Console
   export CAMUNDA_CLIENT_ID=...
   export CAMUNDA_CLIENT_SECRET=...
   ```

3. Start the backend with the `camunda` profile:

   ```bash
   cd backend
   SPRING_PROFILES_ACTIVE=camunda ./mvnw spring-boot:run
   ```

On startup the application deploys `bpmn/reclamation-client-cdg.bpmn` to the cluster, so a
fresh environment is usable without opening the Modeler. From then on every complaint
becomes a real process instance, visible in **Operate**, with its user tasks in
**Tasklist**.

The switch is one setting (`cdg.camunda.enabled`). Nothing else in the application changes
— see [§6](#6-how-the-two-engines-are-interchangeable).

> If the credentials are missing or wrong, the application logs the problem and falls back
> to the simulator rather than refusing to start. The **Administration** screen always
> shows which engine is actually in use.

---

## 2 bis. Running Camunda 8 locally, with Docker (no SaaS account needed)

Prefer to see complaints show up in a real Camunda engine — Operate, Tasklist, the token
moving through the diagram in Modeler — without waiting for SaaS credentials? Run a local
Self-Managed cluster with Docker.

**Requirements:** [Docker Desktop](https://www.docker.com/products/docker-desktop/) running.

```bash
cd camunda
docker compose up -d
docker compose logs -f orchestration     # wait for a line ending in "Broker is ready"
```

This starts Zeebe + Operate + Tasklist as a single container (`camunda/camunda`, the
consolidated image Camunda ships as of 8.9), storing its data in a local H2 file — no
Elasticsearch, no Keycloak, nothing else to run. It comes straight from Camunda's own
[official Docker Compose release for 8.9](https://github.com/camunda/camunda-distributions/releases/tag/docker-compose-8.9),
trimmed to just the pieces this project uses.

Then point the backend at it instead of the simulator:

```bash
cd ../backend
SPRING_PROFILES_ACTIVE=selfmanaged ./mvnw spring-boot:run
```

That's it — no client id, no secret. The bundled config runs with an unprotected API (see
`camunda/configuration/application-h2.yaml`), so the backend connects with nothing but the
two addresses in `application-selfmanaged.yml`. Every complaint registered in the app from
now on is a real process instance:

- **Operate** — http://localhost:8085/operate — watch the token move through the diagram
- **Tasklist** — http://localhost:8085/tasklist — see the same tasks the Angular app shows
- **Admin** — http://localhost:8085/admin

Log in to any of them with `demo` / `demo`.

> **Why port 8085 and not the 8080 shown in Camunda's own docs?** This project's own
> backend already listens on 8080. `camunda/docker-compose.yaml` only remaps the *host*
> side of the port (`8085:8080`) — inside the container it's still 8080, so anything from
> Camunda's docs that references paths like `/operate` or `/v2/...` still works, just at
> `localhost:8085` instead of `:8080`.

**Shut it down** with `docker compose down` (add `-v` to also delete the H2 data volume and
start fresh next time). **Version note:** the compose file is pinned to `camunda/camunda:8.9.11`
to exactly match `camunda-client-java` in `backend/pom.xml` — Camunda only publishes a Docker
image for select patch releases, so this is not simply "the latest 8.9.x".

---

## 3. The process

The model is `backend/src/main/resources/bpmn/reclamation-client-cdg.bpmn`, drawn exactly
as validated with the department:

```
                                        ┌── no ──▶ Rejected & customer notified
Complaint received ──▶ Qualification ──▶┤
                            ▲            └── yes ─▶ Front Office ──┐
                            │                                      │ can answer? yes ─────┐
                            │                          no ─▶ Middle Office ──┐            │
                            │                                      │ can answer? yes ─────┤
                            │                          no ─▶ Back Office ────────────────▶│
                            │                                                             ▼
                            └────────── not validated ◀────────────────────────────  Validation
                                                                                          │
                                                                       validated ─────────▶ Notify & close
```

| Step             | BPMN element id      | Candidate group | Decisions offered            |
|------------------|----------------------|-----------------|------------------------------|
| Qualification    | `Task_Qualification` | `qualification` | qualify and forward · reject |
| Front Office     | `Task_TraitementFO`  | `front-office`  | answer · escalate            |
| Middle Office    | `Task_TraitementMO`  | `middle-office` | answer · escalate            |
| Back Office      | `Task_TraitementBO`  | `back-office`   | answer                       |
| Validation       | `Task_Validation`    | `validation`    | approve and close · return   |

The gateway conditions read four variables, which the application writes when a task is
completed: `qualificationDecision`, `foCanAnswer`, `moCanAnswer`, `validationDecision`.

**The element ids above are a contract.** They are declared once in
`WorkflowStep.java`, and `BpmnModelConsistencyTest` parses the `.bpmn` file on every build
to check that the tasks, their candidate groups and the gateway conditions still match. If
somebody renames a task in the Modeler, the build fails instead of the application quietly
losing track of its own tasks.

---

## 4. What the application does

**Registering a complaint** — one form captures the customer, the channel and the wording.
As it is typed, the classification model suggests a category; the agent keeps the last
word. Saving the complaint and starting the process instance happen in one transaction, so
the database never holds a complaint that no process is driving. The reference
(`REC-2026-000042`) is the process instance's business key, which is what ties a Camunda
task back to a complaint.

**Working a task** — each user's inbox is built from the engine's own view of the open user
tasks, filtered by the candidate group their role belongs to. Tasks can be taken, released,
and completed. The decisions the form offers come from the backend, which derives them from
the transition table — so the buttons on screen can never diverge from what the engine will
accept. The Front Office is not shown a "reject" button, and the Back Office is not offered
an escalation it has nowhere to send.

**Following a complaint** — every complaint has a stepper showing how far it has travelled,
a full timeline of who did what and when, the live process variables, and a downloadable
PDF dossier.

**Deadlines** — each step has a budget (`cdg.sla.steps`), shortened for urgent complaints
and lengthened for low-priority ones. The deadline is written both on the complaint and as
the process variable the model's `zeebe:taskSchedule` reads, so Camunda's own due date and
the application's agree. A scheduled job flags the ones that slip and warns the unit
holding them.

**Reporting** — a dashboard of volumes, categories, channels, per-unit workload, SLA
compliance and a 30-day trend, plus Excel and CSV exports of any filtered list.

**Notifications** — in-app when work arrives, a deadline is missed, or a complaint the user
registered is closed; by e-mail to the customer on closure or rejection (off by default, so
nothing is sent by accident — set `cdg.mail.enabled=true`). The closing letter carries the
answer the unit wrote, in the CDG colours, as HTML with a plain-text alternative;
`docker compose up -d` starts Mailpit on <http://localhost:8025>, which catches every
message so you can read what a customer would receive.

---

## 5. Layout

```
backend/                       Spring Boot 3.5 · Java 21
  src/main/java/ma/cdg/claims/
    domain/                    entities, enums, and ClaimWorkflow — the transition table
    camunda/                   the only code that knows the Camunda SDK exists
    service/                   claims, tasks, SLA, notifications, exports, prediction, sync
    security/                  JWT, roles, candidate-group mapping
    web/                       REST controllers, DTOs, problem-detail error handling
  src/main/resources/
    bpmn/                      the deployed process model
    db/migration/              Flyway schema for PostgreSQL
frontend/                      Angular 20 · standalone components · signals
  src/app/core/                auth, HTTP client, models, toasts
  src/app/shared/              chips, pipes, charts
  src/app/features/            login, dashboard, claims, tasks, notifications, admin
ml-service/                    FastAPI wrapper around the classification model
docs/                          architecture notes
```

---

## 6. How the two engines are interchangeable

Everything above the `CamundaGateway` interface is written against nine methods: start an
instance, search user tasks, get one, assign, unassign, complete, cancel, read variables,
deploy. There are two implementations:

- `CamundaClientGateway` — the real cluster, over the Camunda 8 REST v2 API.
- `SimulatedCamundaGateway` — an in-memory engine that evaluates the same gateway
  conditions and produces tasks with the same candidate groups and due dates.

The simulator is not a stub that returns canned answers; it replays the model. That is what
lets the whole product be demonstrated before the credentials exist, and it is what the
36 automated tests run against, so the test suite needs no cluster. Its state is rebuilt
from the database on restart, so a demonstration survives a restart.

`WorkflowSyncService` closes the remaining gap when a real cluster is connected: once a
minute it compares the open complaints with the engine's open tasks and realigns anything
that drifted — a crash between the two writes, or somebody driving an instance from Operate
or Tasklist.

> **Note on the Camunda dependency.** The project uses `io.camunda:camunda-client-java`
> directly rather than `camunda-spring-boot-starter`. The 8.10 starter is built against
> Spring Boot 4 and pulls `spring-boot-health:4.x` onto the classpath, which collides with
> the Boot 3.5 actuator and prevents the context from starting. The plain client has no
> Spring dependency at all, so the `CamundaClient` bean is built explicitly in
> `CamundaConfiguration` from the `cdg.camunda.*` settings.

---

## 7. Running against PostgreSQL

H2 (a file under `backend/data/`) is the default so the project runs anywhere. For a real
database:

```bash
docker compose up -d db
cd backend
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
```

The `postgres` profile runs Flyway (`db/migration/V1__initial_schema.sql`) and sets
Hibernate to `validate`, so the schema is managed by migrations rather than guessed at
startup. `docker compose up -d` also starts Mailpit (<http://localhost:8025>) to catch
customer e-mails, and the classification service on port 8000.

---

## 8. The classification model

The categoriser built during the first month of the internship is served by
`ml-service/` and called by the backend when `ML_ENABLED=true`:

```bash
cd ml-service && pip install -r requirements.txt && uvicorn main:app --port 8000
cd backend && ML_ENABLED=true ./mvnw spring-boot:run
```

Export the trained pipeline to `ml-service/model/claim_classifier.joblib` — see
[`ml-service/README.md`](ml-service/README.md) for the label set it must produce. The
backend degrades gracefully: if the service is down, slow or returns a category it does not
recognise, registration falls back to keyword rules and the response says which of the two
answered.

---

## 8 bis. The per-unit assistant

A floating **Assistant** button sits in the corner of every page. It follows the unit
around and advises on whichever complaint is open — what the customer is actually asking for, what is missing before a decision,
or a draft of the answer to send. A drafted answer can be carried straight into the
completion dialog with one click.

It is scoped, not general: the server decides which unit is asking from the session rather
than the request, and tells the model only the decisions that unit may actually take —
read from the same transition table the buttons come from. So the Back Office is never
coached towards an escalation it has nowhere to send, and the advice cannot drift from what
the engine will accept. The complaint's own record is the only source of fact, and it is
handed over fenced off and labelled as data: the wording is the customer's, and nothing
inside it can redirect the assistant.

```bash
cd backend
ASSISTANT_ENABLED=true GROQ_API_KEY=gsk_... ./mvnw spring-boot:run
```

Which models a key can reach varies by account, and Groq's catalogue changes. If the panel
reports that the model does not exist, ask the key what it actually has and set
`ASSISTANT_MODEL` to one of them:

```bash
curl -H "Authorization: Bearer $GROQ_API_KEY" https://api.groq.com/openai/v1/models
```

Backed by [Groq](https://console.groq.com/keys), over its OpenAI-compatible chat
completions endpoint — no SDK, a plain REST call, and a free tier that is enough to
demonstrate this. Off by default. Without a key the panel does not appear at all, and
every failure — an unreachable API, a rejected key — degrades to a line in the panel
rather than an error page. The unit can always work without it.

---

## 9. Configuration

Every setting lives under `cdg.*` in `backend/src/main/resources/application.yml`, and each
one can be overridden by an environment variable.

| Setting | Default | Purpose |
|---|---|---|
| `cdg.camunda.enabled` | `false` | connect a real cluster instead of the simulator |
| `cdg.camunda.mode` | `SAAS` | `SAAS` or `SELF_MANAGED` |
| `cdg.camunda.cluster-id` / `.region` | — | SaaS cluster coordinates |
| `cdg.camunda.client-id` / `.client-secret` | — | OAuth client credentials |
| `cdg.workflow.process-id` | `reclamation-client-cdg` | the process to start instances of |
| `cdg.workflow.deploy-on-startup` | `true` | deploy the packaged model on startup |
| `cdg.workflow.sync-interval` | `60s` | how often the projection is reconciled |
| `cdg.sla.steps.*` | 8h / 24h / 48h / 72h / 24h | deadline budget per step |
| `cdg.sla.warning-threshold` | `0.75` | when a deadline starts showing as "due soon" |
| `cdg.ml.enabled` | `false` | call the classification service |
| `cdg.assistant.enabled` | `false` | offer the per-unit assistant on a complaint |
| `cdg.assistant.api-key` | — | Groq API key (`GROQ_API_KEY`) |
| `cdg.assistant.model` | `openai/gpt-oss-120b` | model behind the assistant |
| `cdg.mail.enabled` | `false` | actually send customer e-mails |
| `cdg.jwt.secret` | dev value | **override in every environment** (≥ 32 characters) |
| `cdg.demo.seed-users` / `.seed-claims` | `true` | create the demo accounts and complaints |

---

## 10. Tests

```bash
cd backend && ./mvnw test        # 36 tests
cd frontend && npm run build     # type-checks and bundles the SPA
```

| Suite | What it protects |
|---|---|
| `ClaimWorkflowTest` | the transition table matches the diagram, including what each step may *not* do |
| `BpmnModelConsistencyTest` | the `.bpmn` file still matches the element ids, candidate groups and conditions the code assumes |
| `SimulatedCamundaGatewayTest` | the in-memory engine routes exactly like the model, on every path |
| `ClaimLifecycleIntegrationTest` | complaints walked end to end through the API: happy path, full escalation, rejection, the validation loop, role enforcement, cancellation, inbox scoping |

---

## 11. Ideas worth adding next

- **Attachments** on a complaint (the customer's letter, the proof of payment).
- **A DMN decision table** for qualification, so the admissibility rules become a business
  artefact the department can edit in the Modeler instead of a decision an agent makes.
- **Camunda Identity / SSO** instead of application-local accounts, so a user's Camunda
  groups drive their queues directly.
- **Reassignment and delegation** between agents of the same unit.
- **A customer-facing tracking page**, where the reference alone shows the current stage.
- **Retraining the classifier** on the complaints the application itself collects — the
  suggested-versus-corrected category is already recorded on every complaint, which is
  exactly the labelled data a second iteration of the model needs.
