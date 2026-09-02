# Local Camunda 8 cluster

A trimmed copy of Camunda's own
[official Docker Compose release for 8.9](https://github.com/camunda/camunda-distributions/releases/tag/docker-compose-8.9) —
just Zeebe + Operate + Tasklist (now one consolidated `camunda/camunda` image), no
Connectors/Identity/Keycloak/Optimize/Web Modeler, since this project doesn't use them.

See the root [`README.md`](../README.md#2-bis-running-camunda-8-locally-with-docker-no-saas-account-needed)
for how to start it and connect the backend.

## Files here

| File | Purpose |
|---|---|
| `docker-compose.yaml` | the two services: a one-shot volume-permission fix, then the orchestration container |
| `.env` | pins the image to `8.9.11`, matching `camunda-client-java` in `backend/pom.xml` exactly |
| `configuration/application-h2.yaml` | Camunda's own config: file-based H2 storage, unprotected API, seeds a `demo`/`demo` user for the web UIs |

## Switching to a different database

`application-h2.yaml` is the default. Camunda ships equivalent files for Postgres, MySQL,
MariaDB, SQL Server and Oracle in their release archive if you'd rather not use H2 — set
`ORCHESTRATION_CONFIG_FILE` in `.env` to point at one, add the corresponding service to
`docker-compose.yaml`, and drop the vendor's JDBC driver jar in a `driver-lib/` folder next
to this compose file (Postgres/MySQL... some are bundled already — see
[the driver support matrix](https://docs.camunda.io/docs/self-managed/concepts/databases/relational-db/rdbms-support-policy/#bundled-drivers)).
For a student project, H2 is simplest and is what this repo ships.

## Resetting

```bash
docker compose down -v   # -v also deletes the H2 data volume
docker compose up -d
```

## Troubleshooting

- **Nothing on :8085** — check `docker compose logs orchestration`; the container takes
  10-30s to report ready.
- **Backend logs "Could not build the Camunda client" and falls back to the simulator** —
  confirm the container is actually up (`docker compose ps`) and that nothing else on your
  machine is using ports 8085 or 26500.
- **Port already allocated** — something else (often a previous `docker compose up`
  that wasn't stopped) is holding 8085/26500/9600. `docker compose down` it, or
  `docker ps` to find the culprit.
- **Incident on a user task: `No variable found with name 'slaFrontOffice'`** (or any other
  `sla*` variable), usually alongside `Invalid date-time format '...@GMT'` from the fallback
  the missing variable falls through to. This was a bug in the process model, fixed by
  removing the `zeebe:ioMapping` outputs from the user tasks: Zeebe merges a completed task's
  variables into the process scope only while the task declares *no* output mapping, so the
  mappings were silently dropping every other variable the backend sends. Make sure the
  backend has redeployed the model (it does so on startup) and start a **new** complaint —
  instances already running stay on the old version.
