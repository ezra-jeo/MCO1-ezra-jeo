package com.kingpay.entity;

import com.kingpay.exception.UnimplementedOperationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Identity and editable profile; one user may also be a superhero. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "is_superhero", nullable = false)
    private boolean isSuperhero = false;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Creates an empty instance for JPA.
     *
     * <p>Preconditions: Invoked by the persistence provider.
     * <p>Postconditions: An empty persistence instance exists.
     */
    protected User() {
    }

    /**
     * Assigns the initial data shape without applying business validation.
     *
     * <p>Preconditions: The future service validates all supplied data before persistence.
     * <p>Postconditions: Initial fields are assigned; ownership and financial rules are not implemented.
     * @param username initial username
     * @param passwordHash initial passwordHash
     * @param fullName initial fullName
     * @param phone initial phone
     * @param email initial email
     * @param isSuperhero initial isSuperhero
     */
    public User(String username, String passwordHash, String fullName, String phone, String email, boolean isSuperhero) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.phone = phone;
        this.email = email;
        this.isSuperhero = isSuperhero;
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
     * Reads username.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current username; may be null when not assigned
     */
    public String getUsername() {
        return username;
    }

    /**
     * Reads passwordHash.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current passwordHash; may be null when not assigned
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Reads fullName.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current fullName; may be null when not assigned
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * Reads phone.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current phone; may be null when not assigned
     */
    public String getPhone() {
        return phone;
    }

    /**
     * Reads email.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return the current email; may be null when not assigned
     */
    public String getEmail() {
        return email;
    }

    /**
     * Reads isSuperhero.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return whether this user is eligible to act as a superhero
     */
    public boolean isSuperhero() {
        return isSuperhero;
    }

    /**
     * Reads active.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: State is unchanged.
     * @return whether this user is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Assigns username.
     *
     * <p>Preconditions: The future service validates and authorizes the change.
     * <p>Postconditions: username holds the supplied value.
     * @param username replacement username
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Assigns passwordHash.
     *
     * <p>Preconditions: The future service validates and authorizes the change.
     * <p>Postconditions: passwordHash holds the supplied value.
     * @param passwordHash replacement passwordHash
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Assigns fullName.
     *
     * <p>Preconditions: The future service validates and authorizes the change.
     * <p>Postconditions: fullName holds the supplied value.
     * @param fullName replacement fullName
     */
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /**
     * Assigns phone.
     *
     * <p>Preconditions: The future service validates and authorizes the change.
     * <p>Postconditions: phone holds the supplied value.
     * @param phone replacement phone
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * Assigns email.
     *
     * <p>Preconditions: The future service validates and authorizes the change.
     * <p>Postconditions: email holds the supplied value.
     * @param email replacement email
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Assigns isSuperhero.
     *
     * <p>Preconditions: The future service validates and authorizes the change.
     * <p>Postconditions: isSuperhero holds the supplied value.
     * @param isSuperhero replacement isSuperhero
     */
    public void setSuperhero(boolean isSuperhero) {
        this.isSuperhero = isSuperhero;
    }

    /**
     * Reserves the user deactivation operation.
     *
     * <p>Preconditions: Arguments satisfy the documented operation contract.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void deactivate() {
        throw new UnimplementedOperationException("User deactivation");
    }
}
