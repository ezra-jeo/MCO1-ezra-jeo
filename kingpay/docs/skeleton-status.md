# Phase status and endpoint inventory

Phase 2 authentication/authorization is implemented and verified. See the [implementation report](phase-2-report.md) and [documented executable cases](test-cases/phase-2.md). Later business phases remain unfinished.

## Recorded domain decisions

- One user can hire protection and also be eligible as a provider through `isSuperhero`.
- Registration atomically creates a user and one zero-balance account. The separate account-creation endpoint is a Phase 3 stub; its final role must be reconciled with automatic wallets. The unique owner constraint prevents a second account.
- Profile updates exclude password hashes, activity, ownership and balances. Deactivation is intended to require zero balance and no unfinished/unpaid booking in either role; that eligibility service remains Phase 3 work.
- Inactive users cannot log in or use existing bearer tokens. Deactivation freezes the user; it does not erase account/history rows. All four tables and six non-cascading foreign keys are verified against MySQL.
- Simulated loading accepts only a positive amount for the authenticated owner's wallet. Its audit/retry policy remains Phase 3 work; no real payment integration is planned.
- Booking parties/price are fixed; either confirmation order leads to unpaid, and explicit customer payment associates one permanent transfer. Entity cases cover sequential behavior; service writes and financial concurrency remain future work.
- IDs are `Long`, money is `BigDecimal` with precision 19/scale 2, and timestamps are `Instant`. One implicit demo currency is assumed. Pricing, rounding, self-transfer/self-booking and eligibility-change policies still need decisions in their owning phases.

## Current security behavior

Only `POST /api/v1/auth/register` and `POST /api/v1/auth/login` are public. All other routes require bearer authentication with a current active database identity. Missing/invalid/expired tokens receive 401. Current database roles are ROLE_USER and optional ROLE_SUPERHERO; ownership gates use actual persisted relationships.

Source spending and history require the wallet owner. Booking reads permit either participant; each confirmation requires its assigned actor; payment requires the customer. Wrong actor receives 403, absent resource 404, invalid fields/JSON/IDs 400. Permitted future-phase operations reach 501. Caller identity comes from the principal, never a body identity field.

Registration uses a named username constraint and retains atomic zero-wallet creation. PasswordEncoder uses BCrypt cost 12 with existing jBCrypt compatibility. Registration limits passwords to 8–72 characters and 72 UTF-8 bytes. JWTs use external HS256 key material, stable user-ID subjects, issuer and timestamps; default lifetime is one hour. There is no refresh/logout endpoint.

## Endpoint inventory

Registration and login are implemented. The table's other success statuses describe future business contracts; those operations currently return 501 after authentication, validation and applicable ownership checks. DTO names live in `com.kingpay.dto.request` and `.response`.

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
| 1 | Maven/JDK 21 build, MySQL schema/startup, ignored external config, repeatable tests | Coverage tooling and reproducible demo funding/setup |
| 2 | Registration/login, PasswordEncoder, JWT, active identity reload, security chain, ownership guards, safe errors and documented cases | P2.1–P2.6 verified; operational limits listed in report |
| 3 | Profile/wallet mappings, repositories, DTOs and authenticated routes | Profile CRUD, wallet creation policy, simulated loads, deactivation eligibility and cases |
| 4 | DTOs/routes, repositories and source/history ownership guards | Actual atomic transfer/ledger/history operations and race/rollback cases |
| 5 | Entities, sequential confirmation tests, routes and participant/actor guards | Booking creation, eligibility, persistence, confirmation services and cases |
| 6 | Unique payment link, entity checks and customer guard | Atomic exactly-once booking payment with concurrency/retry cases |
| 7–8 | Case inventories, phase report, README and UML security page | Remaining cases, coverage, final documentation/UML, narrated demonstration and private submission |

## Verification

`mvn -o test`: **171 tests passed, zero failures/errors/skips**, including 37 HTTP security/MySQL cases, 27 service-guard cases, 14 security-component cases, and existing registration/repository/entity tests. `scripts/verify-skeleton.ps1` passed and `mvn -o -DskipTests package` produced the executable jar. Test evidence is recorded in the Phase 2 report.

The scaffold script checks compilation and structural contracts; the actual tests establish authentication/authorization behavior. No future financial behavior, code coverage percentage or fully working domain CRUD is claimed. HTTP fixtures roll back; committed registration fixtures are cleaned by their unique test identity. Schema action remains `validate`.

`Kingpay.drawio` includes a separate Phase 2 security dependency page. Its original main-entity page is retained. Final UML reconciliation remains Phase 7 work.