#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'src/main/java/com/kingpay'

function Assert-Contract {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "Skeleton verification failed: $Message"
    }
}

function Read-Source {
    param([string]$RelativePath)

    $sourcePath = Join-Path $sourceRoot $RelativePath
    Assert-Contract (Test-Path -LiteralPath $sourcePath) "Missing $RelativePath"
    return Get-Content -LiteralPath $sourcePath -Raw -Encoding UTF8
}

Push-Location -LiteralPath $projectRoot
try {
    # Dependencies must already be cached; the README includes the initial online command.
    & mvn -o -DskipTests test-compile
    Assert-Contract ($LASTEXITCODE -eq 0) 'Maven source/test compilation failed.'

    foreach ($entity in @('User', 'Account', 'Booking', 'TransferRecord')) {
        $source = Read-Source "entity/$entity.java"
        Assert-Contract ($source -match '@Entity\b') "$entity is missing its JPA mapping."
        Assert-Contract ($source -match 'private Long id;') "$entity must use a Long identifier."
        Assert-Contract ($source -notmatch 'CascadeType\.(ALL|REMOVE)') "$entity must preserve referenced history."
    }

    $user = Read-Source 'entity/User.java'
    Assert-Contract ($user -match 'private String passwordHash;') 'User must store a password hash.'
    Assert-Contract ($user -match 'private boolean isSuperhero = false;') 'Superhero eligibility flag is missing.'
    Assert-Contract ($user -match 'private boolean active = true;') 'User activity flag is missing.'
    Assert-Contract ($user -notmatch 'void setActive\(') 'Deactivation must have an explicit operation boundary.'

    $account = Read-Source 'entity/Account.java'
    Assert-Contract ($account -match 'private BigDecimal balance;') 'Account balance must use BigDecimal.'
    Assert-Contract ($account -match '@OneToOne\(') 'Account must have one user owner.'
    Assert-Contract ($account -match 'name = "user_id", nullable = false, unique = true, updatable = false') 'Account ownership must be required, unique, and fixed.'

    $booking = Read-Source 'entity/Booking.java'
    Assert-Contract ($booking -match 'private BookingStatus status = BookingStatus.Unconfirmed;') 'Bookings must initially be unconfirmed.'
    Assert-Contract ($booking -match 'name = "payment_transfer_id", unique = true') 'A transfer must not pay multiple bookings.'
    Assert-Contract ($booking -notmatch 'void set(Amount|Customer|ServiceProvider|Status|PaymentTransfer)\(') 'Booking rules must not be bypassed with general setters.'

    $ledger = Read-Source 'entity/TransferRecord.java'
    Assert-Contract ($ledger -notmatch 'public void set\w+\(') 'The permanent ledger must not expose setters.'
    Assert-Contract ($ledger -match 'private Instant transferDate;') 'Transfer timestamp is missing.'

    $status = Read-Source 'entity/BookingStatus.java'
    foreach ($state in @('Unconfirmed', 'CustomerConfirmed', 'ServiceProviderConfirmed', 'ConfirmedUnpaid', 'ConfirmedPaid')) {
        Assert-Contract ($status -match "\b$state\b") "Missing booking state $state."
    }

    $requestNames = @('RegisterRequest', 'LoginRequest', 'UpdateUserRequest', 'LoadAccountRequest', 'CreateBookingRequest', 'TransferRequest')
    foreach ($name in $requestNames) {
        $request = Read-Source "dto/request/$name.java"
        Assert-Contract ($request -match "public record $name\(") "Missing request record $name."
        Assert-Contract ($request -notmatch '\b(passwordHash|active|status|paymentTransferId|customerAccountId|userId)\b') "$name exposes server-owned state."
    }
    foreach ($name in @('RegisterRequest', 'LoginRequest')) {
        $request = Read-Source "dto/request/$name.java"
        Assert-Contract ($request -match 'JsonProperty.Access.WRITE_ONLY') "$name password must be write-only."
        Assert-Contract ($request -match '\[redacted\]') "$name must redact its string representation."
    }
    $loadRequest = Read-Source 'dto/request/LoadAccountRequest.java'
    Assert-Contract ($loadRequest -match '@NotNull') 'A load amount must be supplied.'
    Assert-Contract ($loadRequest -match '@DecimalMin\(value = "0.00", inclusive = false\)') 'A load amount must be positive.'
    Assert-Contract ($loadRequest -match '@Digits\(integer = 17, fraction = 2\)') 'A load amount must fit the money precision.'
    Assert-Contract ($loadRequest -notmatch '\b(accountId|ownerId|balance|openingBalance)\b') 'Loading must target the caller and accept only an amount.'
    Assert-Contract (-not (Test-Path -LiteralPath (Join-Path $sourceRoot 'dto/request/CreateAccountRequest.java'))) 'Account creation no longer accepts an opening-balance DTO.'

    foreach ($name in @('UserResponse', 'SuperheroResponse', 'AccountResponse', 'BookingResponse', 'TransferResponse', 'AuthResponse', 'ApiResponse')) {
        $response = Read-Source "dto/response/$name.java"
        Assert-Contract ($response -notmatch '(?i)\b(password|passwordHash)\b') "$name must not expose credentials."
        $responseDeclaration = $response -replace '(?s)/\*.*?\*/', ''
        Assert-Contract ($responseDeclaration -notmatch '\b(User|Account|Booking|TransferRecord)\s+\w+[,)]') "$name must not embed persistence entities."
    }
    $authResponse = Read-Source 'dto/response/AuthResponse.java'
    Assert-Contract ($authResponse -match '\[redacted\]') 'AuthResponse must redact its token in string representations.'

    $expectedMethodCounts = @{ Auth = 2; User = 4; Account = 3; Transfer = 2; Booking = 6 }
    $expectedPlaceholderCounts = @{ Auth = 0; User = 0; Account = 0; Transfer = 0; Booking = 1 }
    $routeBases = @{ Auth = 'auth'; User = 'users'; Account = 'accounts'; Transfer = 'transfers'; Booking = 'bookings' }
    foreach ($name in $expectedMethodCounts.Keys) {
        $service = Read-Source "service/${name}Service.java"
        $expectedCount = $expectedMethodCounts[$name]
        $throws = [regex]::Matches($service, 'throw new UnimplementedOperationException\(').Count
        Assert-Contract ($throws -eq $expectedPlaceholderCounts[$name]) "$name service has an unexpected placeholder count."
        if ($name -ne 'Auth') {
            Assert-Contract ($service -notmatch '\breturn\s+(null|true|false)\s*;') "$name service must not return fake results."
        }

        $controller = Read-Source "controller/${name}Controller.java"
        Assert-Contract ($controller.Contains('@RequestMapping("/api/v1/' + $routeBases[$name] + '")')) "$name controller has an unexpected route base."
        $routeCount = [regex]::Matches($controller, '@(Get|Post|Put|Delete)Mapping\b').Count
        Assert-Contract ($routeCount -eq $expectedCount) "$name controller has an unexpected endpoint count."
        Assert-Contract ($controller.Contains("private final ${name}Service service;")) "$name controller must use constructor injection."
    }

    $accountService = Read-Source 'service/AccountService.java'
    Assert-Contract ($accountService -match 'AccountResponse create\(Principal caller\)') 'Account creation must not accept an opening balance.'
    $accountController = Read-Source 'controller/AccountController.java'
    Assert-Contract ($accountController -match '@PostMapping\("/me/load"\)') 'Own-wallet loading route is missing.'
    Assert-Contract ($accountController -match 'load\(@Valid @RequestBody LoadAccountRequest request, Principal caller\)') 'Loading must validate its amount and use the principal.'

    $transferController = Read-Source 'controller/TransferController.java'
    Assert-Contract ($transferController -notmatch '@(Put|Patch|Delete)Mapping') 'Transfer history must not expose mutation endpoints.'
    $handler = Read-Source 'exception/SkeletonExceptionHandler.java'
    Assert-Contract ($handler -match 'HttpStatus.NOT_IMPLEMENTED') 'Skeleton operations must report HTTP 501.'
    Assert-Contract ($handler -match '@ExceptionHandler\(UnimplementedOperationException.class\)') 'Only intentional placeholders should map to HTTP 501.'

    Write-Output 'PASS: Java/test sources compile; entity, DTO, route, and remaining placeholder contracts match the current phase.'
    Write-Output 'This is a scaffold check. It does not verify authentication, database startup, business rules, or financial guarantees.'
}
finally {
    Pop-Location
}
