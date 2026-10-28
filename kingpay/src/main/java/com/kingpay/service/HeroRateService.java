package com.kingpay.service;

import com.kingpay.dto.request.PublishRateRequest;
import com.kingpay.dto.response.HeroRateResponse;
import com.kingpay.entity.HeroRate;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.HeroRateRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.security.Principal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** One flat price per hero, read by stable hero user ID. */
@Service @Validated @Transactional(readOnly = true)
public class HeroRateService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");
    private static final int MONEY_SCALE = 2;
    private final HeroRateRepository rates;
    private final AccountRepository accounts;
    private final CurrentUser identities;
    private final EntityManager entities;

    /** Preconditions: dependencies supplied by Spring. Postconditions: no persistence calls.
     * @param rates price persistence
     * @param accounts provider wallets
     * @param identities active identity/owner locking
     * @param entities managed refresh context */
    public HeroRateService(HeroRateRepository rates, AccountRepository accounts, CurrentUser identities, EntityManager entities) {
        this.rates = rates; this.accounts = accounts; this.identities = identities; this.entities = entities;
    }

    /** Preconditions: active superhero wallet and positive exact amount.
     * Postconditions: own unique publication is inserted/updated under owner lock; existing requests/funds unchanged.
     * @param request replacement price only
     * @param caller framework principal
     * @return current own rate */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HeroRateResponse publish(@Valid PublishRateRequest request, Principal caller) {
        BigDecimal amount = validatedAmount(request);
        var actor = identities.lockActive(caller);
        if (!actor.isSuperhero()) { throw new AccessDeniedException("Only superheroes can publish rates."); }
        var wallet = accounts.findByUser_Id(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Provider account not found."));
        HeroRate rate = rates.findByServiceProvider_User_Id(actor.getId()).orElse(null);
        if (rate == null) { rate = new HeroRate(wallet, amount); }
        else { entities.refresh(rate, LockModeType.PESSIMISTIC_WRITE); rate.publish(amount); }
        return response(rates.saveAndFlush(rate));
    }

    /** Preconditions: active caller; provider ID identifies an available published hero.
     * Postconditions: no writes, no private contact/balance data.
     * @param userId hero's stable user ID, not a rate or wallet ID
     * @param caller framework principal
     * @return current published rate
     * @throws ResourceNotFoundException if rate absent or provider unavailable */
    public HeroRateResponse getByUserId(Long userId, Principal caller) {
        identities.active(caller);
        if (userId == null || userId <= 0) { throw new InvalidRequestException("A positive hero user ID is required."); }
        HeroRate rate = rates.findByServiceProvider_User_Id(userId).orElseThrow(() -> new ResourceNotFoundException("Published rate not found."));
        var provider = rate.getServiceProvider().getUser();
        if (!provider.isActive() || !provider.isSuperhero()) { throw new ResourceNotFoundException("Published rate not found."); }
        return response(rate);
    }

    /** Preconditions: none. Postconditions: valid exact normalized amount, or safe rejection before writes.
     * @param request supplied price
     * @return scale-two amount */
    private BigDecimal validatedAmount(PublishRateRequest request) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0
                || request.amount().scale() > MONEY_SCALE || request.amount().compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidRequestException("Rate must be positive and fit 17 integer and 2 fractional digits.");
        }
        return request.amount().setScale(MONEY_SCALE);
    }

    /** Preconditions: persisted publication. Postconditions: values only, no entities.
     * @param rate publication
     * @return public price DTO */
    private HeroRateResponse response(HeroRate rate) {
        return new HeroRateResponse(rate.getId(), rate.getServiceProvider().getUser().getId(), rate.getServiceProvider().getId(), rate.getAmount(), rate.getUpdatedAt());
    }
}
