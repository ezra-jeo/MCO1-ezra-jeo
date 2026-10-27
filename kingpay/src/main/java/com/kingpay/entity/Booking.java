package com.kingpay.entity;

import com.kingpay.exception.UnimplementedOperationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Protection job with fixed parties/price and an optional successful payment. */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_account_id", nullable = false, updatable = false)
    private Account customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_account_id", nullable = false, updatable = false)
    private Account serviceProvider;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookingStatus status = BookingStatus.Unconfirmed;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transfer_id", unique = true)
    private TransferRecord paymentTransfer;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Preconditions: Invoked by the persistence provider.
     * <p>Postconditions: An empty persistence instance exists.
     */
    protected Booking() {
    }

    /**
     * Assigns the initial data shape without applying business validation.
     *
     * <p>Preconditions: The future service validates all supplied data before persistence.
     * <p>Postconditions: Initial fields are assigned; ownership and financial rules are not implemented.
     * @param customer initial customer
     * @param serviceProvider initial serviceProvider
     * @param amount initial amount
     */
    public Booking(Account customer, Account serviceProvider, BigDecimal amount) {
        this.customer = customer;
        this.serviceProvider = serviceProvider;
        this.amount = amount;
        this.createdAt = Instant.now();
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
     * Reads createdAt.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current createdAt; may be null when not assigned
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Reads customer.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current customer; may be null when not assigned
     */
    public Account getCustomer() {
        return customer;
    }

    /**
     * Reads serviceProvider.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current serviceProvider; may be null when not assigned
     */
    public Account getServiceProvider() {
        return serviceProvider;
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
     * Reads status.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current status; may be null when not assigned
     */
    public BookingStatus getStatus() {
        return status;
    }

    /**
     * Reads paymentTransfer.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current paymentTransfer; may be null when not assigned
     */
    public TransferRecord getPaymentTransfer() {
        return paymentTransfer;
    }

    /**
     * Reserves the customer completion confirmation operation.
     *
     * <p>Preconditions: Arguments satisfy the documented operation contract.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void confirmCustomer() {
        throw new UnimplementedOperationException("Customer completion confirmation");
    }

    /**
     * Reserves the service provider completion confirmation operation.
     *
     * <p>Preconditions: Arguments satisfy the documented operation contract.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void confirmServiceProvider() {
        throw new UnimplementedOperationException("Service provider completion confirmation");
    }

    /**
     * Reserves the booking paid transition operation.
     *
     * <p>Preconditions: Arguments satisfy the documented operation contract.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param transfer requested transfer
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void markPaid(TransferRecord transfer) {
        throw new UnimplementedOperationException("Booking paid transition");
    }
}
