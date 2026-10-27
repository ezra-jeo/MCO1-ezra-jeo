# Kingpay

Kingpay is a fictional superhero protection booking and wallet API. The planned flow creates a booking, collects both participants' completion confirmations, and lets the customer explicitly pay its fixed price. Wallets start at zero and can receive simulated loads. Standalone transfers have a separate permanent ledger.

**Current state: Phase 2 implemented.** Registration creates a hashed user and zero-balance wallet atomically. Login issues signed JWTs; other routes require an active authenticated user, with ownership guards on identifier-based transfers and bookings. Future business operations still return 501 after authorization. See the [Phase 2 report](docs/phase-2-report.md) and [endpoint inventory](docs/skeleton-status.md).

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

The latest full-suite result is recorded in the [Phase 2 report](docs/phase-2-report.md), including real MySQL repository, registration rollback/concurrency, HTTP security and application-context tests. See also [MySQL setup evidence](docs/mysql-setup.md).

Registration and Phase 2 methods have [documented executable cases](docs/test-cases/phase-2.md), with at least three distinct cases per affected service/controller method. Future business methods still need their own cases; no coverage report is claimed.

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

MySQL connectivity, schema validation, executable application startup, and an HTTP request were verified against `kingpay`. The temporary verification server was stopped afterward. Normal starts use `DB_DDL_AUTO=validate`; the four mapped tables already exist. Demo data and funding setup remain pending.

## Minimal API example

Register a test identity (use a new username if it already exists):

```powershell
'{"username":"demo-customer","password":"demo-password-123","fullName":"Demo Customer","phone":"000","email":"demo@example.test","isSuperhero":false}' |
  curl.exe -i -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" --data-binary '@-'
```

Login and use its bearer token with PowerShell:

```powershell
$loginBody = @{ username = 'demo-customer'; password = 'demo-password-123' } | ConvertTo-Json
$loginResponse = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post `
  -ContentType 'application/json' -Body $loginBody
$authHeaders = @{ Authorization = 'Bearer ' + $loginResponse.data.token }
Invoke-WebRequest http://localhost:8080/api/v1/accounts/me -Headers $authHeaders
```

Registration returns 201, login returns 200, and the authenticated account lookup currently returns **501** because its Phase 3 business operation is unfinished. PowerShell reports that 501 as an HTTP error. Omitting the bearer returns 401. A typical authentication failure is:

```json
{"message":"Authentication required or invalid.","data":null}
```

The authenticated principal supplies caller identity. Tokens use a stable user ID, validated issuer/expiry and HS256 signature; every bearer request reloads current user name, active state and roles. Inactive users cannot log in or use existing tokens. There is no refresh-token or logout endpoint. Wrong credentials receive a generic 401; cross-owner actions receive 403; validation errors use the same `message`/`data` envelope with safe field descriptions.

Wallet loading remains a Phase 3 stub at `POST /api/v1/accounts/me/load` with `{"amount":1000.00}`. It is simulated funding without a real payment provider. Other future operations and policy questions are listed in the phase report.
