# Kingpay

Kingpay is a Spring Boot API for superhero protection bookings and wallet payments. A hero publishes one flat rate; a customer submits a protection request at that price. Provider acceptance creates a booking. Both participants confirm completed work before the customer explicitly pays its preserved price.

The API also provides registration/login, editable profiles, zero-balance wallets, simulated wallet loads, account-to-account transfers, private histories and a read-only admin overview. Money uses exact `BigDecimal`/DECIMAL(19,2) values in one implicit project currency.

## Relationship to course lectures and laboratory activities

The mapping below follows the MCO1 specifications' Week 1-6 technical scope and the Week 5 wallet/security laboratory. Account-to-account transfer is also included as the common feature required by the subsequent class clarification. These are the foundation, rather than the additional feature claimed for this capstone.

| Course topic / activity | Specific Kingpay implementation | Source evidence |
| --- | --- | --- |
| Weeks 1-2: Spring Boot, IoC and dependency injection | Maven Spring Boot monolith; controllers receive application services through constructors, and services receive persistence/security dependencies | [pom.xml](pom.xml), [AuthController](src/main/java/com/kingpay/controller/AuthController.java), [AuthService](src/main/java/com/kingpay/service/AuthService.java) |
| Weeks 1-2: REST routing and DTOs | Resource routes under `/api/v1`, explicit HTTP methods/statuses, request records and response DTOs instead of entity serialization | [UserController](src/main/java/com/kingpay/controller/UserController.java), [request DTOs](src/main/java/com/kingpay/dto/request), [response DTOs](src/main/java/com/kingpay/dto/response) |
| Week 3: JPA/Hibernate and MySQL persistence | Mapped entities, relationship constraints, JpaRepository interfaces and derived queries; database settings loaded through application.properties | [entities](src/main/java/com/kingpay/entity), [repositories](src/main/java/com/kingpay/repository), [application.properties](src/main/resources/application.properties); reference [1] |
| Week 3: domain data operations | Register/create identities and wallets; read/update own profile; guarded user deactivation; owner wallet lookup and explicit loading/transfer commands | [UserService](src/main/java/com/kingpay/service/UserService.java), [AccountService](src/main/java/com/kingpay/service/AccountService.java) |
| Week 4: Bean Validation and centralized exception handling | Required fields, email/length/decimal validation; RestControllerAdvice and ExceptionHandler methods produce consistent safe error envelopes | [RegisterRequest](src/main/java/com/kingpay/dto/request/RegisterRequest.java), [LoadAccountRequest](src/main/java/com/kingpay/dto/request/LoadAccountRequest.java), [SkeletonExceptionHandler](src/main/java/com/kingpay/exception/SkeletonExceptionHandler.java) |
| Week 5: registration, login and password security | Atomic user/zero-wallet registration, BCrypt PasswordEncoder, AuthenticationManager credential verification and signed expiring JWT issuance | [AuthService](src/main/java/com/kingpay/service/AuthService.java), [TokenService](src/main/java/com/kingpay/service/TokenService.java) |
| Week 5: secured resources and ownership | Stateless SecurityFilterChain, UserDetailsService adapter, JWT validation, current database identity/activity checks, and owner/participant authorization | [SecurityConfig](src/main/java/com/kingpay/security/SecurityConfig.java), [KingpayUserDetailsService](src/main/java/com/kingpay/security/KingpayUserDetailsService.java), [CurrentUser](src/main/java/com/kingpay/security/CurrentUser.java); reference [2] |
| Common transfer requirement | Authorized source-wallet debit, recipient credit and permanent successful transfer record in one transaction; owner-only incoming/outgoing history | [TransferService](src/main/java/com/kingpay/service/TransferService.java), [TransferRecord](src/main/java/com/kingpay/entity/TransferRecord.java) |
| Week 6: JUnit, Mockito and controller/integration testing | Isolated service tests, MockMvc HTTP tests and MySQL-backed checks of committed success, rollback and selected concurrency cases | [executable tests](src/test/java/com/kingpay), [documented case matrices](test-cases/README.md) |
| Documentation and demonstration requirements | Source Javadoc contracts, class diagram, documented cases and a Postman demonstration; JaCoCo generates coverage for the test demonstration | [class diagram](../Kingpay.drawio), [case inventory](test-cases/method-cases.tsv), [coverage setup](pom.xml); reference [3] |

The Week 5 scaffold's security goals are retained with implementation differences: framework JWT support replaces a handwritten filter, and a UserDetailsService adapter maps the persistence entity to Spring's UserDetails contract. Admin overview authorization uses externally configured stable user IDs. The project does not claim an exact copy of every scaffold endpoint.

Financial records use domain-specific commands and retained history: profile deletion means deactivation, and wallets/bookings/transfers do not expose unrestricted replacement/deletion operations. This documents the implemented policy rather than claiming generic CRUD for every entity.

## Additional feature: superhero protection booking

The selected addition beyond the wallet/security scaffold is the **protection booking lifecycle**. It applies course technologies to a new domain workflow; transactions, validation and JWT themselves are not claimed as new technologies outside the course.

| Additional capability | Implemented behavior | Relevance and practical value |
| --- | --- | --- |
| Published hero pricing | One positive flat rate per provider; available hero discovery and rate lookup by user ID | Customers can review a concrete service price before requesting protection |
| Price-preserving customer request | Authenticated customer submits a provider and expected price; server checks the current publication and saves immutable terms | Rejects stale displays and prevents later rate edits from silently changing an existing agreement |
| Provider acceptance or decline | Only the assigned provider responds; acceptance and one linked booking commit together; repeated acceptance returns the original booking | Gives heroes control over engagements and prevents duplicate bookings caused by retries |
| Independent completion confirmations | Each assigned participant confirms only their own side; either order reaches ConfirmedUnpaid | Separates accepting the job from agreeing that the protection work is complete |
| Booking-linked settlement | Customer explicitly pays the stored full price after both confirmations; debit, credit, ledger and booking link commit together | Associates payment with the agreed service and prevents partial financial updates |
| Duplicate-safe payment | A successful retry returns the same paid booking and transfer ID | Prevents repeated requests from charging for the same engagement twice |
| Private lifecycle history | Participants can inspect their requests/bookings and payment association; unrelated users are denied | Makes progress and settlement traceable without exposing another customer's private activity |

Implementation evidence: [HeroRateService](src/main/java/com/kingpay/service/HeroRateService.java), [ProtectionRequestService](src/main/java/com/kingpay/service/ProtectionRequestService.java), [BookingService](src/main/java/com/kingpay/service/BookingService.java), and [booking/request case matrices](test-cases/r1.md).

This addition is specific to Kingpay's theme: a generic wallet can move funds, but it does not represent a protection engagement, preserve its agreed price, obtain provider agreement, track both completion confirmations or link a single settlement to that work. The booking lifecycle connects those responsibilities into an end-to-end service journey.

Boundaries: one provider and one full payment per booking, simulated wallet funding and one implicit currency. Customers may defer unpaid settlement; installments, cancellation, refunds, expiry and scheduling are not implemented. Additional-feature grading remains subject to the instructor's assessment of relevance and practicality.

## References and attribution

Course basis: MCO1 project specifications, Section 4 (Weeks 1-6 technical scope); Week 5 laboratory instructions (wallet security, authentication and ownership); the MCO1 FAQ (documented service/controller cases, endpoint/error demonstration and coverage); and the class clarification requiring account-to-account transfers. These identify the instructional basis without requiring copies of course handouts in the submitted repository.

Implementation references:

1. [Spring Data JPA reference](https://docs.spring.io/spring-data/jpa/reference/jpa.html) - repository abstraction, entity persistence, queries and transaction/locking support.
2. [Spring Security JWT resource-server reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) - bearer-token authentication and JWT validation infrastructure used by the application. Kingpay issues its own tokens and uses a local configured HMAC key; it does not depend on an external authorization server.
3. [JaCoCo Maven plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html) - test-agent integration and generated coverage reports.
4. [springdoc-openapi documentation](https://springdoc.org/) - generated OpenAPI schemas and Swagger UI. This supplements API documentation; the recorded behavior demonstration can use Postman alone.

## Requirements and repository contents

- JDK 21, Maven 3.9 or later, and a running MySQL server.
- Run the commands below from `kingpay/`, where `pom.xml` lives.
- Keep `src/main`, `src/test`, `pom.xml`, `.env.example`, the Maven wrapper files and [documented test cases](test-cases/README.md) in the submission.
- Include the [UML class diagram](../Kingpay.drawio) at repository root, alongside the `kingpay/` project directory.
- Include the narrated demonstration or its accessible link before submission.
- Real `.env` files, credentials and generated `target/` artifacts are not submission files.

## Configure MySQL and authentication

Create a dedicated development database through MySQL Workbench or your MySQL client:

```sql
CREATE DATABASE kingpay CHARACTER SET utf8mb4;
```

Copy the environment template if `.env` does not already exist:

```powershell
Copy-Item .env.example .env
```

Enter the connection values locally. Spring imports the working directory's `.env` as a Java properties file; values are unquoted, and literal backslashes must be escaped. Credentials go in their separate fields rather than in the JDBC URL. OS environment variables can also supply these settings.

| Variable | Meaning |
| --- | --- |
| `DB_URL` | JDBC URL, for example `jdbc:mysql://localhost:3306/kingpay`; the database must exist |
| `DB_USERNAME` | MySQL user with access to that development database |
| `DB_PASSWORD` | That user's password |
| `DB_DDL_AUTO` | Normally `validate`; one-time `update` initialization is described below |
| `JWT_SECRET` | Base64-encoded random signing key, at least 32 bytes before encoding |
| `JWT_ISSUER` | Expected issuer; defaults to `kingpay` |
| `JWT_TTL_SECONDS` | Positive token lifetime; defaults to `3600` |
| `KINGPAY_ADMIN_USER_IDS` | Comma-separated trusted user IDs; empty configuration denies admin access |

After copying the template, generate its signing key locally without printing it:

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

This replaces only the example placeholder. Invalid/missing keys prevent startup, and changing an existing signing key invalidates previously issued tokens. `.env` and local `.env.*` variants are ignored; `.env.example` contains placeholders.

## Build and initialize a fresh database

Download dependencies and package the application:

```powershell
mvn -DskipTests package
```

For the new empty development schema only, initialize the mapped tables once:

```powershell
java -jar target/kingpay-0.0.1-SNAPSHOT.jar --spring.jpa.hibernate.ddl-auto=update
```

After successful startup, stop the process with Ctrl+C. Keep `.env` at `DB_DDL_AUTO=validate` and start normally:

```powershell
mvn spring-boot:run
```

Alternatively, run the packaged application normally:

```powershell
java -jar target/kingpay-0.0.1-SNAPSHOT.jar
```

The default port is **8080**. For another port, add `--server.port=8091` to the jar command. Run one instance per chosen port.

Normal startup validates the six mapped tables without changing the schema. These initialization steps assume a fresh database; an existing database must already match the current model. No automatic versioned data-migration process is configured. Stop a running packaged instance before rebuilding its jar, particularly on Windows where it can remain locked.

## Swagger and OpenAPI

Documentation is served by the application itself:

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)
- [OpenAPI YAML](http://localhost:8080/v3/api-docs.yaml)

Use the application's actual port. All 24 operations have generated request/response schemas and workflow descriptions. Register or log in, copy `data.token`, click **Authorize**, and paste the token. Switch tokens when switching actors. Authorization is not persisted across browser refreshes.

Registration/login and documentation GET routes are public. Business endpoints require a current active bearer identity. JWT subjects use stable user IDs; each request reloads current activity/name/authorities. Passwords are BCrypt hashes. There is no refresh-token or logout endpoint.

## Minimal API example

Create a test customer using credentials entered locally:

```powershell
$baseUrl = 'http://localhost:8080'
$credential = Get-Credential -Message 'Enter a new Kingpay test username and password'
$registration = @{
    username = $credential.UserName
    password = $credential.GetNetworkCredential().Password
    fullName = 'Example Customer'
    phone = '000'
    email = 'customer@example.test'
    isSuperhero = $false
} | ConvertTo-Json
$registered = Invoke-RestMethod "$baseUrl/api/v1/auth/register" -Method Post `
    -ContentType 'application/json' -Body $registration
$loginBody = @{
    username = $credential.UserName
    password = $credential.GetNetworkCredential().Password
} | ConvertTo-Json
$login = Invoke-RestMethod "$baseUrl/api/v1/auth/login" -Method Post `
    -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = 'Bearer ' + $login.data.token }
Invoke-RestMethod "$baseUrl/api/v1/users/me" -Headers $headers
Invoke-RestMethod "$baseUrl/api/v1/accounts/me" -Headers $headers
Invoke-RestMethod "$baseUrl/api/v1/accounts/me/load" -Method Post `
    -Headers $headers -ContentType 'application/json' -Body '{"amount":1000.00}'
```

Registration returns 201 with the public user fields and atomically creates a zero wallet. Login returns 200 with a bearer token; the owner wallet read returns its balance. Use a new username for a fresh registration; duplicate usernames return 409. Passwords must contain 8-72 characters and fit BCrypt's 72-byte UTF-8 limit.

The load example adds 1000.00. Each successful load is a separate simulated credit; repeating it adds again. Loading has no external payment provider or transfer-ledger entry.

## Protection and payment workflow

Use a second identity with `isSuperhero=true` as the provider. The same user may also hire another hero.

1. **Provider publishes:** `PUT /api/v1/users/me/rate` with `{"amount":100.00}`.
2. **Customer browses:** `GET /api/v1/users/superheroes`, then `GET /api/v1/users/{userId}/rate`. Select the hero by user ID; transfer endpoints instead use account IDs.
3. **Customer submits:** `POST /api/v1/protection-requests` with `serviceProviderUserId` and the displayed `expectedAmount`. The server resolves both wallets and snapshots its authoritative rate. A stale displayed price returns 409; successful creation returns 201 Pending with no booking.
4. **Provider responds:** `POST /api/v1/protection-requests/{requestId}/accept` creates exactly one Unconfirmed booking at the preserved price. Repeated acceptance returns that booking. `/decline` creates none; repeat decline is harmless, and opposite terminal decisions return 409.
5. **Both confirm completed work:** each participant invokes their own booking confirmation endpoint. Either order is allowed: Unconfirmed becomes CustomerConfirmed or ServiceProviderConfirmed, then ConfirmedUnpaid. Repeats cannot reverse completion/payment.
6. **Customer pays:** `POST /api/v1/bookings/{bookingId}/payment`. The fixed-price debit, provider credit, transfer record and paid booking link commit together. The result is ConfirmedPaid with `paymentTransferId`; successful retries return the original result without charging again.

Requests, acceptance and completion confirmations move no money. Zero funds are allowed until payment. Insufficient funds leave the booking unpaid and retryable; load the missing funds and retry. Published-price changes affect new requests only. A matching standalone transfer does not mark a booking paid. Direct `POST /bookings` returns 405.

## Endpoint inventory

Responses use the `message`/`data` envelope, except successful deactivation returns 204 without a body. Request schemas and detailed errors are available in Swagger.

| Method and path | Purpose / success |
| --- | --- |
| `POST /api/v1/auth/register` | Register user and zero wallet; 201 |
| `POST /api/v1/auth/login` | Issue bearer token; 200 |
| `GET /api/v1/users/me` | Own profile; 200 |
| `PUT /api/v1/users/me` | Replace editable profile fields; 200 |
| `DELETE /api/v1/users/me` | Guarded user deactivation; 204 |
| `GET /api/v1/users/superheroes` | Available heroes with published prices; 200 |
| `PUT /api/v1/users/me/rate` | Publish/update own flat rate; 200 |
| `GET /api/v1/users/{userId}/rate` | Read available hero rate by user ID; 200 |
| `POST /api/v1/accounts` | Create own zero wallet for an accountless user; 201 |
| `GET /api/v1/accounts/me` | Own wallet; 200 |
| `POST /api/v1/accounts/me/load` | Add an exact positive simulated load; 200 |
| `POST /api/v1/transfers` | Transfer `{fromId, toId, amount}` as source owner; 200 |
| `GET /api/v1/transfers/history/{accountId}` | Owner-only incoming/outgoing history; 200 |
| `POST /api/v1/protection-requests` | Submit a server-priced Pending request; 201 |
| `GET /api/v1/protection-requests` | Own submissions/provider inbox; 200 |
| `GET /api/v1/protection-requests/{requestId}` | Participant-only request details; 200 |
| `POST /api/v1/protection-requests/{requestId}/accept` | Assigned provider accepts; 200 booking |
| `POST /api/v1/protection-requests/{requestId}/decline` | Assigned provider declines; 200 request |
| `GET /api/v1/bookings` | Own bookings in either role; 200 |
| `GET /api/v1/bookings/{bookingId}` | Participant-only booking; 200 |
| `POST /api/v1/bookings/{bookingId}/confirmations/customer` | Customer confirms completion; 200 |
| `POST /api/v1/bookings/{bookingId}/confirmations/service-provider` | Provider confirms completion; 200 |
| `POST /api/v1/bookings/{bookingId}/payment` | Customer explicitly pays fixed price; 200 |
| `GET /api/v1/admin/overview` | Configured admin reads all six collections; 200 |

Registration already creates a wallet, so a normal user's separate account-creation request returns 409. Its 201 path supports accountless existing identities and is covered by a controlled test fixture.

## Ownership, history and errors

- Only owners read their profiles/wallets or spend from their accounts. Only assigned participants read a protection request/booking, and only the assigned provider responds to a protection request.
- Profile PUT replaces username, fullName, phone, email and isSuperhero. Credentials, activity, wallet ownership and balances cannot be replaced through that body. Unknown JSON fields are ignored.
- User deactivation requires zero balance and no unfinished/unpaid booking in either role. It freezes only User and retains related records. Pending requests do not block otherwise valid deactivation; new acceptance rechecks availability. Outstanding provider bookings block removal of hero eligibility.
- Money inputs must be positive and fit 17 integer/two fractional digits; balances may reach zero. No rounding or currency conversion is performed. Self-transfers and self-protection requests are rejected.
- Standalone transfers atomically debit/credit and append a successful transfer record. Successful repeats are independent transfers. Histories include both directions, newest timestamp then ID descending.
- Bookings keep fixed parties/price. Transfer history has no edit/delete API. Cancellation, request expiry, refunds, bidding and pagination are outside this version.

Errors use 400 for invalid fields/IDs/JSON, 401 for absent/invalid/inactive authentication, 403 for wrong actors, 404 for absent resources, and 409 for domain conflicts such as stale prices, insufficient funds or duplicate usernames. Unexpected server errors return a safe 500 without internal diagnostics. Failed transactional writes leave no partial financial effects.

## Admin configuration

The admin overview returns `users`, `accounts`, `bookings`, `transfers`, `rates` and `protectionRequests`, including historical/inactive records. It excludes passwords/hashes/tokens and is read-only and unpaginated.

Register the intended identity normally, take its returned user ID, add that ID to `KINGPAY_ADMIN_USER_IDS` in the local `.env`, and restart. Multiple IDs are comma-separated. Neither registration nor profile fields grant admin access; empty configuration denies everyone. Credentials remain local.

## Tests and coverage

Configure MySQL and initialize the current schema before running the complete suite:

```powershell
mvn clean verify
```

Maven runs JUnit/Mockito/MockMvc and MySQL-backed tests, builds the executable jar, and generates JaCoCo HTML at `target/site/jacoco/index.html`. Surefire results are in `target/surefire-reports/`. The database tests create and clean up their own captured fixtures; use a dedicated development database. No coverage exclusions or threshold are configured.

To regenerate test coverage without cleaning or repackaging a running jar:

```powershell
mvn '-Djacoco.append=false' test jacoco:report
```

MySQL must be available; a separately running API server is not required. The flag replaces previous execution data so this report describes the new test run. Open `target/site/jacoco/index.html` in a browser, then click a package and class to inspect source coverage: green is covered, red is missed, and yellow is partly covered. JaCoCo records production instructions and branches executed by the tests. Manual Swagger calls are not collected by this test-agent setup. Coverage measures execution, not whether every assertion or business rule is correct; the first HTML `Cov.` column is instruction coverage, while line and branch counters are separate.

The generated report is under ignored `target/` and can be regenerated for the video. It does not need to be committed with the source.

The latest verified full run passed **674 tests in 26 classes**, with zero failures/errors/skips. Observed production line coverage was **768/811 (94.70%)**, including **431/431 service/controller lines**. Overall branch coverage was **253/282 (89.72%)**. These figures describe that run rather than guarantee all possible behavior.

The [case matrices and method inventory](test-cases/README.md) document at least three distinct cases for each of the 76 service/controller declarations, including private helpers. Keep both this documentation and the executable tests in the submission. Production methods include Javadoc contracts in their source.

To run only the database-independent entity suites:

```powershell
mvn '-Dtest=AccountTest,BookingTest' test
```

For the recorded demonstration, use Postman or curl to show intended successes and error messages, run the tests, display coverage, and explain the pricing/request/booking/payment boundaries with voice narration.
