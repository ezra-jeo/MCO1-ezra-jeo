package com.kingpay.entity;

import com.kingpay.exception.InsufficientBalanceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Wallet owned by one user; balances are changed only by internal operations. */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true, updatable = false)
    private User user;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Preconditions: Invoked by the persistence provider.
     * <p>Postconditions: An empty persistence instance exists.
     */
    protected Account() {
    }

    /**
     * Assigns the initial data shape without applying business validation.
     *
     * <p>Preconditions: The application service validates all supplied data before persistence.
     * <p>Postconditions: Initial fields are assigned; the application service owns authorization and transactional persistence.
     * @param user initial user
     * @param balance initial balance
     */
    public Account(User user, BigDecimal balance) {
        this.user = user;
        this.balance = balance;
    }

    /**
     * Reads id.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current id; null before persistence
     */
    public Long getId() {
        return id;
    }

    /**
     * Reads balance.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current balance; may be null when not assigned
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * Reads user.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current user; may be null when not assigned
     */
    public User getUser() {
        return user;
    }

    /**
     * Subtracts a positive amount when sufficient funds are available.
     *
     * <p>Preconditions: the current balance is non-null and nonnegative; amount
     * is non-null, positive, and no greater than the current balance.
     * <p>Postconditions: on success, the balance decreases by amount and may be zero.
     * If the amount is invalid or funds are insufficient, the balance is unchanged.
     * @param amount positive amount to subtract from the balance
     * @throws IllegalArgumentException if amount is null, zero, or negative
     * @throws InsufficientBalanceException if amount exceeds the current balance
     */
    public void debit(BigDecimal amount) {
        BigDecimal zero = new BigDecimal("0.0");
        if (amount == null|| amount.compareTo(zero) <= 0) {
            throw new IllegalArgumentException("Amount argument invalid.");
        }

        BigDecimal newBalance = this.balance.subtract(amount);        
        if (newBalance.compareTo(zero) < 0) {
            throw new InsufficientBalanceException("Debit");
        }
        
        this.balance = newBalance; 
        
    }

    /**
     * Adds a positive amount to the balance.
     *
     * <p>Preconditions: the current balance is non-null and nonnegative; amount
     * is non-null and positive.
     * <p>Postconditions: on success, the balance increases by amount. If the amount
     * is invalid, the balance is unchanged.
     * @param amount positive amount to add to the balance
     * @throws IllegalArgumentException if amount is null, zero, or negative
     */
    public void credit(BigDecimal amount) {
        BigDecimal zero = new BigDecimal("0.0");
        if (amount == null || amount.compareTo(zero) <= 0) {
            throw new IllegalArgumentException("Amount argument invalid.");
        }

        BigDecimal newBalance = this.balance.add(amount);
        
        this.balance = newBalance;
    }
}
