# Skeleton status and phase boundaries

This scaffold implements data shapes and draft boundaries only. Its existence does not complete any feature phase in the root `plan.md`. The explicit skeleton delegation overrides that plan's hand-mode restriction only for this bounded scaffold task.

## Recorded domain decisions

- A user can hire protection and also be a superhero. `isSuperhero` marks provider eligibility.
- A user owns one account. The unique account-owner foreign key prevents a second account; creating the initial account remains an explicit endpoint action. A registered user can therefore temporarily have no account before that action. The UML's one-to-one relationship describes the intended funded participant.
- Users can edit profile details. The profile update body excludes password hashes, activity flags, account ownership, and balances.
- `active` lives on `User` and will govern account activity. Deactivation requires zero balance and no unfinished/unpaid bookings in either participant role. Inactive users will not create bookings or send/receive transfers. These rules are not implemented yet.
- User/account/booking rows remain available for history. Successful transfer records have no edit/delete API. No relationship requests cascading removal; actual database foreign-key behavior must be verified in Phase 1/3.
- The demo account-creation body accepts a nonnegative opening balance. The future service derives ownership from the authenticated principal and rejects a second account. Opening funds are a demo-only shortcut, not a successful transfer or a real top-up feature.
- Bookings hold fixed parties/price and exist before money moves. Their optional payment link points to one successful transfer. Standalone transfers have no booking requirement.
- The five agreed enum states remain `Unconfirmed`, `CustomerConfirmed`, `ServiceProviderConfirmed`, `ConfirmedUnpaid`, and `ConfirmedPaid`. Either confirmation order and idempotent repeated confirmations belong to Phase 5.

## Scaffold choices

- Packages remain under `com.kingpay`; the separate transfer lab's exact package/signature contract is not assumed to apply.
- JPA entities retain the main UML fields. IDs use `Long`; money uses `BigDecimal` with database precision 19 and scale 2; timestamps use `Instant`.
- Two decimal places and a single implicit demo currency are draft assumptions. No rounding, currency conversion, or pricing behavior is implemented.
- Constructor/getter/profile-setter boilerplate works. Entity business commands and all service operations throw `UnimplementedOperationException` without changing state. Constructors assign data; they do not enforce business invariants.
- Concrete services reserve the application boundaries. No service interfaces, repositories, mappers, JWT utilities, or security configuration are added before their phase needs them.
- Controllers use constructor injection, accept request DTOs, and return response DTO envelopes. Caller-sensitive methods accept the framework's `Principal`; it may be absent before Phase 2. Services must eventually reject unauthenticated access.
- The narrow skeleton exception handler maps only intentional placeholders to HTTP 501. It is not the final validation/authentication/domain error handler.
- Request/response records separate accepted fields from disclosed fields. Public superhero summaries omit private contact details. Password inputs are write-only and redact their string representations; token responses redact their string representations too.
- Username length 3-50, registration password length 8-128, and profile-field limits are draft input policies. Confirm password rules against the chosen encoder during Phase 2. Updating a profile uses full replacement (`PUT`), including an explicit superhero flag.
- Booking amount comes from the draft creation request and is intended to become fixed at creation. A provider pricing/offer model has not been selected.

## Draft endpoint/access inventory

Every service-backed route below is unfinished and currently reports HTTP 501 for a request that reaches it. Success statuses and access/error rules are future contracts, not verified behavior. DTO names are in `com.kingpay.dto.request` and `.response`.

| Method and path | Request | Response data | Planned access | Planned success | Future error cases |
| --- | --- | --- | --- | --- | --- |
| `POST /api/v1/auth/register` | `RegisterRequest` | `UserResponse` | Public | 201 | Invalid input; duplicate username |
| `POST /api/v1/auth/login` | `LoginRequest` | `AuthResponse` | Public | 200 | Invalid input/credentials; inactive-user policy |
| `GET /api/v1/users/me` | None | `UserResponse` | Authenticated owner | 200 | Missing authentication/user |
| `PUT /api/v1/users/me` | `UpdateUserRequest` | `UserResponse` | Active owner | 200 | Invalid input; duplicate username; invalid eligibility change |
| `DELETE /api/v1/users/me` | None | No body | Active owner | 204 | Nonzero balance; outstanding bookings; missing authentication |
| `GET /api/v1/users/superheroes` | None | `List<SuperheroResponse>` | Authenticated user | 200 | Missing authentication |
| `POST /api/v1/accounts` | `CreateAccountRequest` | `AccountResponse` | Active owner | 201 | Invalid balance; duplicate account; missing authentication |
| `GET /api/v1/accounts/me` | None | `AccountResponse` | Authenticated owner | 200 | Missing account/authentication |
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

| Phase | Scaffold present | Still required before acceptance |
| --- | --- | --- |
| 0: decisions/UML | Main entities, latest domain decisions, draft endpoint inventory | Resolve remaining policies and reconcile final UML, service boundaries, and rubric interpretation |
| 1: foundation | JDK 21/Maven project, entity metadata, validation dependency, compilation/structural script | MySQL driver/configuration/startup; runnable context test; external credentials; demo setup; coverage tooling |
| 2: authentication | Registration/login DTOs and controller/service stubs | Password encoding, JWT, security chain, principal loading, authentication/authorization, documented tests |
| 3: profiles/accounts | Mappings, DTOs, owner-facing routes and deactivation stubs | Repositories/mapping, CRUD/ownership/deactivation rules, demo account funding behavior, documented tests |
| 4: transfers | Request/history DTOs and service/controller stubs | Atomic debit/credit/ledger writes; rollback, ownership, self-transfer and concurrency policies; documented tests |
| 5: booking | Mapping, enum, participant/confirmation route stubs | Eligibility, fixed-price creation, participant access, both confirmation orders/idempotency; documented tests |
| 6: payment | Optional unique transfer link and payment stub | Customer-only atomic payment, retry safety and concurrent duplicate prevention; documented tests |
| 7-8: evidence/submission | This status record and scaffold verification | Full three-case-per-method documentation/tests, coverage, final Javadoc/UML/README, narrated endpoint demonstration, private-repository submission |

Remaining design decisions include superhero-flag changes during outstanding jobs, inactive-user login/history policy, same-account transfers/self-bookings, API failure statuses, provider pricing, and the course's CRUD interpretation. Resolve each before implementing its owning phase.

## Verification limits

Run `scripts/verify-skeleton.ps1` from PowerShell. It compiles production/test sources and checks scaffold contracts without a database. It does not execute the original context-loading test, prove ORM schema behavior, or exercise HTTP/security/financial behavior. Placeholder assertions are stage-specific and should be retired or replaced when their phase is implemented. No unchecked root-plan acceptance criterion is marked complete.
