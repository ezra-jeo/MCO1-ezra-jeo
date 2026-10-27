package com.kingpay.service;

import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.entity.User;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.UserRepository;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests byte-length policy directly, independently of controller character-count validation. */
class PasswordPolicyTest {
    private UserRepository users;
    private AccountRepository accounts;
    private PasswordEncoder encoder;
    private AuthService service;

    /** Preconditions: none. Postconditions: fresh isolated collaborators; no repository/encoder work. */
    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        accounts = mock(AccountRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new AuthService(users, accounts, encoder, mock(AuthenticationManager.class), mock(TokenService.class));
    }

    /**
     * P2-PW01 through P2-PW05: reject unsupported UTF-8 lengths before hashing/writes.
     * <p>Preconditions: test dependencies are initialized and username is available.
     * <p>Postconditions: supported input reaches encoding; rejected input produces no writes.
     * @param id documented case ID
     * @param password synthetic boundary input
     * @param accepted whether the 72-byte policy accepts it
     */
    @ParameterizedTest(name = "{0}: UTF-8 password boundary, accepted={2}")
    @MethodSource("passwordCases")
    void enforceByteLimit(String id, String password, boolean accepted) {
        RegisterRequest request = new RegisterRequest("edge-user", password, "Test", "000", "test@example.test", false);
        if (accepted) {
            when(encoder.encode(password)).thenReturn("encoded");
            when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                ReflectionTestUtils.setField(user, "id", 1L);
                return user;
            });
            assertThat(service.register(request).id()).isEqualTo(1L);
            verify(encoder).encode(password);
        } else {
            assertThrows(InvalidRequestException.class, () -> service.register(request));
            verifyNoInteractions(encoder, accounts);
            verify(users, never()).saveAndFlush(any(User.class));
        }
    }

    /** Preconditions: none. Postconditions: only synthetic boundary passwords are supplied.
     * @return exact-limit, oversized, Unicode and null scenarios */
    private static Stream<Arguments> passwordCases() {
        return Stream.of(Arguments.of("P2-PW01", "a".repeat(72), true),
                Arguments.of("P2-PW02", "a".repeat(73), false),
                Arguments.of("P2-PW03", "\u00e9".repeat(36), true),
                Arguments.of("P2-PW04", "\u00e9".repeat(37), false),
                Arguments.of("P2-PW05", null, false));
    }
}
