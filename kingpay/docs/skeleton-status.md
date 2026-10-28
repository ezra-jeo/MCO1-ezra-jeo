# Phase status and endpoint inventory

Phases 2–7 are implemented and verified. See the [Phases 6–7 report](phase-6-7-report.md), [payment cases](test-cases/phase-6.md) and [complete method inventory](method-cases.tsv). Narrated recording, private-repository access and submission remain Phase 8.

## Recorded domain decisions

- One user can hire protection and also be eligible as a provider through `isSuperhero`.
- Registration atomically creates a user and one zero-balance account. The separate account-creation endpoint supports accountless identities and returns 409 when an account already exists. The unique owner constraint prevents a second account.
- Profile updates exclude password hashes, activity, ownership and balances. Deactivation is intended to require zero balance and no unfinished/unpaid booking in either role; the service now enforces those checks before freezing only the user.
- Inactive users cannot log in or use existing bearer tokens. Deactivation freezes the user; it does not erase account/history rows. All four tables and six non-cascading foreign keys are verified against MySQL.
- Simulated loading accepts only a positive amount for the authenticated owner's wallet. Each successful request is a separate credit without a ledger row or idempotency key; no real payment integration is planned.
- Booking parties/price are fixed; either confirmation order leads to unpaid, and explicit customer payment associates one permanent transfer. Standalone transfers, booking creation, private reads and two-party completion are verified; atomic booking payment and duplicate protection are also verified.
- IDs are `Long`, money is `BigDecimal` with precision 19/scale 2, and timestamps are `Instant`. One implicit demo currency is assumed. Amounts use exact DECIMAL(19,2) without rounding. Removing hero eligibility is blocked by outstanding provider work. Self-transfers are rejected. Self-booking is rejected. Creation stores a positive exact price from the request as the agreed quote; there is no provider rate/catalog model.

## Current security behavior

`POST /api/v1/auth/register`, `POST /api/v1/auth/login` and read-only Swagger/OpenAPI resources are public. All business routes require bearer authentication with a current active database identity. Missing/invalid/expired tokens receive 401. Current database roles are ROLE_USER and optional ROLE_SUPERHERO; ownership gates use actual persisted relationships.

Source spending and history require the wallet owner. Booking reads permit either participant; each confirmation requires its assigned actor; payment requires the customer. Wrong actor receives 403, absent resource 404, invalid fields/JSON/IDs 400. Profile/wallet routes now persist their Phase 3 behavior. Standalone transfers/history and booking reads/confirmations return 200; booking creation returns 201. Customer payment returns 200 with the original result on successful repeats; premature/insufficient payment returns 409. No business endpoint remains a 501 placeholder. Caller identity comes from the principal, never a body identity field.

Registration uses a named username constraint and retains atomic zero-wallet creation. PasswordEncoder uses BCrypt cost 12 with existing jBCrypt compatibility. Registration limits passwords to 8–72 characters and 72 UTF-8 bytes. JWTs use external HS256 key material, stable user-ID subjects, issuer and timestamps; default lifetime is one hour. There is no refresh/logout endpoint.

## Endpoint inventory

Registration, login and profile/wallet routes are implemented. Hero summaries include the account ID needed to select a bookable provider. Transfers and owner-only history are implemented. Booking creation, reads and confirmations are implemented. Payment is implemented with fixed-price customer authorization, atomic wallet/ledger/link updates and harmless successful repeats. DTO names live in `com.kingpay.dto.request` and `.response`.

| Method and path | Request | Response data | Access | Success contract | Error cases |
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
| `POST /api/v1/bookings` | `CreateBookingRequest` | `BookingResponse` | Active customer | 201 | Invalid price/provider; missing account; provider ineligible/inactive; self-booking rejected |
| `GET /api/v1/bookings` | None | `List<BookingResponse>` | Authenticated participant | 200 | Missing authentication |
| `GET /api/v1/bookings/{bookingId}` | None | `BookingResponse` | Booking participant | 200 | Missing booking/authentication; forbidden access |
| `POST /api/v1/bookings/{bookingId}/confirmations/customer` | None | `BookingResponse` | Booking customer | 200 | Missing booking/authentication; wrong participant |
| `POST /api/v1/bookings/{bookingId}/confirmations/service-provider` | None | `BookingResponse` | Assigned provider | 200 | Missing booking/authentication; wrong participant |
| `POST /api/v1/bookings/{bookingId}/payment` | None | `BookingResponse` | Booking customer | 200 | Not both confirmed; forbidden access; insufficient funds; capacity/inactive recipient; persistence failure; paid retries return original 200 |
| `GET /api/v1/admin/overview` | None | `AdminOverviewResponse` | Active ID in configured allowlist | 200 | Missing/invalid/inactive bearer 401; unlisted identity 403 |

`DELETE /users/me` means deactivation with retained records. Accounts have no arbitrary balance-update or separate physical-delete route. Bookings have no generic update/delete routes because parties/price are fixed and cancellation remains outside the base model. Confirm how those policies meet the rubric's domain CRUD wording; do not add unsafe mutation merely to provide all four verbs.

## Progress and acceptance

| Phase | Evidence now present | Still required |
| --- | --- | --- |
| 0 | Main entities, recorded decisions, endpoint inventory | Remaining policies and final rubric/CRUD interpretation |
| 1 | Maven/JDK 21 build, MySQL schema/startup, ignored config, repeatable tests/demo and JaCoCo | P1.1–P1.5 verified; coverage added in Phase 7 |
| 2 | Registration/login, PasswordEncoder, JWT, active identity reload, security chain, ownership guards, safe errors and documented cases | P2.1–P2.6 verified; operational limits listed in report |
| 3 | Owner profiles, eligible hero selection, zero-wallet creation, exact loads, guarded deactivation, 92 new cases and demo setup | P3.1–P3.7 verified; limits in Phase 3 report |
| 4 | Atomic transfers, immutable API ledger, owner-only history, ordered locking, SQL rollback and 70 new cases | P4.1–P4.8 verified; limits in Phase 4 report |
| 5 | Fixed-price creation, private both-role reads, either-order/idempotent confirmations, 149 new cases and committed races | P5.1–P5.7 verified; limits in Phase 5 report |
| 6 | Atomic fixed-price payment, original-result retries, four SQL failure stages and six races | P6.1–P6.8 verified; limits in combined report |
| 7 | All 56 method/case mappings, Javadoc, 53 UML source types, coverage and fresh-schema curl proof | P7.1–P7.8 verified; OpenAPI implemented with contract checks; see OpenAPI/admin addendum |
| 8 | Local runnable deliverables and reproducible demonstration sequence | Narrated recording, repository/content/access review and private submission |

## Verification

`mvn -o test`: **550 tests passed, zero failures/errors/skips**, including 41 payment cases, 149 booking cases, the stable-ID wallet regression and all prior regressions. `scripts/verify-skeleton.ps1` passed and `mvn -o -DskipTests package` produced the executable jar. JaCoCo and packaging are generated by `mvn clean verify`. Current evidence is recorded in the Phases 6–7 report.

The scaffold script checks compilation and structural contracts; the actual tests establish authentication, authorization, profile/wallet behavior, atomic standalone transfers, booking completion and atomic duplicate-safe payment. Coverage is reported without a required threshold; generic booking/ledger mutation is intentionally absent under the agreed domain policy. Earlier transactional HTTP fixtures roll back; committed registration, transfer and booking fixtures clean up only their captured test IDs. Schema action remains `validate`.

`Kingpay.drawio` includes separate Phase 2 security, Phase 3 profile/wallet, Phase 4 transfer/history and Phase 5 booking/completion dependency pages. Its main entity associations are retained with corrected zero-wallet/loading notes and optional wallet cardinality. Eight Final pages list all 53 source types and their declarations; exact signatures and references are verified.
Live demo setup was verified twice against the packaged application: demo-customer wallet 733 at 1000.00 and demo-superhero wallet 734 at 250.00. Credentials are in ignored .env.demo. Sequential rerun preserved both IDs and balances; the temporary server was stopped. See scripts/prepare-demo.ps1 and the Phase 3 report.


OpenAPI and admin scope update: see [OpenAPI/admin report](openapi-admin-report.md), [schema](openapi.yaml) and [new cases](test-cases/openapi-admin.md). Admin registration does not grant access: configure its stable ID in KINGPAY_ADMIN_USER_IDS and restart. The current requested identity is ID 6678; credentials are stored only in ignored .env.admin.
