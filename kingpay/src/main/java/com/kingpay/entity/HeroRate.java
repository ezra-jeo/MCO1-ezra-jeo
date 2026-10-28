package com.kingpay.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.Instant;

/** One current flat price per provider; existing requests retain their own snapshots. */
@Entity
@Table(name = "hero_rates")
public class HeroRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_account_id", nullable = false, unique = true, updatable = false)
    private Account serviceProvider;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Preconditions: Invoked by the persistence provider.
     * <p>Postconditions: An empty persistence instance exists.
     */
    protected HeroRate() {
    }

    /**
     * Assigns the provider and initial flat price.
     *
     * <p>Preconditions: The provider is persisted and the service has validated
     * the positive exact price.
     * <p>Postconditions: The initial publication and its timestamps are assigned.
     * @param serviceProvider immutable provider wallet
     * @param amount initial flat price
     */
    public HeroRate(Account serviceProvider, BigDecimal amount) {
        this.serviceProvider = serviceProvider;
        this.amount = amount;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /**
     * Publishes a replacement flat price for future requests.
     *
     * <p>Preconditions: The service has validated the price and ownership under
     * the owner lock.
     * <p>Postconditions: The current price and update timestamp are replaced;
     * existing request snapshots remain unchanged.
     * @param amount replacement flat price
     */
    public void publish(BigDecimal amount) {
        this.amount = amount;
        this.updatedAt = Instant.now();
    }

    /**
     * Reads id.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the persisted ID; null before insertion
     */
    public Long getId() {
        return id;
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
     * Reads amount.
     *
     * <p>Preconditions: The publication is initialized.
     * <p>Postconditions: State is unchanged.
     * @return the current flat price
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Reads createdAt.
     *
     * <p>Preconditions: The publication is initialized.
     * <p>Postconditions: State is unchanged.
     * @return the initial publication time
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Reads updatedAt.
     *
     * <p>Preconditions: The publication is initialized.
     * <p>Postconditions: State is unchanged.
     * @return the latest publication time
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
