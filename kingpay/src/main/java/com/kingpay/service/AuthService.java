package com.kingpay.service;

import com.kingpay.dto.request.LoginRequest;
import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.dto.response.AuthResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.User;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.UserRepository;

import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Transactional registration and stateless username/password login. */
@Service
@Validated
public class AuthService {
    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokens;
    private static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Receives identity persistence, password authentication, and token issuance.
     * <p>Preconditions: dependencies are supplied by Spring.
     * <p>Postconditions: registration/login share the configured security infrastructure.
     * @param userRepository identity repository
     * @param accountRepository wallet repository
     * @param passwordEncoder required encoding abstraction
     * @param authenticationManager credential verifier
     * @param tokens access-token issuer
     */
    @Autowired
    public AuthService(UserRepository userRepository, AccountRepository accountRepository,
            PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager, TokenService tokens) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
    }

    /**
     * Registers a user and zero-balance wallet within one transaction.
     *
     * <p>Preconditions: request is non-null and its registration fields are valid.
     * <p>Postconditions: on success, both records persist and the response excludes
     * credentials. A duplicate username causes a conflict; any write failure rolls
     * back both records. The database constraint remains authoritative for races.
     * @param request validated registration data
     * @return the saved user's public response fields
     * @throws IllegalArgumentException if request is null
     * @throws UsernameAlreadyExistsException if the precheck or named unique constraint finds a duplicate
     * @throws DataIntegrityViolationException if another database integrity rule fails
     */
    @Transactional 
    public UserResponse register(@Valid RegisterRequest request) {
        if (request == null) { throw new IllegalArgumentException("Register argument is null."); }
        if (userRepository.existsByUsername(request.username())) {
            throw new UsernameAlreadyExistsException();
        }
    
        validatePasswordLength(request.password());

        User user = new User(
            request.username(),
            passwordEncoder.encode(request.password()),
            request.fullName(),
            request.phone(),
            request.email(),
            request.isSuperhero()
        );

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isUsernameConflict(exception)) {
                throw new UsernameAlreadyExistsException(exception);
            }
            throw exception;
        }

        Account account = new Account(user, BigDecimal.ZERO);
        accountRepository.saveAndFlush(account);

        return new UserResponse(
            user.getId(), 
            user.getUsername(), 
            user.getFullName(),
            user.getPhone(),
            user.getEmail(),
            user.isSuperhero(),
            user.isActive()
        );

    }

    /**
     * Identifies only the named username constraint, including qualified database names.
     * <p>Preconditions: exception is a non-null persistence integrity failure.
     * <p>Postconditions: no data changes; unrelated or unnamed constraints are not classified as usernames.
     * @param exception repository failure whose cause chain is inspected
     * @return whether the username unique constraint caused the failure
     */
    private boolean isUsernameConflict(DataIntegrityViolationException exception) {
        return com.kingpay.exception.UsernameConflicts.matches(exception);
    }

    /**
     * Authenticates active credentials and returns a signed expiring bearer token.
     *
     * <p>Preconditions: request fields pass validation and the password fits BCrypt's UTF-8 limit.
     * <p>Postconditions: no writes; successful login issues a token without returning credentials.
     * @param request submitted login credentials
     * @return signed token, bearer type, and expiry
     * @throws InvalidRequestException if request or password length is invalid
     * @throws org.springframework.security.core.AuthenticationException if credentials are invalid/inactive
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Login request is required.");
        }
        validatePasswordLength(request.password());
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        User user = userRepository.findByUsername(authentication.getName()).filter(User::isActive)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials."));
        return tokens.issue(user);
    }

    /**
     * Rejects oversized UTF-8 passwords before BCrypt can reject or truncate them.
     * <p>Preconditions: password came from a validated request.
     * <p>Postconditions: no values are changed or disclosed; unsupported lengths fail safely.
     * @param password submitted secret, never logged
     * @throws InvalidRequestException if null or longer than 72 UTF-8 bytes
     */
    private void validatePasswordLength(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRequestException("Password exceeds the supported UTF-8 length.");
        }
    }
}
