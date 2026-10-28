package com.kingpay.service;

import com.kingpay.dto.request.CreateProtectionRequest;
import com.kingpay.dto.request.PublishRateRequest;
import com.kingpay.entity.*;
import com.kingpay.exception.*;
import com.kingpay.repository.*;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Isolated R1 publication/proposal rules; real SQL/race proof lives in RateRequestPersistenceTest. */
class RateRequestServiceTest {
    enum Operation { PUBLISH, RATE, CREATE, READ, LIST, ACCEPT, DECLINE }
    private final HeroRateRepository rates = mock(HeroRateRepository.class);
    private final ProtectionRequestRepository requests = mock(ProtectionRequestRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final CurrentUser identities = new CurrentUser(users, entities);
    private final HeroRateService publications = new HeroRateService(rates, accounts, identities, entities);
    private final ProtectionRequestService service = new ProtectionRequestService(requests, rates, accounts, bookings, identities, entities);
    private User customer;
    private User provider;
    private Account customerWallet;
    private Account providerWallet;
    private HeroRate rate;
    private ProtectionRequest proposal;

    /** Preconditions: mocks initialized. Postconditions: reverse-ID owners, price 100 and pending proposal fixtures. */
    @BeforeEach
    void fixtures() {
        customer = user("customer", 2L, false); provider = user("provider", 1L, true);
        User outsider = user("outsider", 3L, false);
        customerWallet = new Account(customer, BigDecimal.ZERO); providerWallet = new Account(provider, BigDecimal.ZERO);
        ReflectionTestUtils.setField(customerWallet,"id",20L); ReflectionTestUtils.setField(providerWallet,"id",10L);
        rate = new HeroRate(providerWallet,new BigDecimal("100.00")); ReflectionTestUtils.setField(rate,"id",5L);
        proposal = new ProtectionRequest(customerWallet,rate); ReflectionTestUtils.setField(proposal,"id",40L);
        when(users.findByUsername("customer")).thenReturn(Optional.of(customer));
        when(users.findByUsername("provider")).thenReturn(Optional.of(provider));
        when(users.findByUsername("outsider")).thenReturn(Optional.of(outsider));
        when(accounts.findByUser_Id(2L)).thenReturn(Optional.of(customerWallet));
        when(accounts.findByUser_Id(1L)).thenReturn(Optional.of(providerWallet));
        when(rates.findByServiceProvider_User_Id(1L)).thenReturn(Optional.of(rate));
        when(requests.findById(40L)).thenReturn(Optional.of(proposal));
        when(rates.saveAndFlush(any(HeroRate.class))).thenAnswer(call->{HeroRate row=call.getArgument(0);ReflectionTestUtils.setField(row,"id",5L);return row;});
        when(requests.saveAndFlush(any(ProtectionRequest.class))).thenAnswer(call->{ProtectionRequest row=call.getArgument(0);ReflectionTestUtils.setField(row,"id",40L);return row;});
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(call->{Booking row=call.getArgument(0);ReflectionTestUtils.setField(row,"id",30L);return row;});
    }

    /** Preconditions: active provider and valid boundary price. Postconditions: own publication updated, old proposal preserved.
     * @param amount exact price boundary */
    @ParameterizedTest @ValueSource(strings={"100.00","0.01","99999999999999999.99"})
    void ownPublicationUsesExactPriceAndOwnerLock(String amount) {
        var result=publications.publish(new PublishRateRequest(new BigDecimal(amount)),()->"provider");
        assertThat(result.serviceProviderUserId()).isEqualTo(1L);
        assertThat(result.amount()).isEqualByComparingTo(amount);
        assertThat(proposal.getAmount()).isEqualByComparingTo("100");
        var order=inOrder(entities,rates);
        order.verify(entities).refresh(provider,LockModeType.PESSIMISTIC_WRITE);
        order.verify(entities).refresh(rate,LockModeType.PESSIMISTIC_WRITE);
        order.verify(rates).saveAndFlush(rate);
        noMoney();
    }

    /** Preconditions: provider has no publication. Postconditions: one current rate inserted for own wallet. */
    @Test
    void firstPublicationCreatesOwnRate() {
        when(rates.findByServiceProvider_User_Id(1L)).thenReturn(Optional.empty());
        assertThat(publications.publish(new PublishRateRequest(BigDecimal.TEN),()->"provider").id()).isEqualTo(5L);
        verify(rates).saveAndFlush(argThat(row->row.getServiceProvider()==providerWallet));
    }

    /** Preconditions: malformed amount. Postconditions: rejected before persistence.
     * @param amount null/nonpositive/excess scale or range */
    @ParameterizedTest @NullSource @ValueSource(strings={"0","-1","1.001","100000000000000000"})
    void invalidPublicationDoesNotWrite(String amount) {
        PublishRateRequest input=new PublishRateRequest(amount==null?null:new BigDecimal(amount));
        assertThrows(InvalidRequestException.class,()->publications.publish(input,()->"provider"));
        verifyNoInteractions(accounts,rates);
    }

    /** Preconditions: null bodies. Postconditions: explicit invalid-request failures before persistence. */
    @Test
    void nullBodiesAreRejected() {
        assertThrows(InvalidRequestException.class,()->publications.publish(null,()->"provider"));
        assertThrows(InvalidRequestException.class,()->service.create(null,()->"customer"));
        verifyNoInteractions(accounts,rates,requests,bookings);
    }

    /** Preconditions: caller not superhero. Postconditions: cannot publish anyone's rate. */
    @Test
    void ordinaryUserCannotPublish() {
        assertThrows(AccessDeniedException.class,()->publications.publish(new PublishRateRequest(BigDecimal.TEN),()->"customer"));
        verifyNoInteractions(rates);
    }

    /** Preconditions: eligible user has no wallet. Postconditions: missing-wallet error, no publication. */
    @Test
    void accountlessProviderCannotPublish() {
        when(accounts.findByUser_Id(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,()->publications.publish(new PublishRateRequest(BigDecimal.TEN),()->"provider"));
        verify(rates,never()).saveAndFlush(any());
    }

    /** Preconditions: active caller. Postconditions: lookup uses user ID and exposes no contact/funds.
     * @param name active reader */
    @ParameterizedTest @ValueSource(strings={"customer","provider","outsider"})
    void activeUsersCanReadPublishedRate(String name) {
        var result=publications.getByUserId(1L,()->name);
        assertThat(result.serviceProviderAccountId()).isEqualTo(10L); assertThat(result.amount()).isEqualByComparingTo("100");
        verify(rates).findByServiceProvider_User_Id(1L);
    }

    /** Preconditions: missing/unavailable rate. Postconditions: lookup returns resource error.
     * @param shape absent, inactive or ineligible */
    @ParameterizedTest @ValueSource(strings={"absent","inactive","ineligible"})
    void unavailableRatesAreHidden(String shape) {
        if(shape.equals("absent")){when(rates.findByServiceProvider_User_Id(1L)).thenReturn(Optional.empty());}
        if(shape.equals("inactive")){provider.deactivate();}
        if(shape.equals("ineligible")){provider.setSuperhero(false);}
        assertThrows(ResourceNotFoundException.class,()->publications.getByUserId(1L,()->"customer"));
    }

    /** Preconditions: malformed hero ID. Postconditions: invalid request, no rate query.
     * @param id absent/nonpositive ID */
    @ParameterizedTest @NullSource @ValueSource(longs={0,-1})
    void invalidHeroLookupIdFails(Long id) {
        assertThrows(InvalidRequestException.class,()->publications.getByUserId(id,()->"customer"));
        verifyNoInteractions(rates);
    }

    /** Preconditions: valid current price and zero funds. Postconditions: Pending snapshot only; acceptance copies same amount.
     * @param amount ordinary/minimum/maximum preserved quote */
    @ParameterizedTest @ValueSource(strings={"100.00","0.01","99999999999999999.99"})
    void requestAndAcceptancePreserveExactServerPrice(String amount) {
        rate.publish(new BigDecimal(amount));
        var submitted=service.create(new CreateProtectionRequest(1L,new BigDecimal(amount)),()->"customer");
        assertThat(submitted.status()).isEqualTo(ProtectionRequestStatus.Pending); assertThat(submitted.bookingId()).isNull();
        verifyNoInteractions(bookings);
        verify(requests).saveAndFlush(any(ProtectionRequest.class));
        // Accept the actual created proposal rather than the separate default fixture.
        var captured=org.mockito.ArgumentCaptor.forClass(ProtectionRequest.class); verify(requests).saveAndFlush(captured.capture());
        when(requests.findById(40L)).thenReturn(Optional.of(captured.getValue()));
        rate.publish(new BigDecimal("150.00"));
        var booking=service.accept(40L,()->"provider");
        assertThat(booking.amount()).isEqualByComparingTo(amount); assertThat(booking.protectionRequestId()).isEqualTo(40L);
        assertThat(booking.status()).isEqualTo(BookingStatus.Unconfirmed); noMoney();
    }

    /** Preconditions: invalid proposal inputs. Postconditions: invalid request before persistence.
     * @param input invalid hero or displayed price */
    @ParameterizedTest @MethodSource("invalidInputs")
    void invalidProposalDoesNotWrite(CreateProtectionRequest input) {
        assertThrows(InvalidRequestException.class,()->service.create(input,()->"customer"));
        verifyNoInteractions(accounts,rates,requests,bookings);
    }

    /** Preconditions: displayed amount no longer matches publication. Postconditions: conflict, no proposal/booking. */
    @Test
    void staleDisplayCannotChooseAnArbitraryPrice() {
        assertThrows(BusinessConflictException.class,()->service.create(new CreateProtectionRequest(1L,new BigDecimal("99")),()->"customer"));
        verify(requests,never()).saveAndFlush(any()); verifyNoInteractions(bookings); noMoney();
    }

    /** Preconditions: selected publication belongs to requester. Postconditions: self-request rejected. */
    @Test
    void selfRequestIsRejected() {
        when(rates.findByServiceProvider_User_Id(2L)).thenReturn(Optional.of(new HeroRate(customerWallet,BigDecimal.TEN)));
        assertThrows(InvalidRequestException.class,()->service.create(new CreateProtectionRequest(2L,BigDecimal.TEN),()->"customer"));
    }

    /** Preconditions: customer wallet or publication absent. Postconditions: 404 domain failure, no insert.
     * @param missingWallet which resource is absent */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void missingCreationResourceFails(boolean missingWallet) {
        if(missingWallet){when(accounts.findByUser_Id(2L)).thenReturn(Optional.empty());}
        else{when(rates.findByServiceProvider_User_Id(1L)).thenReturn(Optional.empty());}
        assertThrows(ResourceNotFoundException.class,()->service.create(input(),()->"customer"));
        verify(requests,never()).saveAndFlush(any());
    }

    /** Preconditions: actor/eligibility changes before refreshed locks. Postconditions: no new engagement.
     * @param shape failed availability rule */
    @ParameterizedTest @ValueSource(strings={"inactive-provider","ineligible-provider","inactive-customer-after-lock"})
    void creationRechecksAvailabilityUnderLocks(String shape) {
        if(shape.equals("inactive-provider")){provider.deactivate();}
        if(shape.equals("ineligible-provider")){provider.setSuperhero(false);}
        if(shape.equals("inactive-customer-after-lock")){doAnswer(call->{customer.deactivate();return null;}).when(entities).refresh(customer,LockModeType.PESSIMISTIC_WRITE);}
        assertThrows(BusinessConflictException.class,()->service.create(input(),()->"customer"));
        verify(requests,never()).saveAndFlush(any());
    }

    /** Preconditions: either assigned participant. Postconditions: fixed private terms returned.
     * @param name selected participant */
    @ParameterizedTest @ValueSource(strings={"customer","provider"})
    void participantsCanReadRequest(String name) { assertThat(service.getById(40L,()->name).amount()).isEqualByComparingTo("100"); }

    /** Preconditions: caller participates in both roles. Postconditions: stable-ID list order and accepted link preserved. */
    @Test
    void listMapsBothRolesAndAcceptedBooking() {
        customer.setSuperhero(true);
        var reverseRate=new HeroRate(customerWallet,BigDecimal.TEN); ReflectionTestUtils.setField(reverseRate,"id",6L);
        var reverse=new ProtectionRequest(providerWallet,reverseRate); ReflectionTestUtils.setField(reverse,"id",41L); reverse.accept();
        var booked=new Booking(reverse); ReflectionTestUtils.setField(booked,"id",31L);
        when(bookings.findByProtectionRequest_Id(41L)).thenReturn(Optional.of(booked));
        when(requests.findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(2L,2L)).thenReturn(List.of(reverse,proposal));
        var result=service.getMine(()->"customer");
        assertThat(result).extracting("id").containsExactly(41L,40L); assertThat(result.getFirst().bookingId()).isEqualTo(31L);
        verify(requests).findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(2L,2L);
    }

    /** Preconditions: no caller proposals. Postconditions: empty list. */
    @Test
    void emptyListIsSuccessful() { assertThat(service.getMine(()->"customer")).isEmpty(); }

    /** Preconditions: outsider reads or wrong actors respond. Postconditions: forbidden before writes.
     * @param action selected unauthorized command */
    @ParameterizedTest @ValueSource(strings={"read-outsider","accept-customer","decline-customer","accept-outsider","decline-outsider"})
    void wrongActorsCannotReadOrRespond(String action) {
        String[] parts=action.split("-"); Principal caller=()->parts[1];
        assertThrows(AccessDeniedException.class,()->{switch(parts[0]){case "read"->service.getById(40L,caller);case "accept"->service.accept(40L,caller);default->service.decline(40L,caller);}});
        verify(requests,never()).saveAndFlush(any()); verify(bookings,never()).saveAndFlush(any());
    }

    /** Preconditions: new acceptance. Postconditions: owners sorted before proposal, then agreement flushed before booking. */
    @Test
    void acceptanceUsesOwnerFirstLocksAndAtomicSaveOrder() {
        service.accept(40L,()->"provider");
        var order=inOrder(entities,requests,bookings);
        order.verify(entities).refresh(provider,LockModeType.PESSIMISTIC_WRITE); order.verify(entities).refresh(customer,LockModeType.PESSIMISTIC_WRITE);
        order.verify(entities).refresh(proposal,LockModeType.PESSIMISTIC_WRITE); order.verify(requests).saveAndFlush(proposal); order.verify(bookings).saveAndFlush(any());
    }

    /** Preconditions: previously accepted job, provider still active but eligibility/customer activity changed. Postconditions: harmless original result. */
    @Test
    void repeatedAcceptanceReturnsOriginalBooking() {
        proposal.accept(); var booked=new Booking(proposal); ReflectionTestUtils.setField(booked,"id",30L);
        when(bookings.findByProtectionRequest_Id(40L)).thenReturn(Optional.of(booked));
        customer.deactivate(); provider.setSuperhero(false);
        assertThat(service.accept(40L,()->"provider").id()).isEqualTo(30L);
        verify(requests,never()).saveAndFlush(any()); verify(bookings,never()).saveAndFlush(any());
    }

    /** Preconditions: pending request. Postconditions: one decline save, repeat preserves response time and creates no booking. */
    @Test
    void declineAndRepeatDoNotCreateBooking() {
        var first=service.decline(40L,()->"provider"); var second=service.decline(40L,()->"provider");
        assertThat(second.status()).isEqualTo(ProtectionRequestStatus.Declined); assertThat(second.respondedAt()).isEqualTo(first.respondedAt());
        verify(requests,times(1)).saveAndFlush(proposal); verifyNoInteractions(bookings);
    }

    /** Preconditions: opposite terminal state. Postconditions: conflict without reversal.
     * @param accepted whether initial state is accepted */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void terminalDecisionCannotBeReversed(boolean accepted) {
        if(accepted){proposal.accept();assertThrows(BusinessConflictException.class,()->service.decline(40L,()->"provider"));}
        else{proposal.decline();assertThrows(BusinessConflictException.class,()->service.accept(40L,()->"provider"));}
        verify(requests,never()).saveAndFlush(any()); verifyNoInteractions(bookings);
    }

    /** Preconditions: pending proposal, customer inactive or hero eligibility removed. Postconditions: new acceptance denied.
     * @param disabledCustomer which availability rule fails */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void acceptanceRechecksBothParticipants(boolean disabledCustomer) {
        if(disabledCustomer){customer.deactivate();}else{provider.setSuperhero(false);}
        assertThrows(BusinessConflictException.class,()->service.accept(40L,()->"provider"));
        assertThat(proposal.getStatus()).isEqualTo(ProtectionRequestStatus.Pending); verifyNoInteractions(bookings);
    }

    /** Preconditions: provider disables while lock waits. Postconditions: direct mutation denied. */
    @Test
    void providerActivityIsRecheckedAfterLock() {
        doAnswer(call->{provider.deactivate();return null;}).when(entities).refresh(provider,LockModeType.PESSIMISTIC_WRITE);
        assertThrows(AccessDeniedException.class,()->service.accept(40L,()->"provider")); verifyNoInteractions(bookings);
    }

    /** Preconditions: pending old work with inactive customer and now-ineligible provider. Postconditions: active assigned provider can decline. */
    @Test
    void obsoletePendingWorkCanStillBeDeclined() {
        customer.deactivate(); provider.setSuperhero(false);
        assertThat(service.decline(40L,()->"provider").status()).isEqualTo(ProtectionRequestStatus.Declined);
    }

    /** Preconditions: booking flush fails after agreement flush. Postconditions: exception propagates for outer rollback. */
    @Test
    void bookingFailurePropagatesAfterAgreementFlush() {
        when(bookings.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("test failure"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.accept(40L,()->"provider"));
        verify(requests).saveAndFlush(proposal);
    }

    /** Preconditions: impossible accepted proposal without its booking. Postconditions: safe server failure rather than duplicate creation. */
    @Test
    void missingAcceptedBookingDoesNotCreateAnother() {
        proposal.accept(); assertThrows(IllegalStateException.class,()->service.accept(40L,()->"provider"));
        verify(bookings,never()).saveAndFlush(any());
    }

    /** Preconditions: no principal. Postconditions: all seven service operations require authentication.
     * @param operation selected method */
    @ParameterizedTest @EnumSource(Operation.class)
    void missingPrincipalFailsEveryOperation(Operation operation) {
        assertThrows(InsufficientAuthenticationException.class,()->invoke(operation,null,40L));
    }

    /** Preconditions: identity absent. Postconditions: no operation accesses domain rows.
     * @param operation selected method */
    @ParameterizedTest @EnumSource(Operation.class)
    void missingIdentityFailsEveryOperation(Operation operation) {
        assertThrows(ResourceNotFoundException.class,()->invoke(operation,()->"absent",40L));
        verifyNoInteractions(rates,accounts,requests,bookings);
    }

    /** Preconditions: inactive direct caller. Postconditions: every method rejects it.
     * @param operation selected method */
    @ParameterizedTest @EnumSource(Operation.class)
    void inactiveIdentityFailsEveryOperation(Operation operation) {
        provider.deactivate(); assertThrows(AccessDeniedException.class,()->invoke(operation,()->"provider",40L));
    }

    /** Preconditions: absent proposal. Postconditions: all identifier methods fail 404 domain condition.
     * @param operation requested identifier command */
    @ParameterizedTest @EnumSource(value=Operation.class,names={"READ","ACCEPT","DECLINE"})
    void missingRequestFails(Operation operation) { assertThrows(ResourceNotFoundException.class,()->invoke(operation,()->"provider",99L)); }

    /** Preconditions: malformed proposal ID. Postconditions: reject before repository lookup.
     * @param operation method
     * @param id invalid ID */
    @ParameterizedTest @MethodSource("invalidIds")
    void invalidRequestIdFails(Operation operation,Long id) {
        assertThrows(InvalidRequestException.class,()->invoke(operation,()->"provider",id)); verifyNoInteractions(requests);
    }

    /** Preconditions: fixtures initialized. Postconditions: no money mutations.
     * @param operation selected method
     * @param caller trusted principal
     * @param id requested proposal ID */
    private void invoke(Operation operation,Principal caller,Long id) {
        switch(operation){case PUBLISH->publications.publish(new PublishRateRequest(BigDecimal.TEN),caller);case RATE->publications.getByUserId(1L,caller);
            case CREATE->service.create(input(),caller);case READ->service.getById(id,caller);case LIST->service.getMine(caller);
            case ACCEPT->service.accept(id,caller);case DECLINE->service.decline(id,caller);}
    }
    /** Preconditions: none. Postconditions: valid proposal fixture returned.
     * @return hero ID/displayed price */
    private CreateProtectionRequest input(){return new CreateProtectionRequest(1L,new BigDecimal("100"));}
    /** Preconditions: synthetic fields. Postconditions: saved-ID identity fixture.
     * @param name login
     * @param id ID
     * @param hero eligibility
     * @return fixture identity */
    private User user(String name,Long id,boolean hero){User value=new User(name,"hash","Test","0","t@example.test",hero);ReflectionTestUtils.setField(value,"id",id);return value;}
    /** Preconditions: fixtures initialized. Postconditions: zero wallets and no wallet saves. */
    private void noMoney(){assertThat(customerWallet.getBalance()).isEqualByComparingTo("0");assertThat(providerWallet.getBalance()).isEqualByComparingTo("0");verify(accounts,never()).saveAndFlush(any());}
    /** Preconditions: none. Postconditions: eight invalid input cases.
     * @return invalid bodies */
    private static Stream<CreateProtectionRequest> invalidInputs(){return Stream.of(new CreateProtectionRequest(null,BigDecimal.ONE),new CreateProtectionRequest(0L,BigDecimal.ONE),new CreateProtectionRequest(-1L,BigDecimal.ONE),new CreateProtectionRequest(1L,null),new CreateProtectionRequest(1L,BigDecimal.ZERO),new CreateProtectionRequest(1L,BigDecimal.ONE.negate()),new CreateProtectionRequest(1L,new BigDecimal("1.001")),new CreateProtectionRequest(1L,new BigDecimal("100000000000000000")));}
    /** Preconditions: none. Postconditions: three identifier methods times three bad IDs.
     * @return operation/ID cases */
    private static Stream<Arguments> invalidIds(){return Stream.of(Operation.READ,Operation.ACCEPT,Operation.DECLINE).flatMap(operation->Stream.of(null,0L,-1L).map(id->Arguments.of(operation,id)));}
}
