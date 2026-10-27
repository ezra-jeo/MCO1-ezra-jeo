# Kingpay

Kingpay is a fictional superhero protection booking and wallet API. The planned flow creates a booking, collects both participants' completion confirmations, and lets the customer explicitly pay its fixed price. Wallets start at zero and can receive simulated loads. Standalone transfers have a separate permanent ledger.

**Current state: Phase 4 implemented.** Registration/login, profiles, superhero selection, wallet loading, atomic standalone transfers and private transfer history work. Booking creation/confirmations and booking-associated payment remain Phases 5–6. See the [Phase 4 report](docs/phase-4-report.md) and [endpoint inventory](docs/skeleton-status.md).

## Prerequisites and dependency installation

- JDK 21 and Maven 3.9 or later on `PATH`.
- Run commands from `kingpay/`, where `pom.xml` lives.
- A running MySQL server, a project database, and a MySQL user with access to it are needed for application startup. Enter local credentials in the ignored `.env` file described below.

Download dependencies and compile production and test sources:

```powershell
mvn -DskipTests test-compile
```

The existing POM's Spring Boot version is retained. Bean Validation, MySQL Connector/J, Spring Security and its JWT resource-server components are included. Coverage tooling remains pending.

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

## Verify the skeleton

```powershell
.\scripts\verify-skeleton.ps1
```

The script compiles with cached Maven dependencies and checks the entity, DTO, endpoint, and explicit-placeholder structure. It fails immediately when an assertion or compilation fails. This is a phase-specific scaffold check; update its placeholder expectations when implementing the corresponding phase.

`-DskipTests test-compile` compiles test sources; it does **not** execute tests. The original `KingpayApplicationTests.contextLoads()` verifies startup against MySQL. Running the full suite requires a valid `.env` and a reachable database with the expected schema:

```powershell
mvn test
```

The full suite passes **333 tests** with zero failures/errors/skips. The [Phase 4 report](docs/phase-4-report.md) records unit, HTTP/security, actual SQL rollback and committed concurrency evidence. See also [MySQL setup evidence](docs/mysql-setup.md).

Registration, [Phase 2](docs/test-cases/phase-2.md), [Phase 3](docs/test-cases/phase-3.md) and [Phase 4](docs/test-cases/phase-4.md) have documented executable cases, with at least three per affected service/controller method. Future booking/payment methods still need their own cases; no coverage report is claimed.

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

MySQL connectivity, schema validation and executable startup were verified against `kingpay`. Normal starts use `DB_DDL_AUTO=validate`; the four mapped tables already exist. Phase 3 introduces no schema migration.

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

`GET /api/v1/users/superheroes` returns active eligible users with wallets, including their `accountId` for the future booking request. Contact details and balances are omitted. Removing provider eligibility while assigned work is unfinished/unpaid returns 409.

Registration already creates a wallet. `POST /api/v1/accounts` creates a zero wallet for an accountless user and returns 409 for an existing wallet; it accepts no opening-balance input.

## Prepare repeatable demo participants

With the application running, execute:

```powershell
.\scripts\prepare-demo.ps1
```

The script prepares `demo-customer` at **1000.00** and `demo-superhero` at **250.00**, using real API calls. Missing credentials are generated into ignored `kingpay/.env.demo`; the script prints only usernames, wallet IDs and balances. You can supply `KINGPAY_DEMO_PASSWORD` instead. Read your local secret file privately when preparing Postman; keep passwords/tokens out of screenshots and source control.

Sequential reruns load only the difference to the target. A balance above target, an inactive identity, incompatible eligibility or an existing different password fails without resetting the account. Run the script sequentially. It targets localhost by default; `-BaseUrl` selects a different local server port.

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

The example changes the recipient's balance. Demo preparation does not undo transfers or reduce balances above its targets. The acceptance tests use isolated 1000.00/500.00 fixtures and verify 900.00/600.00 after a 100.00 transfer without changing the prepared demo wallets.