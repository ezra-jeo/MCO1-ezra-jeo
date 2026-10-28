# Kingpay

Kingpay is a fictional superhero protection booking and wallet API. A hero publishes one flat rate; a customer submits a protection request at that price. Provider acceptance creates the booking. Both participants later confirm completion before the customer explicitly pays its preserved price. Wallets start at zero and can receive simulated loads. Standalone transfers have a separate permanent ledger.

**Current state: Phases 2-7 and R1 implemented and verified.** R1 adds published flat rates and private accept/decline requests. See the [R1 report](docs/r1-report.md), [R1 acceptance cases](docs/test-cases/r1.md), [endpoint inventory](docs/skeleton-status.md) and root `plan.md`. Earlier phase reports are historical checkpoints. Recording, repository access and submission remain Phase 8; the professor's standout-feature decision remains open.

## Prerequisites and dependency installation

- JDK 21 and Maven 3.9 or later on `PATH`.
- Run commands from `kingpay/`, where `pom.xml` lives.
- A running MySQL server, a project database, and a MySQL user with access to it are needed for application startup. Enter local credentials in the ignored `.env` file described below.

Download dependencies and compile production and test sources:

```powershell
mvn -DskipTests test-compile
```

The existing POM's Spring Boot version is retained. Bean Validation, MySQL Connector/J, Spring Security and its JWT resource-server components are included. JaCoCo generates coverage during Maven verification.

## MySQL configuration

Create `kingpay/.env` from `.env.example` if it does not already exist. Fill in these values locally:

| Variable | Meaning |
| --- | --- |
| `DB_URL` | JDBC URL such as `jdbc:mysql://localhost:3306/kingpay`; database must already exist |
| `DB_USERNAME` | MySQL user with access to the project database |
| `DB_PASSWORD` | That user's password; keep it in `.env` |
| `DB_DDL_AUTO` | `validate` checks existing tables; `update` creates/updates mapped tables only in a confirmed dedicated development database |
| `JWT_SECRET` | Base64-encoded cryptographically random signing key, at least 32 bytes before encoding; never commit or log it |
| `JWT_ISSUER` | Expected token issuer; defaults to `kingpay` |
| `JWT_TTL_SECONDS` | Positive access-token lifetime; defaults to `3600` |

`application.properties` imports the working directory's `.env` using Spring Boot's native properties-file support. Run Maven from `kingpay/`. Values are unquoted Java properties values: backslashes must be escaped as `\\`, and quotes become part of the value. Credentials belong in their separate fields, not inside the JDBC URL. OS environment variables can also supply these keys. See [Spring Boot externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html#importing-extensionless-files).

The default schema action is `validate`. A fresh empty database needs initial table creation before validation can pass; choose `update` only for the confirmed development schema. No database deletion, reset, or `create-drop` behavior is configured. Both `.env` and local `.env.*` variants are ignored by Git; `.env.example` contains no real credentials.

For a fresh local setup, create a dedicated schema in MySQL Workbench or your MySQL client:

```sql
CREATE DATABASE kingpay CHARACTER SET utf8mb4;
```

Copy `.env.example` to `.env`, enter its database credentials, generate the signing key below, then build with `mvn -DskipTests package`. On this new schema, initialize once with `java -jar target/kingpay-0.0.1-SNAPSHOT.jar --spring.jpa.hibernate.ddl-auto=update`. Stop with Ctrl+C and restart normally with `java -jar target/kingpay-0.0.1-SNAPSHOT.jar`; startup now validates the existing tables. Keep `.env` at `DB_DDL_AUTO=validate`. Tests and API demonstration commands below use this normal configuration.

After copying `.env.example`, generate its signing key locally without printing it:

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $jwtRandom.GetBytes($jwtBytes) } finally { $jwtRandom.Dispose() }
$jwtEnvPath = Join-Path $pwd '.env'
$jwtEnvText = [IO.File]::ReadAllText($jwtEnvPath)
$jwtEnvText = $jwtEnvText.Replace('JWT_SECRET=REPLACE_WITH_BASE64_RANDOM_KEY',
    'JWT_SECRET=' + [Convert]::ToBase64String($jwtBytes))
[IO.File]::WriteAllText($jwtEnvPath, $jwtEnvText)
```

This replaces only the example placeholder and leaves an existing configured key intact. Invalid/missing keys prevent startup. Replacing the configured key invalidates existing tokens.


## Existing-database R1 migration

For an existing four-table database, use the additive migration before starting the new build with `validate`. Run from `kingpay/`; the explicit catalog must match `DB_URL`:

```powershell
$mysqlDriver = Get-ChildItem "$env:USERPROFILE/.m2/repository/com/mysql/mysql-connector-j" -Recurse -Filter '*.jar' |
  Sort-Object FullName -Descending | Select-Object -First 1
java --class-path $mysqlDriver.FullName scripts/MigrateRates.java apply kingpay
java --class-path $mysqlDriver.FullName scripts/MigrateRates.java verify kingpay
```

The launcher reads external credentials privately and creates only `hero_rates`, `protection_requests` and the nullable unique booking-origin association. It fingerprints original values before/after, does not rewrite rows or opening balances, and supports retry after partial DDL. Pause concurrent writes while comparing fingerprints. It is deliberately scoped to this schema revision, rather than a general migration framework. Existing bookings keep null origins and remain completable/payable. Rollback to the previous application retains the expanded schema and new history; do not drop the new tables/column. See [migration evidence](docs/evidence/r1-migration-before.txt) and the [report](docs/r1-report.md).

## Verify the skeleton

```powershell
.\scripts\verify-skeleton.ps1
```

The script compiles with cached Maven dependencies and checks the entity, DTO, endpoint, and explicit-placeholder structure. It fails immediately when an assertion or compilation fails. This is a phase-specific scaffold check; update its placeholder expectations when implementing the corresponding phase.

`-DskipTests test-compile` compiles test sources; it does **not** execute tests. The original `KingpayApplicationTests.contextLoads()` verifies startup against MySQL. Running the full suite requires a valid `.env` and a reachable database with the expected schema:

```powershell
mvn test
```

The full suite passes **674 tests** with zero failures/errors/skips. This includes real MySQL rollback and committed concurrency evidence for transfers and booking payments. See the [Phases 6–7 report](docs/phase-6-7-report.md) and [initial MySQL setup evidence](docs/mysql-setup.md).

Registration and [Phases 2–7](docs/test-cases/phase-7.md) have documented executable cases. The [method inventory](docs/method-cases.tsv) maps every declared service/controller method, including private/static helpers, to at least three distinct cases.

## Coverage and documentation verification

```powershell
mvn clean verify
.\scripts\verify-documentation.ps1
```

Open `target/site/jacoco/index.html` for coverage and `target/apidocs/index.html` for generated Javadoc. HTML, XML and CSV coverage are generated by the pinned [JaCoCo Maven plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html). First use downloads dependencies; later runs can use `mvn -o clean verify` once cached. No coverage threshold or exclusions are configured. Observed coverage: 743/786 production lines (94.53%); service/controller lines are 100% (428/428). Service branches are 185/188 (98.40%); These percentages describe this run, not a rubric requirement or proof of correctness.

The documentation script parses Java source with the JDK, checks method contracts and all 76 service/controller case mappings against passing Surefire results, validates every native UML reference and all 65 source types' declared fields/signatures, and generates Javadoc with malformed-documentation warnings treated as errors. Run `scripts/update-uml.ps1` after changing signatures, then rerun verification. See the [UML source map](docs/uml-source-map.md). Generated `target/` reports are ignored by Git and regenerated locally.

The isolated account entity suite can run before datasource setup:

```powershell
mvn -Dtest=AccountTest test
```

Its [13 documented cases](docs/test-cases/account.md) cover debit/credit success, boundary amounts, rejected inputs, and unchanged balances after failure. These entity cases supplement the course requirement; every service/controller method still needs at least three documented unique cases.

The booking suite adds [22 documented cases](docs/test-cases/booking.md) for confirmation ordering/idempotency and payment eligibility, matching, and duplicate rejection. Run both suites in PowerShell:

```powershell
mvn '-Dtest=AccountTest,BookingTest' test
```

The booking fixtures simulate persisted account IDs without starting a database. Accessor-only classes do not receive tests that merely repeat their getters.

The [repository query inventory and 21 MySQL cases](docs/test-cases/repositories.md) cover identity/ownership lookups, superhero browsing, transfer history, booking access, and outstanding work. Once all production sources compile, run them against the configured database:

```powershell
mvn -Dtest=RepositoryQueriesTest test
```

Repository test inserts are transactional and rolled back. All 21 repository cases have passed against MySQL. Registration persistence tests separately verify completed service transactions and remove only their uniquely named fixture records afterward.

Registration uses a named unique index, `uk_users_username`, to distinguish username conflicts from other integrity failures. The existing development index was renamed without changing data or uniqueness. Forward/reverse SQL and compatibility notes are in the [registration case inventory](docs/test-cases/registration.md). `PasswordEncoder` now uses BCrypt cost 12 and verifies existing jBCrypt hashes. Registration accepts 8–72 characters, additionally limited to 72 UTF-8 bytes; multibyte characters can reach the byte limit sooner.

## Run the application

The startup command is:

```powershell
mvn spring-boot:run
```

MySQL connectivity, schema validation and executable startup were verified against `kingpay`. Normal starts use `DB_DDL_AUTO=validate`; the six mapped tables exist after the R1 migration. The current working database has already been migrated without changing original rows.

## Minimal API example

Register a test identity (use a new username if it already exists):

```powershell
'{"username":"example-customer","password":"demo-password-123","fullName":"Demo Customer","phone":"000","email":"demo@example.test","isSuperhero":false}' |
  curl.exe -i -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" --data-binary '@-'
```

Login and use its bearer token with PowerShell:

```powershell
$loginBody = @{ username = 'example-customer'; password = 'demo-password-123' } | ConvertTo-Json
$loginResponse = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post `
  -ContentType 'application/json' -Body $loginBody
$authHeaders = @{ Authorization = 'Bearer ' + $loginResponse.data.token }
Invoke-WebRequest http://localhost:8080/api/v1/accounts/me -Headers $authHeaders
```

Registration returns 201, login returns 200, and the authenticated account lookup returns 200 with its zero balance. Omitting the bearer returns 401. A typical authentication failure is:

```json
{"message":"Authentication required or invalid.","data":null}
```

The authenticated principal supplies caller identity. Tokens use a stable user ID, validated issuer/expiry and HS256 signature; every bearer request reloads current user name, active state and roles. Inactive users cannot log in or use existing tokens. There is no refresh-token or logout endpoint. Wrong credentials receive a generic 401; cross-owner actions receive 403; validation errors use the same `message`/`data` envelope with safe field descriptions.

Load the authenticated owner's wallet with an exact positive amount:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/accounts/me/load -Method Post `
  -Headers $authHeaders -ContentType 'application/json' -Body '{"amount":1000.00}'
```

Each successful request adds that amount; retrying a successful request adds it again. This is simulated project funding with no external payment provider or transfer-ledger row. Amounts must fit 17 integer and 2 fractional digits; resulting balances must also fit. No rounding or currency conversion occurs; one implicit project currency is used.

Profile routes are `GET/PUT/DELETE /api/v1/users/me`. PUT fully replaces username, fullName, phone, email and isSuperhero; server-owned fields cannot be changed through that body. Unknown JSON fields are ignored. DELETE freezes the user only when the wallet is zero and no outstanding booking exists in either role; it retains wallet, booking and ledger records. State conflicts return 409. Inactive users' tokens are rejected with 401.

`GET /api/v1/users/superheroes` returns active eligible heroes with a published rate. Each summary includes the hero user `id`, `accountId`, `rateId` and `amount`. Protection requests select the hero by user ID. Contact details and balances are omitted. Removing provider eligibility while assigned work is unfinished/unpaid returns 409.

Registration already creates a wallet. `POST /api/v1/accounts` creates a zero wallet for an accountless user and returns 409 for an existing wallet; it accepts no opening-balance input.

## Prepare repeatable demo participants

With the application running, execute:

```powershell
.\scripts\prepare-demo.ps1
```

The script prepares `demo-customer` at **1000.00** and `demo-superhero` at **250.00**, and publishes the hero's flat rate at **100.00**, using real API calls. Missing credentials are generated into ignored `kingpay/.env.demo`; the script prints only usernames, wallet IDs and balances. You can supply `KINGPAY_DEMO_PASSWORD` instead. Read your local secret file privately when preparing Postman; keep passwords/tokens out of screenshots and source control.

Sequential reruns load only the difference to the target. A balance above target, an inactive identity, incompatible eligibility, an existing different rate or an existing different password fails without resetting the account. Run the script sequentially. It targets localhost by default; `-BaseUrl` selects a different local server port.

## Standalone transfer and private history

After authenticating and loading the example user's wallet above, prepare the demo users so a recipient is available. Use its public provider account ID; the caller supplies no identity credentials in the transfer body:

```powershell
$sourceWallet = Invoke-RestMethod http://localhost:8080/api/v1/accounts/me -Headers $authHeaders
$heroList = Invoke-RestMethod http://localhost:8080/api/v1/users/superheroes -Headers $authHeaders
$recipientAccountId = $heroList.data | Where-Object { $_.username -eq 'demo-superhero' } |
  Select-Object -First 1 -ExpandProperty accountId
if (-not $recipientAccountId) { throw 'Run scripts/prepare-demo.ps1 first.' }
$transferBody = @{ fromId = $sourceWallet.data.id; toId = $recipientAccountId; amount = [decimal]100.00 } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/v1/transfers -Method Post -Headers $authHeaders `
  -ContentType 'application/json' -Body $transferBody
Invoke-RestMethod ('http://localhost:8080/api/v1/transfers/history/' + $sourceWallet.data.id) -Headers $authHeaders
```

A successful transfer returns 200 with ID, source/destination IDs, exact amount and timestamp. Both balance updates and one ledger insertion commit together. History is private to its account owner and includes incoming/outgoing successful transfers, newest first; no results returns an empty array.

Self-transfers and invalid amounts return 400; missing accounts 404; wrong source/history owner 403; insufficient funds, inactive recipients and capacity conflicts 409. Missing/invalid bearer authentication returns 401. No ledger edit/delete endpoint exists. Repeated successful requests are separate transfers, and standalone transfers do not mark bookings paid.

## Booking and two-party completion

Continue with the customer `$authHeaders` and `$recipientAccountId` from above. Log in as the selected demo superhero using its local `.env.demo` password, then create a fixed-price job and confirm each side:

```powershell
$heroCredential = Get-Credential -UserName 'demo-superhero' -Message 'Use the local demo password'
$heroLoginBody = @{ username = $heroCredential.UserName; password = $heroCredential.GetNetworkCredential().Password } | ConvertTo-Json
$heroLogin = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post `
  -ContentType 'application/json' -Body $heroLoginBody
$heroHeaders = @{ Authorization = 'Bearer ' + $heroLogin.data.token }
$customerBefore = (Invoke-RestMethod http://localhost:8080/api/v1/accounts/me -Headers $authHeaders).data.balance
$heroBefore = (Invoke-RestMethod http://localhost:8080/api/v1/accounts/me -Headers $heroHeaders).data.balance
$heroUserId = (Invoke-RestMethod http://localhost:8080/api/v1/users/me -Headers $heroHeaders).data.id
Invoke-RestMethod http://localhost:8080/api/v1/users/me/rate -Method Put `
  -Headers $heroHeaders -ContentType 'application/json' -Body '{"amount":100.00}'
$currentRate = Invoke-RestMethod ("http://localhost:8080/api/v1/users/$heroUserId/rate") -Headers $authHeaders
$requestBody = @{ serviceProviderUserId = $heroUserId; expectedAmount = $currentRate.data.amount } | ConvertTo-Json
$proposal = Invoke-RestMethod http://localhost:8080/api/v1/protection-requests -Method Post `
  -Headers $authHeaders -ContentType 'application/json' -Body $requestBody
$requestUrl = 'http://localhost:8080/api/v1/protection-requests/' + $proposal.data.id
# Provider can POST $requestUrl/decline instead; that creates no booking.
$createdBooking = Invoke-RestMethod ($requestUrl + '/accept') -Method Post -Headers $heroHeaders
$bookingUrl = 'http://localhost:8080/api/v1/bookings/' + $createdBooking.data.id
Invoke-RestMethod ($bookingUrl + '/confirmations/service-provider') -Method Post -Headers $heroHeaders
Invoke-RestMethod ($bookingUrl + '/confirmations/customer') -Method Post -Headers $authHeaders
Invoke-RestMethod $bookingUrl -Headers $authHeaders
Invoke-RestMethod http://localhost:8080/api/v1/bookings -Headers $authHeaders
$customerAfter = (Invoke-RestMethod http://localhost:8080/api/v1/accounts/me -Headers $authHeaders).data.balance
$heroAfter = (Invoke-RestMethod http://localhost:8080/api/v1/accounts/me -Headers $heroHeaders).data.balance
if ($customerBefore -ne $customerAfter -or $heroBefore -ne $heroAfter) { throw 'Unexpected booking balance change.' }
[pscustomobject]@{ CustomerBefore = $customerBefore; CustomerAfter = $customerAfter; HeroBefore = $heroBefore; HeroAfter = $heroAfter }
```

Request creation returns 201 with `Pending` and no booking; provider acceptance returns 200 with an `Unconfirmed` booking; the first confirmation above returns `ServiceProviderConfirmed`, and the second returns `ConfirmedUnpaid`. Customer-first also works through `CustomerConfirmed`. Each participant can confirm only their assigned side. Repeats return 200 with the current state, including historical paid bookings; they never transfer funds or reset payment links. Reads are private to either participant, and lists include both roles ordered by creation time then ID descending.

The authenticated identity selects the customer. Request inputs are `serviceProviderUserId` and `expectedAmount`; the latter detects stale displays, while the server stores the authoritative rate. A mismatch returns 409. Existing request/booking snapshots never change when the hero publishes a new rate. Zero funds are allowed at submission/acceptance. New acceptance requires both users active and the provider eligible. Requests are private to participants; only the assigned provider accepts/declines. Those decisions are terminal and retry-safe; repeated customer submissions are independent requests. Direct `POST /bookings` returns 405. Pending requests do not prevent otherwise valid deactivation/eligibility removal; acceptance rechecks availability. There are no generic edit/delete, cancellation or expiry routes.

After both confirmations, the customer explicitly pays:

```powershell
Invoke-RestMethod ($bookingUrl + '/payment') -Method Post -Headers $authHeaders
```

Payment returns 200 with `ConfirmedPaid` and `paymentTransferId`. Its fixed-price debit, provider credit, ledger row and booking link commit together. Premature payment or insufficient funds returns 409; another actor receives 403. A failed payment stays unpaid without partial financial effects. Load the exact missing amount through the simulated load endpoint and retry. Repeating a successful payment returns its original result without charging again, including simultaneous requests. Body fields cannot replace the stored source, recipient, quote or link. A matching standalone transfer does not mark the booking paid.

## Repeatable endpoint and error demonstration

With the packaged app running normally on port 8080:

```powershell
.\scripts\verify-api.ps1 -BaseUrl http://localhost:8080
```

This script executes **86 asserted curl requests** covering every endpoint path and relevant 400/401/403/404/405/409 responses, both transfer directions, publish/lookup, stale-price rejection, request/accept/decline, confirmation/payment, safe retries, deactivation and retained history. It generates unique synthetic users and simulated funds on every run; use a dedicated local development schema. Request credentials stay in temporary files that are removed; `target/api-verification.json` contains safe case/status metadata. It leaves its provider and financial history rows intact. Normal registrations already have wallets, so `POST /accounts` demonstrates 409; its accountless 201 case is verified by the documented MySQL/controller fixture.

The R1 sequence passed on a fresh separate verification schema, then again after restarting it with `validate`. Existing `kingpay` demo balances were not reset. Use the same sequence during the Phase 8 narrated recording, then show `mvn clean verify` and the coverage report. OpenAPI and the read-only admin overview are now implemented; see the addendum below.

The example changes the recipient's balance. Demo preparation does not undo transfers or reduce balances above its targets. The acceptance tests use isolated 1000.00/500.00 fixtures and verify 900.00/600.00 after a 100.00 transfer without changing the prepared demo wallets.

## OpenAPI / Swagger UI and admin overview

After starting the application, open `/swagger-ui/index.html`. The task-started server currently uses [port 8091](http://localhost:8091/swagger-ui/index.html); normal startup defaults to port 8080. JSON is at `/v3/api-docs` and YAML at `/v3/api-docs.yaml`. Importable snapshots are [openapi.json](docs/openapi.json) and [openapi.yaml](docs/openapi.yaml). All 24 operations are documented with DTO schemas, validation, status codes and workflow rules.

Register/login remain public. Documentation GET routes are also public; all business routes require a current active bearer identity. Log in, copy `data.token`, click **Authorize** in Swagger, and paste just the token. It is not persisted across refreshes. Use the documentation server's actual port for requests.

`GET /api/v1/admin/overview` returns global `users`, `accounts`, `bookings`, `transfers`, `rates` and `protectionRequests` in the standard envelope. It includes inactive/historical records and contact/balance details but excludes passwords/hashes/tokens. It is read-only, unpaginated and limited to the stable user IDs in `KINGPAY_ADMIN_USER_IDS`. Empty configuration denies all users; no registration/profile/query field grants access. Add the trusted IDs as comma-separated unquoted values in `.env`, then restart.

The requested identity is `kingpay-admin`, user ID **6678**; its generated credentials are in ignored `.env.admin`, and its wallet starts at zero. Set `KINGPAY_ADMIN_USER_IDS=6678` yourself as requested. An empty allowlist continues to deny access; restart after configuring trusted IDs. See the [delegate report](docs/openapi-admin-report.md) for process/run details and verification. Keep `.env.admin` out of source control and recordings.
