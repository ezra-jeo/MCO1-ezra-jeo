package com.kingpay.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable proposal terms with one terminal provider response. */
@Entity
@Table(name = "protection_requests")
public class ProtectionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_account_id", nullable = false, updatable = false)
    private Account customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_account_id", nullable = false, updatable = false)
    private Account serviceProvider;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_id", nullable = false, updatable = false)
    private HeroRate rate;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProtectionRequestStatus status = ProtectionRequestStatus.Pending;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Preconditions: Invoked by the persistence provider.
     * <p>Postconditions: An empty persistence instance exists.
     */
    protected ProtectionRequest() {
    }

    /**
     * Copies the published price and parties into a pending request.
     *
     * <p>Preconditions: The service has validated the parties and current rate
     * under the owner and rate locks.
     * <p>Postconditions: Fixed terms and the creation timestamp are assigned;
     * no booking is created and no money moves.
     * @param customer fixed requesting wallet
     * @param rate published price and fixed provider
     */
    public ProtectionRequest(Account customer, HeroRate rate) {
        this.customer = customer;
        this.rate = rate;
        this.serviceProvider = rate.getServiceProvider();
        this.amount = rate.getAmount();
        this.createdAt = Instant.now();
    }

    /**
     * Records the assigned provider's acceptance.
     *
     * <p>Preconditions: The service has authorized the assigned provider.
     * <p>Postconditions: Pending becomes Accepted and the response time is set;
     * repeated acceptance leaves state unchanged. A declined request is rejected.
     * @throws IllegalStateException if already declined
     */
    public void accept() {
        if (status == ProtectionRequestStatus.Declined) {
            throw new IllegalStateException("Request was declined.");
        }
        if (status == ProtectionRequestStatus.Pending) {
            status = ProtectionRequestStatus.Accepted;
            respondedAt = Instant.now();
        }
    }

    /**
     * Records the assigned provider's decline.
     *
     * <p>Preconditions: The service has authorized the assigned provider.
     * <p>Postconditions: Pending becomes Declined and the response time is set;
     * repeated decline leaves state unchanged. An accepted request is rejected.
     * @throws IllegalStateException if already accepted
     */
    public void decline() {
        if (status == ProtectionRequestStatus.Accepted) {
            throw new IllegalStateException("Request was accepted.");
        }
        if (status == ProtectionRequestStatus.Pending) {
            status = ProtectionRequestStatus.Declined;
            respondedAt = Instant.now();
        }
    }

    /**
     * Reads id.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the generated ID; null before persistence
     */
    public Long getId() {
        return id;
    }

    /**
     * Reads customer.
     *
     * <p>Preconditions: The relationship is initialized.
     * <p>Postconditions: State is unchanged.
     * @return the fixed customer wallet
     */
    public Account getCustomer() {
        return customer;
    }

    /**
     * Reads serviceProvider.
     *
     * <p>Preconditions: The relationship is initialized.
     * <p>Postconditions: State is unchanged.
     * @return the fixed provider wallet
     */
    public Account getServiceProvider() {
        return serviceProvider;
    }

    /**
     * Reads rate.
     *
     * <p>Preconditions: The relationship is initialized.
     * <p>Postconditions: State is unchanged.
     * @return the originating rate; its current amount may differ from the snapshot
     */
    public HeroRate getRate() {
        return rate;
    }

    /**
     * Reads amount.
     *
     * <p>Preconditions: The request terms are initialized.
     * <p>Postconditions: State is unchanged.
     * @return the preserved price at submission
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Reads status.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the initial-agreement status
     */
    public ProtectionRequestStatus getStatus() {
        return status;
    }

    /**
     * Reads createdAt.
     *
     * <p>Preconditions: The request terms are initialized.
     * <p>Postconditions: State is unchanged.
     * @return the submission time
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Reads respondedAt.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the response time; null while Pending
     */
    public Instant getRespondedAt() {
        return respondedAt;
    }
}
