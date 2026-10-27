# Kingpay

Kingpay is a fictional superhero protection booking and wallet API. The planned flow creates a booking, collects both participants' completion confirmations, and lets the customer explicitly pay its fixed price. Standalone transfers have a separate permanent ledger.

**Current state: compiling skeleton.** Entity mappings, DTOs, draft HTTP routes, and unfinished service boundaries exist. Authentication, persistence operations, money movement, booking transitions, and account deactivation are not implemented. See [the phase status and endpoint inventory](docs/skeleton-status.md).

## Prerequisites and dependency installation

- JDK 21 and Maven 3.9 or later on `PATH`.
- Run commands from `kingpay/`, where `pom.xml` lives.
- MySQL and environment-backed credentials will be configured in Phase 1. No credentials or JWT keys are included.

Download dependencies and compile production and test sources:

```powershell
mvn -DskipTests test-compile
```

The existing POM's Spring Boot version is retained. Bean Validation is the only dependency added for this skeleton. MySQL/JWT/security/coverage setup remains in the owning phases.

## Verify the skeleton

```powershell
.\scripts\verify-skeleton.ps1
```

The script compiles with cached Maven dependencies and checks the entity, DTO, endpoint, and explicit-placeholder structure. It fails immediately when an assertion or compilation fails. This is a phase-specific scaffold check; update its placeholder expectations when implementing the corresponding phase.

`-DskipTests test-compile` compiles test sources; it does **not** execute tests. The original `KingpayApplicationTests.contextLoads()` is retained. Running the full suite currently requires the Phase 1 datasource setup:

```powershell
mvn test
```

No service/controller behavior tests, three-case-per-method documentation, or coverage results are claimed by this skeleton.

## Run the application

The startup command is:

```powershell
mvn spring-boot:run
```

Startup is not established yet. Spring Data JPA requires a datasource, and `application.properties` currently contains only the application name. Complete Phase 1's MySQL driver, database, and external credential configuration before expecting that command or the context-loading test to succeed. No database reset or seed operation is included.

## Minimal API example

Once startup is configured, this valid request reaches the explicit placeholder:

```powershell
'{"openingBalance":1000.00}' | curl.exe -i -X POST http://localhost:8080/api/v1/accounts `
  -H "Content-Type: application/json" `
  --data-binary '@-'
```

The planned account-creation response is HTTP 201. At this skeleton stage it returns **HTTP 501**:

```json
{"message":"Demo account creation is not implemented.","data":null}
```

There is no authentication enforcement yet; the future owner comes from the authenticated principal, never a supplied user ID. Invalid bodies can receive framework validation errors before reaching a stub. The final error contract belongs to the later implementation phases.
