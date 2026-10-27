package com.kingpay.entity;

import com.kingpay.exception.UnimplementedOperationException;
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
     * <p>Preconditions: The future service validates all supplied data before persistence.
     * <p>Postconditions: Initial fields are assigned; ownership and financial rules are not implemented.
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
     * Reserves the account debit operation.
     *
     * <p>Preconditions: Arguments satisfy the documented operation contract.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param amount requested amount
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void debit(BigDecimal amount) {
        throw new UnimplementedOperationException("Account debit");
    }

    /**
     * Reserves the account credit operation.
     *
     * <p>Preconditions: Arguments satisfy the documented operation contract.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param amount requested amount
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void credit(BigDecimal amount) {
        throw new UnimplementedOperationException("Account credit");
    }
}
