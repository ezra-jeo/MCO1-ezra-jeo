package com.kingpay.entity;

import com.kingpay.exception.InsufficientBalanceException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated debit/credit tests; cases are documented in test-cases/account.md.
 * No Spring context or database is required.
 */
class AccountTest {

    /**
     * Verifies ACC-D01: a smaller positive debit subtracts the requested amount.
     * <p>Preconditions: balance is 100.00 and amount is 40.00.
     * <p>Postconditions: the operation returns normally and balance is 60.00.
     */
    @Test
    @DisplayName("ACC-D01: debit a positive amount within the available balance")
    void debitPositiveAmountReducesBalance() {
        Account account = createAccount("100.00");

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    /**
     * Verifies ACC-D02: the complete balance may be debited.
     * <p>Preconditions: balance and amount are both 100.00.
     * <p>Postconditions: the operation returns normally and balance is zero.
     */
    @Test
    @DisplayName("ACC-D02: debit the exact available balance to zero")
    void debitExactBalanceLeavesZero() {
        Account account = createAccount("100.00");

        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Verifies ACC-D03: fractional debit arithmetic preserves decimal value.
     * <p>Preconditions: balance is 100.10 and amount is 0.20.
     * <p>Postconditions: balance is exactly 99.90 numerically.
     */
    @Test
    @DisplayName("ACC-D03: debit fractional currency without precision loss")
    void debitFractionalAmountPreservesDecimalValue() {
        Account account = createAccount("100.10");

        account.debit(new BigDecimal("0.20"));

        assertThat(account.getBalance()).isEqualByComparingTo("99.90");
    }

    /**
     * Verifies ACC-D04: an overdraft is rejected without a partial debit.
     * <p>Preconditions: balance is 100.00 and amount is 100.01.
     * <p>Postconditions: InsufficientBalanceException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-D04: reject insufficient funds and preserve the balance")
    void debitInsufficientFundsLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(InsufficientBalanceException.class,
                () -> account.debit(new BigDecimal("100.01")));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Verifies ACC-D05: null is rejected before arithmetic.
     * <p>Preconditions: balance is 100.00 and amount is null.
     * <p>Postconditions: IllegalArgumentException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-D05: reject a null debit and preserve the balance")
    void debitNullAmountLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(IllegalArgumentException.class, () -> account.debit(null));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Verifies ACC-D06: a zero debit is rejected.
     * <p>Preconditions: balance is 100.00 and amount is zero.
     * <p>Postconditions: IllegalArgumentException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-D06: reject a zero debit and preserve the balance")
    void debitZeroAmountLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(IllegalArgumentException.class, () -> account.debit(BigDecimal.ZERO));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Verifies ACC-D07: a negative debit cannot increase available funds.
     * <p>Preconditions: balance is 100.00 and amount is -10.00.
     * <p>Postconditions: IllegalArgumentException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-D07: reject a negative debit and preserve the balance")
    void debitNegativeAmountLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(IllegalArgumentException.class,
                () -> account.debit(new BigDecimal("-10.00")));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Verifies ACC-C01: a positive credit increases an existing balance.
     * <p>Preconditions: balance is 100.00 and amount is 40.00.
     * <p>Postconditions: the operation returns normally and balance is 140.00.
     */
    @Test
    @DisplayName("ACC-C01: credit a positive amount to an existing balance")
    void creditPositiveAmountIncreasesBalance() {
        Account account = createAccount("100.00");

        account.credit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("140.00");
    }

    /**
     * Verifies ACC-C02: a zero-balance wallet can receive its first credit.
     * <p>Preconditions: balance is zero and amount is 25.00.
     * <p>Postconditions: the operation returns normally and balance is 25.00.
     */
    @Test
    @DisplayName("ACC-C02: credit an initially empty wallet")
    void creditEmptyWalletIncreasesBalance() {
        Account account = createAccount("0.00");

        account.credit(new BigDecimal("25.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("25.00");
    }

    /**
     * Verifies ACC-C03: fractional credit arithmetic preserves decimal value.
     * <p>Preconditions: balance is 0.10 and amount is 0.20.
     * <p>Postconditions: balance is exactly 0.30 numerically.
     */
    @Test
    @DisplayName("ACC-C03: credit fractional currency without precision loss")
    void creditFractionalAmountPreservesDecimalValue() {
        Account account = createAccount("0.10");

        account.credit(new BigDecimal("0.20"));

        assertThat(account.getBalance()).isEqualByComparingTo("0.30");
    }

    /**
     * Verifies ACC-C04: null is rejected before arithmetic.
     * <p>Preconditions: balance is 100.00 and amount is null.
     * <p>Postconditions: IllegalArgumentException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-C04: reject a null credit and preserve the balance")
    void creditNullAmountLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(IllegalArgumentException.class, () -> account.credit(null));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Verifies ACC-C05: a zero credit is rejected.
     * <p>Preconditions: balance is 100.00 and amount is zero.
     * <p>Postconditions: IllegalArgumentException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-C05: reject a zero credit and preserve the balance")
    void creditZeroAmountLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(IllegalArgumentException.class, () -> account.credit(BigDecimal.ZERO));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Verifies ACC-C06: a negative credit cannot reduce available funds.
     * <p>Preconditions: balance is 100.00 and amount is -10.00.
     * <p>Postconditions: IllegalArgumentException is thrown; balance stays 100.00.
     */
    @Test
    @DisplayName("ACC-C06: reject a negative credit and preserve the balance")
    void creditNegativeAmountLeavesBalanceUnchanged() {
        Account account = createAccount("100.00");

        assertThrows(IllegalArgumentException.class,
                () -> account.credit(new BigDecimal("-10.00")));

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Creates an independent in-memory account in a chosen existing-wallet state.
     * <p>Preconditions: balance is a valid nonnegative decimal string.
     * <p>Postconditions: a new account with a synthetic owner and no database ID exists.
     * @param balance starting balance for the operation under test
     * @return the isolated account fixture
     */
    private Account createAccount(String balance) {
        User owner = new User("account-owner", "test-only-hash", "Account Owner",
                "00000000000", "owner@example.test", false);
        return new Account(owner, new BigDecimal(balance));
    }
}
