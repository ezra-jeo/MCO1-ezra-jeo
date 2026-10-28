package com.kingpay.entity;

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
     * <p>Preconditions: The service validates all supplied data before persistence.
     * <p>Postconditions: Initial fields are assigned; this constructor does not authorize parties or move funds.
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
     * Records the customer's completion confirmation without moving money.
     *
     * <p>Preconditions: status is initialized and the application service has
     * verified that the caller is this booking's customer.
     * <p>Postconditions: Unconfirmed becomes CustomerConfirmed;
     * ServiceProviderConfirmed becomes ConfirmedUnpaid. All other states remain
     * unchanged, so repeated confirmations cannot reverse completion or payment.
     * Balances and the payment transfer link are unchanged.
     */
    public void confirmCustomer() {
        if (this.status == BookingStatus.Unconfirmed) {
            this.status = BookingStatus.CustomerConfirmed;
        } else if (this.status == BookingStatus.ServiceProviderConfirmed) {
            this.status = BookingStatus.ConfirmedUnpaid;
        }
    }

    /**
     * Records the service provider's completion confirmation without moving money.
     *
     * <p>Preconditions: status is initialized and the application service has
     * verified that the caller is this booking's assigned service provider.
     * <p>Postconditions: Unconfirmed becomes ServiceProviderConfirmed;
     * CustomerConfirmed becomes ConfirmedUnpaid. All other states remain
     * unchanged, so repeated confirmations cannot reverse completion or payment.
     * Balances and the payment transfer link are unchanged.
     */
    public void confirmServiceProvider() {
        if (this.status == BookingStatus.Unconfirmed) {
            this.status = BookingStatus.ServiceProviderConfirmed;
        } else if (this.status == BookingStatus.CustomerConfirmed) {
            this.status = BookingStatus.ConfirmedUnpaid;
        }
    }

    /**
     * Links a matching transfer and marks a completed unpaid booking as paid.
     *
     * <p>Preconditions: the booking's status, participants, and amount are
     * initialized; a non-null transfer has initialized accounts and amount.
     * All participant accounts have non-null persisted IDs.
     * The application service authorizes the customer and coordinates successful
     * money movement, ledger persistence, and this update in one transaction.
     * <p>Postconditions: on success, paymentTransfer references transfer and status
     * is ConfirmedPaid. Rejected inputs leave both fields unchanged. This method
     * does not debit or credit accounts or persist the supplied transfer.
     * <p>Amounts are compared numerically, ignoring scale. Account matching
     * compares primitive long ID values, independently of object identity.
     * @param transfer the successful transfer intended to pay this booking
     * @throws IllegalArgumentException if transfer is null, its accounts or amount
     * do not match, status is not ConfirmedUnpaid, or a payment is already linked
     */
    public void markPaid(TransferRecord transfer) {
        if (transfer == null) {
            throw new IllegalArgumentException("TransferRecord value is null.");
        }

        if (transfer.getSourceAccount().getId().longValue() != this.customer.getId().longValue()
                || transfer.getDestinationAccount().getId().longValue() != this.serviceProvider.getId().longValue()
                || transfer.getAmount().compareTo(this.amount) != 0) {
            throw new IllegalArgumentException("TransferRecord is not for the booking.");
        }

        if (this.status != BookingStatus.ConfirmedUnpaid) {
            throw new IllegalArgumentException("Both parties have not confirmed protection resolution.");
        }

        if (this.paymentTransfer != null) {
            throw new IllegalArgumentException("A transfer has already been recorded");
        }

        this.paymentTransfer = transfer;
        this.status = BookingStatus.ConfirmedPaid;
    }
}
