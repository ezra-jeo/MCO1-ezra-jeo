package com.kingpay.security;

import java.util.Set;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** External read-only admin allowlist; no public request can grant access.
 * <p>Preconditions: values come from trusted application configuration.
 * <p>Postconditions: absent/empty configuration grants nobody admin access.
 * @param userIds stable IDs permitted to read the global overview */
@Validated
@ConfigurationProperties(prefix = "kingpay.admin")
public record AdminSettings(Set<@Positive Long> userIds) {
    /** Preconditions: configuration binder supplies optional IDs. Postconditions: allowlist is immutable and defaults empty.
     * @param userIds configured positive IDs, or null for no admins */
    public AdminSettings {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
    }
}
