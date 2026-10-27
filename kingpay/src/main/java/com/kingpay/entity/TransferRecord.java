package com.kingpay.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Permanent ledger row for a successful transfer; no ordinary mutation API. */
@Entity
@Table(name = "transfer_records")
public class TransferRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false, updatable = false)
    private Account destinationAccount;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "transfer_date", nullable = false, updatable = false)
    private Instant transferDate;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Preconditions: Invoked by the persistence provider.
     * <p>Postconditions: An empty persistence instance exists.
     */
    protected TransferRecord() {
    }

    /**
     * Assigns the initial data shape without applying business validation.
     *
     * <p>Preconditions: The future service validates all supplied data before persistence.
     * <p>Postconditions: Initial fields are assigned; ownership and financial rules are not implemented.
     * @param sourceAccount initial sourceAccount
     * @param destinationAccount initial destinationAccount
     * @param amount initial amount
     */
    public TransferRecord(Account sourceAccount, Account destinationAccount, BigDecimal amount) {
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.transferDate = Instant.now();
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
     * Reads sourceAccount.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current sourceAccount; may be null when not assigned
     */
    public Account getSourceAccount() {
        return sourceAccount;
    }

    /**
     * Reads destinationAccount.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current destinationAccount; may be null when not assigned
     */
    public Account getDestinationAccount() {
        return destinationAccount;
    }

    /**
     * Reads amount.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current amount; may be null when not assigned
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Reads transferDate.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current transferDate; may be null when not assigned
     */
    public Instant getTransferDate() {
        return transferDate;
    }
}
