# Phase status and endpoint inventory

Phases 2–4 are implemented and verified. See the [Phase 4 report](phase-4-report.md) and [documented cases](test-cases/phase-4.md). Booking commands and booking-associated payment remain Phases 5–6.

## Recorded domain decisions

- One user can hire protection and also be eligible as a provider through `isSuperhero`.
- Registration atomically creates a user and one zero-balance account. The separate account-creation endpoint supports accountless identities and returns 409 when an account already exists. The unique owner constraint prevents a second account.
- Profile updates exclude password hashes, activity, ownership and balances. Deactivation is intended to require zero balance and no unfinished/unpaid booking in either role; the service now enforces those checks before freezing only the user.
- Inactive users cannot log in or use existing bearer tokens. Deactivation freezes the user; it does not erase account/history rows. All four tables and six non-cascading foreign keys are verified against MySQL.
- Simulated loading accepts only a positive amount for the authenticated owner's wallet. Each successful request is a separate credit without a ledger row or idempotency key; no real payment integration is planned.
- Booking parties/price are fixed; either confirmation order leads to unpaid, and explicit customer payment associates one permanent transfer. Standalone transfer writes and contention handling are verified; booking service writes remain future work.
- IDs are `Long`, money is `BigDecimal` with precision 19/scale 2, and timestamps are `Instant`. One implicit demo currency is assumed. Amounts use exact DECIMAL(19,2) without rounding. Removing hero eligibility is blocked by outstanding provider work. Self-transfers are rejected. Pricing and self-booking policies remain future decisions.

## Current security behavior

Only `POST /api/v1/auth/register` and `POST /api/v1/auth/login` are public. All other routes require bearer authentication with a current active database identity. Missing/invalid/expired tokens receive 401. Current database roles are ROLE_USER and optional ROLE_SUPERHERO; ownership gates use actual persisted relationships.

Source spending and history require the wallet owner. Booking reads permit either participant; each confirmation requires its assigned actor; payment requires the customer. Wrong actor receives 403, absent resource 404, invalid fields/JSON/IDs 400. Profile/wallet routes now persist their Phase 3 behavior. Standalone transfers/history now return 200; permitted booking/payment placeholders reach 501. Caller identity comes from the principal, never a body identity field.

Registration uses a named username constraint and retains atomic zero-wallet creation. PasswordEncoder uses BCrypt cost 12 with existing jBCrypt compatibility. Registration limits passwords to 8–72 characters and 72 UTF-8 bytes. JWTs use external HS256 key material, stable user-ID subjects, issuer and timestamps; default lifetime is one hour. There is no refresh/logout endpoint.

## Endpoint inventory

Registration, login and profile/wallet routes are implemented. Hero summaries include the account ID needed to select a bookable provider. Transfers and owner-only history are implemented. Booking/payment rows describe future business success contracts and still return 501 after authentication, validation and applicable ownership checks. DTO names live in `com.kingpay.dto.request` and `.response`.

| Method and path | Request | Response data | Planned access | Planned success | Future error cases |
| --- | --- | --- | --- | --- | --- |
| `POST /api/v1/auth/register` | `RegisterRequest` | `UserResponse` | Public | 201 | Invalid input; duplicate username |
| `POST /api/v1/auth/login` | `LoginRequest` | `AuthResponse` | Public | 200 | Invalid input/credentials; inactive users return 401 |
| `GET /api/v1/users/me` | None | `UserResponse` | Authenticated owner | 200 | Missing authentication/user |
| `PUT /api/v1/users/me` | `UpdateUserRequest` | `UserResponse` | Active owner | 200 | Invalid input; duplicate username; invalid eligibility change |
| `DELETE /api/v1/users/me` | None | No body | Active owner | 204 | Nonzero balance; outstanding bookings; missing authentication |
| `GET /api/v1/users/superheroes` | None | `List<SuperheroResponse>` | Authenticated user | 200 | Missing authentication |
| `POST /api/v1/accounts` | None | `AccountResponse` | Active owner | 201 | Duplicate account; missing authentication; inactive user |
| `GET /api/v1/accounts/me` | None | `AccountResponse` | Authenticated owner | 200 | Missing account/authentication |
| `POST /api/v1/accounts/me/load` | `LoadAccountRequest` | `AccountResponse` | Active owner | 200 | Missing/zero/negative amount; excess precision; missing account/authentication; inactive user; resulting balance exceeds storage limit |
| `POST /api/v1/transfers` | `TransferRequest` | `TransferResponse` | Active source owner | 200 | Invalid amount/IDs; missing accounts; forbidden ownership; insufficient funds; inactive participant; self-transfer policy |
| `GET /api/v1/transfers/history/{accountId}` | None | `List<TransferResponse>` | Requested account owner | 200 | Missing account/authentication; forbidden ownership |
| `POST /api/v1/bookings` | `CreateBookingRequest` | `BookingResponse` | Active customer | 201 | Invalid price/provider; missing account; provider ineligible/inactive; self-booking policy |
| `GET /api/v1/bookings` | None | `List<BookingResponse>` | Authenticated participant | 200 | Missing authentication |
| `GET /api/v1/bookings/{bookingId}` | None | `BookingResponse` | Booking participant | 200 | Missing booking/authentication; forbidden access |
| `POST /api/v1/bookings/{bookingId}/confirmations/customer` | None | `BookingResponse` | Booking customer | 200 | Missing booking/authentication; wrong participant |
| `POST /api/v1/bookings/{bookingId}/confirmations/service-provider` | None | `BookingResponse` | Assigned provider | 200 | Missing booking/authentication; wrong participant |
| `POST /api/v1/bookings/{bookingId}/payment` | None | `BookingResponse` | Booking customer | 200 | Unconfirmed/already-paid booking; forbidden access; insufficient funds; transfer failure |

`DELETE /users/me` means deactivation with retained records. Accounts have no arbitrary balance-update or separate physical-delete route. Bookings have no generic update/delete routes because parties/price are fixed and cancellation remains outside the base model. Confirm how those policies meet the rubric's domain CRUD wording; do not add unsafe mutation merely to provide all four verbs.

## Progress and acceptance

| Phase | Evidence now present | Still required |
| --- | --- | --- |
| 0 | Main entities, recorded decisions, endpoint inventory | Remaining policies and final rubric/CRUD interpretation |
| 1 | Maven/JDK 21 build, MySQL schema/startup, ignored external config, repeatable tests | Coverage tooling (P1.4); demo setup P1.5 now verified |
| 2 | Registration/login, PasswordEncoder, JWT, active identity reload, security chain, ownership guards, safe errors and documented cases | P2.1–P2.6 verified; operational limits listed in report |
| 3 | Owner profiles, eligible hero selection, zero-wallet creation, exact loads, guarded deactivation, 92 new cases and demo setup | P3.1–P3.7 verified; limits in Phase 3 report |
| 4 | Atomic transfers, immutable API ledger, owner-only history, ordered locking, SQL rollback and 70 new cases | P4.1–P4.8 verified; limits in Phase 4 report |
| 5 | Entities, sequential confirmation tests, routes and participant/actor guards | Booking creation, eligibility, persistence, confirmation services and cases |
| 6 | Unique payment link, entity checks and customer guard | Atomic exactly-once booking payment with concurrency/retry cases |
| 7–8 | Case inventories, phase report, README and UML security page | Remaining cases, coverage, final documentation/UML, narrated demonstration and private submission |

## Verification

`mvn -o test`: **333 tests passed, zero failures/errors/skips**, including 70 Phase 4 unit/HTTP/SQL-rollback/concurrency cases and all prior regressions. `scripts/verify-skeleton.ps1` passed and `mvn -o -DskipTests package` produced the executable jar. Latest evidence is recorded in the Phase 4 report.

The scaffold script checks compilation and structural contracts; the actual tests establish authentication, authorization and Phase 3 profile/wallet behavior. No future financial behavior, code coverage percentage or fully working domain CRUD is claimed. HTTP fixtures roll back; committed registration fixtures are cleaned by their unique test identity. Schema action remains `validate`.

`Kingpay.drawio` includes separate Phase 2 security and Phase 3 profile/wallet and Phase 4 transfer/history dependency pages. Its original main-entity page is retained. Final UML reconciliation remains Phase 7 work.
Live demo setup was verified twice against the packaged application: demo-customer wallet 733 at 1000.00 and demo-superhero wallet 734 at 250.00. Credentials are in ignored .env.demo. Sequential rerun preserved both IDs and balances; the temporary server was stopped. See scripts/prepare-demo.ps1 and the Phase 3 report.
