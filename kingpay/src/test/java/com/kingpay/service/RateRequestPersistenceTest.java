package com.kingpay.service;

import com.kingpay.dto.request.*;
import com.kingpay.entity.*;
import com.kingpay.exception.*;
import com.kingpay.repository.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** R1 HTTP/MySQL rules and committed races; no outer transaction conceals rollback. */
@SpringBootTest @AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class RateRequestPersistenceTest {
    private static final int WAIT_SECONDS = 30;
    enum Route { PUBLISH, RATE, CREATE, READ, LIST, ACCEPT, DECLINE }
    @Autowired private MockMvc mvc;
    @Autowired private HeroRateService publications;
    @Autowired private ProtectionRequestService service;
    @Autowired private BookingService jobs;
    @Autowired private UserService profiles;
    @Autowired private AccountService wallets;
    @Autowired private TokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private HeroRateRepository rates;
    @Autowired private ProtectionRequestRepository requests;
    @MockitoSpyBean private BookingRepository bookings;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager transactions;
    private User customer;
    private User provider;
    private User outsider;
    private User accountless;
    private Long customerId;
    private Long providerId;
    private Long outsiderId;
    private long requestId;

    /** Preconditions: schema validates. Postconditions: zero wallets, current rate 100 and one pending request committed. */
    @BeforeEach
    void fixtures() {
        String suffix=UUID.randomUUID().toString().replace("-","");
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            customer=users.saveAndFlush(identity("r1c_"+suffix,false)); provider=users.saveAndFlush(identity("r1p_"+suffix,true));
            outsider=users.saveAndFlush(identity("r1o_"+suffix,false)); accountless=users.saveAndFlush(identity("r1n_"+suffix,true));
            customerId=accounts.saveAndFlush(new Account(customer,BigDecimal.ZERO)).getId();
            providerId=accounts.saveAndFlush(new Account(provider,BigDecimal.ZERO)).getId();
            outsiderId=accounts.saveAndFlush(new Account(outsider,BigDecimal.ZERO)).getId();
        });
        publications.publish(new PublishRateRequest(new BigDecimal("100")),()->provider.getUsername());
        requestId=service.create(input("100"),()->customer.getUsername()).id();
    }

    /** Preconditions: own existing rate. Postconditions: PUT updates one row; GET by user ID returns correct public price.
     * @throws Exception on HTTP failure */
    @Test
    void heroPublishesAndLooksUpOneFlatRateByUserId() throws Exception {
        Long rateId=rates.findByServiceProvider_User_Id(provider.getId()).orElseThrow().getId();
        mvc.perform(auth(put("/api/v1/users/me/rate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":150,\"userId\":"+outsider.getId()+"}"),provider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(rateId)).andExpect(jsonPath("$.data.serviceProviderUserId").value(provider.getId()));
        mvc.perform(auth(get("/api/v1/users/"+provider.getId()+"/rate"),customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(150));
        assertThat(rateCount()).isEqualTo(1); assertThat(requestAmount(requestId)).isEqualByComparingTo("100"); noMoney();
    }

    /** Preconditions: hero without publication or wallet. Postconditions: first publish creates once; missing wallet fails.
     * @throws Exception on HTTP failure */
    @Test
    void firstPublicationAndAccountlessFailureAreExplicit() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status->users.findById(outsider.getId()).orElseThrow().setSuperhero(true));
        mvc.perform(auth(put("/api/v1/users/me/rate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":25}"),outsider)).andExpect(status().isOk());
        mvc.perform(auth(put("/api/v1/users/me/rate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":25}"),accountless)).andExpect(status().isNotFound());
        assertThat(rateCount()).isEqualTo(2); noMoney();
    }

    /** Preconditions: malformed publication. Postconditions: 400, publication unchanged.
     * @param body invalid JSON/amount
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings={"{}","{\"amount\":null}","{\"amount\":0}","{\"amount\":-1}","{\"amount\":1.001}","{\"amount\":100000000000000000}","{broken"})
    void invalidPublishedPricesDoNotChangeRate(String body) throws Exception {
        mvc.perform(auth(put("/api/v1/users/me/rate").contentType(MediaType.APPLICATION_JSON).content(body),provider)).andExpect(status().isBadRequest());
        assertThat(rates.findByServiceProvider_User_Id(provider.getId()).orElseThrow().getAmount()).isEqualByComparingTo("100");
    }

    /** Preconditions: ordinary active user. Postconditions: cannot publish a provider rate.
     * @throws Exception on HTTP failure */
    @Test
    void customerCannotPublishRate() throws Exception {
        mvc.perform(auth(put("/api/v1/users/me/rate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"),customer)).andExpect(status().isForbidden());
        assertThat(rateCount()).isEqualTo(1);
    }

    /** Preconditions: published/unpublished/disabled identities. Postconditions: directory only shows priced available heroes.
     * @throws Exception on HTTP failure */
    @Test
    void directoryContainsPublishedPricesAndOmitsUnavailableHeroes() throws Exception {
        String json=mvc.perform(auth(get("/api/v1/users/superheroes"),customer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> selected=read(json,"$.data[?(@.id == "+provider.getId()+")].amount");
        assertThat(selected).hasSize(1); assertThat(new BigDecimal(selected.getFirst().toString())).isEqualByComparingTo("100");
        assertThat(json).doesNotContain(accountless.getUsername(),"\"email\"","\"balance\"","\"passwordHash\"");
        profiles.deactivateMe(()->provider.getUsername());
        mvc.perform(auth(get("/api/v1/users/"+provider.getId()+"/rate"),customer)).andExpect(status().isNotFound());
        String filtered=mvc.perform(auth(get("/api/v1/users/superheroes"),customer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(filtered).doesNotContain(provider.getUsername());
    }

    /** Preconditions: new customer request includes forged state/party/price fields. Postconditions: server-derived Pending terms only.
     * @throws Exception on HTTP failure */
    @Test
    void customerSubmissionCreatesOnlyAServerPricedPendingRequest() throws Exception {
        String body="{\"serviceProviderUserId\":"+provider.getId()+",\"expectedAmount\":100,\"amount\":1,\"customerAccountId\":"+outsiderId+",\"serviceProviderAccountId\":"+outsiderId+",\"status\":\"Accepted\",\"bookingId\":999}";
        mvc.perform(auth(post("/api/v1/protection-requests").contentType(MediaType.APPLICATION_JSON).content(body),customer))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("Pending")).andExpect(jsonPath("$.data.amount").value(100))
                .andExpect(jsonPath("$.data.customerAccountId").value(customerId)).andExpect(jsonPath("$.data.serviceProviderAccountId").value(providerId))
                .andExpect(jsonPath("$.data.bookingId").isEmpty());
        assertThat(requestCount()).isEqualTo(2); assertThat(bookingCount()).isZero(); noMoney();
    }

    /** Preconditions: invalid request body. Postconditions: 400 with existing proposal only and no booking.
     * @param body malformed IDs/price/JSON
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings={"{}","{\"serviceProviderUserId\":0,\"expectedAmount\":100}","{\"serviceProviderUserId\":-1,\"expectedAmount\":100}","{\"serviceProviderUserId\":1}","{\"serviceProviderUserId\":1,\"expectedAmount\":0}","{\"serviceProviderUserId\":1,\"expectedAmount\":-1}","{\"serviceProviderUserId\":1,\"expectedAmount\":1.001}","{\"serviceProviderUserId\":1,\"expectedAmount\":100000000000000000}","{broken"})
    void invalidProposalBodiesCreateNothing(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/protection-requests").contentType(MediaType.APPLICATION_JSON).content(body),customer)).andExpect(status().isBadRequest());
        assertThat(requestCount()).isEqualTo(1); assertThat(bookingCount()).isZero(); noMoney();
    }

    /** Preconditions: missing customer wallet or selected publication. Postconditions: 404 without proposal insertion.
     * @param missingCustomer which resource is absent
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void missingRequestResourceIsRejected(boolean missingCustomer) throws Exception {
        mvc.perform(auth(post("/api/v1/protection-requests").contentType(MediaType.APPLICATION_JSON)
                .content(body(missingCustomer?provider.getId():Long.MAX_VALUE,"100")),missingCustomer?accountless:customer)).andExpect(status().isNotFound());
        assertThat(requestCount()).isEqualTo(1);
    }

    /** Preconditions: customer also publishes a rate. Postconditions: self-engagement rejected.
     * @throws Exception on HTTP failure */
    @Test
    void selfRequestCannotCreateBooking() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status->users.findById(customer.getId()).orElseThrow().setSuperhero(true));
        publications.publish(new PublishRateRequest(BigDecimal.TEN),()->customer.getUsername());
        mvc.perform(auth(post("/api/v1/protection-requests").contentType(MediaType.APPLICATION_JSON).content(body(customer.getId(),"10")),customer)).andExpect(status().isBadRequest());
        assertThat(bookingCount()).isZero();
    }

    /** Preconditions: displayed 100, current 150, existing request snapshot 100. Postconditions: stale create fails; original acceptance stays 100.
     * @throws Exception on HTTP failure */
    @Test
    void staleDisplayFailsButExistingRequestKeepsOriginalPrice() throws Exception {
        publications.publish(new PublishRateRequest(new BigDecimal("150")),()->provider.getUsername());
        mvc.perform(auth(post("/api/v1/protection-requests").contentType(MediaType.APPLICATION_JSON).content(body(provider.getId(),"100")),customer))
                .andExpect(status().isConflict());
        assertThat(requestCount()).isEqualTo(1);
        String json=mvc.perform(auth(post(requestUrl()+"/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1,\"status\":\"ConfirmedPaid\",\"paymentTransferId\":999}"),provider)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(100)).andExpect(jsonPath("$.data.status").value("Unconfirmed"))
                .andExpect(jsonPath("$.data.protectionRequestId").value(requestId)).andReturn().getResponse().getContentAsString();
        Number booking=read(json,"$.data.id");
        assertThat(jdbc.queryForObject("SELECT amount FROM bookings WHERE id=?",BigDecimal.class,booking.longValue())).isEqualByComparingTo("100");
        assertThat(requestAmount(requestId)).isEqualByComparingTo("100"); noMoney();
    }

    /** Preconditions: zero wallets and exact price boundary. Postconditions: request/accept succeed without affordability check.
     * @param price minimum or maximum fixed price
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings={"0.01","99999999999999999.99"})
    void zeroFundedRequestsAndAcceptancePreserveBoundaries(String price) throws Exception {
        publications.publish(new PublishRateRequest(new BigDecimal(price)),()->provider.getUsername());
        long id=service.create(input(price),()->customer.getUsername()).id();
        var booking=service.accept(id,()->provider.getUsername());
        assertThat(booking.amount()).isEqualByComparingTo(price); assertThat(booking.protectionRequestId()).isEqualTo(id); noMoney();
    }

    /** Preconditions: pending proposal. Postconditions: either participant reads; outsiders cannot.
     * @param providerSide selected role
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void participantsReadFixedRequestTerms(boolean providerSide) throws Exception {
        mvc.perform(auth(get(requestUrl()),providerSide?provider:customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(100));
    }

    /** Preconditions: actor not assigned for selected operation. Postconditions: 403 with no state change.
     * @param attempt wrong read/command actor
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings={"read-outsider","accept-customer","decline-customer","accept-outsider","decline-outsider"})
    void wrongActorCannotReadOrRespond(String attempt) throws Exception {
        String[] parts=attempt.split("-"); var builder=parts[0].equals("read")?get(requestUrl()):post(requestUrl()+"/"+parts[0]);
        mvc.perform(auth(builder,parts[1].equals("customer")?customer:outsider)).andExpect(status().isForbidden());
        assertThat(state(requestId)).isEqualTo("Pending"); assertThat(bookingCount()).isZero();
    }

    /** Preconditions: caller participates in opposite roles and tied submission times. Postconditions: private newest/ID ordering and empty outsider inbox.
     * @throws Exception on HTTP failure */
    @Test
    void requestListsIncludeBothRolesAndExcludeOthers() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status->users.findById(customer.getId()).orElseThrow().setSuperhero(true));
        publications.publish(new PublishRateRequest(new BigDecimal("50")),()->customer.getUsername());
        long reverse=service.create(new CreateProtectionRequest(customer.getId(),new BigDecimal("50")),()->provider.getUsername()).id();
        jdbc.update("UPDATE protection_requests SET created_at='2026-01-01 00:00:00' WHERE id IN (?,?)",requestId,reverse);
        mvc.perform(auth(get("/api/v1/protection-requests"),customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(reverse));
        mvc.perform(auth(get("/api/v1/protection-requests"),provider)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(auth(get("/api/v1/protection-requests"),outsider)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    /** Preconditions: pending job accepted, or pending job declined. Postconditions: repeats harmless; opposite terminal action conflicts.
     * @param accepted terminal decision
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void responseRetriesPreserveOneTerminalDecision(boolean accepted) throws Exception {
        String action=accepted?"accept":"decline";
        String first=mvc.perform(auth(post(requestUrl()+"/"+action),provider)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second=mvc.perform(auth(post(requestUrl()+"/"+action),provider)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Number)read(second,"$.data.id")).isEqualTo((Number)read(first,"$.data.id"));
        mvc.perform(auth(post(requestUrl()+"/"+(accepted?"decline":"accept")),provider)).andExpect(status().isConflict());
        assertThat(bookingCount()).isEqualTo(accepted?1:0); assertThat(state(requestId)).isEqualTo(accepted?"Accepted":"Declined"); noMoney();
    }

    /** Preconditions: pending request but customer disabled/provider ineligible. Postconditions: acceptance conflicts; active assigned provider may decline.
     * @param disabledCustomer which prerequisite changed
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void unavailablePendingEngagementCannotBeAccepted(boolean disabledCustomer) throws Exception {
        if(disabledCustomer){profiles.deactivateMe(()->customer.getUsername());}
        else{profiles.updateMe(new UpdateUserRequest(provider.getUsername(),"Provider","0","p@example.test",false),()->provider.getUsername());}
        mvc.perform(auth(post(requestUrl()+"/accept"),provider)).andExpect(status().isConflict());
        mvc.perform(auth(post(requestUrl()+"/decline"),provider)).andExpect(status().isOk());
        assertThat(bookingCount()).isZero(); noMoney();
    }

    /** Preconditions: no bearer. Postconditions: all seven new routes reject anonymous callers.
     * @param route selected operation
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Route.class)
    void allNewRoutesRequireAuthentication(Route route) throws Exception { mvc.perform(operation(route,requestId)).andExpect(status().isUnauthorized()); }

    /** Preconditions: token predates disabled identity. Postconditions: all routes reject stale active claims.
     * @param route selected operation
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Route.class)
    void allNewRoutesRejectInactiveBearer(Route route) throws Exception {
        User actor=route==Route.PUBLISH||route==Route.ACCEPT||route==Route.DECLINE?provider:customer;
        var call=auth(operation(route,requestId),actor);
        jdbc.update("UPDATE users SET active=false WHERE id=?",actor.getId());
        mvc.perform(call).andExpect(status().isUnauthorized());
    }

    /** Preconditions: absent proposal ID. Postconditions: 404 for participant reads/responses.
     * @param route selected identifier operation
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(value=Route.class,names={"READ","ACCEPT","DECLINE"})
    void missingProposalReturnsNotFound(Route route) throws Exception { mvc.perform(auth(operation(route,Long.MAX_VALUE),provider)).andExpect(status().isNotFound()); }

    /** Preconditions: bad numeric/nonpositive path ID. Postconditions: safe 400 for new identifier routes.
     * @param route selected route
     * @param id invalid path
     * @throws Exception on HTTP failure */
    @ParameterizedTest @MethodSource("invalidPaths")
    void invalidNewPathIdsAreRejected(Route route,String id) throws Exception {
        String url=route==Route.RATE?"/api/v1/users/"+id+"/rate":"/api/v1/protection-requests/"+id+(route==Route.ACCEPT?"/accept":route==Route.DECLINE?"/decline":"");
        mvc.perform(auth(route==Route.ACCEPT||route==Route.DECLINE?post(url):get(url),provider)).andExpect(status().isBadRequest());
    }

    /** Preconditions: direct booking payload without initial agreement. Postconditions: 405; no bypass/booking.
     * @throws Exception on HTTP failure */
    @Test
    void directBookingCreationIsRetired() throws Exception {
        mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"serviceProviderAccountId\":"+providerId+"}"),customer)).andExpect(status().isMethodNotAllowed());
        assertThat(bookingCount()).isZero();
    }

    /** Preconditions: price changes after submission, accepted work then both completion confirmations.
     * Postconditions: explicit payment uses 100 snapshot and one ledger; initial acceptance moves no money.
     * @throws Exception on HTTP failure */
    @Test
    void acceptedRequestCompletesAndPaysPreservedPrice() throws Exception {
        wallets.load(new LoadAccountRequest(new BigDecimal("1000")),()->customer.getUsername());
        wallets.load(new LoadAccountRequest(new BigDecimal("500")),()->provider.getUsername());
        publications.publish(new PublishRateRequest(new BigDecimal("150")),()->provider.getUsername());
        long booking=service.accept(requestId,()->provider.getUsername()).id();
        assertBalances("1000","500",0);
        jobs.confirmServiceProvider(booking,()->provider.getUsername()); jobs.confirmCustomer(booking,()->customer.getUsername());
        var paid=jobs.pay(booking,()->customer.getUsername()); var repeat=jobs.pay(booking,()->customer.getUsername());
        assertThat(paid.amount()).isEqualByComparingTo("100"); assertThat(repeat.paymentTransferId()).isEqualTo(paid.paymentTransferId());
        assertBalances("900","600",1);
        mvc.perform(auth(get(requestUrl()),customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.bookingId").value(booking));
    }

    /** Preconditions: token predates provider rename. Postconditions: user-ID lookup and assigned response remain correct.
     * @throws Exception on HTTP failure */
    @Test
    void renamedProviderRetainsRequestAndRateIdentity() throws Exception {
        var acceptance=auth(post(requestUrl()+"/accept"),provider);
        profiles.updateMe(new UpdateUserRequest("r1new_"+UUID.randomUUID().toString().replace("-",""),"Provider","0","p@example.test",true),()->provider.getUsername());
        mvc.perform(acceptance).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/users/"+provider.getId()+"/rate"),customer)).andExpect(status().isOk());
    }

    /** Preconditions: real agreement flush; booking failure before/after real booking insertion.
     * Postconditions: actual SQL error rolls back both state and booking; HTTP response is safe.
     * @param afterInsert whether to flush the real booking first
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void mysqlBookingFailureRollsBackAgreementAndBooking(boolean afterInsert) throws Exception {
        AtomicBoolean observed=new AtomicBoolean();
        doAnswer(call->{
            assertThat(state(requestId)).isEqualTo("Accepted");
            if(afterInsert){entities.persist(call.getArgument(0));entities.flush();assertThat(bookingCount()).isEqualTo(1);}
            observed.set(true);
            jdbc.update("INSERT INTO hero_rates (provider_account_id,amount,created_at,updated_at) VALUES (?,1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",Long.MAX_VALUE);
            return null;
        }).when(bookings).saveAndFlush(any(Booking.class));
        mvc.perform(auth(post(requestUrl()+"/accept"),provider)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to complete request."));
        assertThat(observed).isTrue(); assertThat(state(requestId)).isEqualTo("Pending"); assertThat(bookingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT responded_at FROM protection_requests WHERE id=?",java.sql.Timestamp.class,requestId)).isNull(); noMoney();
    }

    /** Preconditions: six provider accepts race. Postconditions: one matching booking and original ID for every caller.
     * @throws Exception on worker failure */
    @Test
    void concurrentAcceptsCreateOneBooking() throws Exception {
        Callable<String> accept=()->service.accept(requestId,()->provider.getUsername()).id().toString();
        var results=race(List.of(accept,accept,accept,accept,accept,accept));
        assertThat(results.stream().distinct().count()).isEqualTo(1); assertThat(bookingCount()).isEqualTo(1); assertThat(state(requestId)).isEqualTo("Accepted"); noMoney();
    }

    /** Preconditions: accept and decline compete. Postconditions: one terminal result; no accepted request without its unique booking.
     * @throws Exception on worker failure */
    @Test
    void acceptDeclineRaceHasOneTerminalOutcome() throws Exception {
        Callable<String> accept=()->{try{service.accept(requestId,()->provider.getUsername());return "accepted";}catch(BusinessConflictException exception){return "conflict";}};
        Callable<String> decline=()->{try{service.decline(requestId,()->provider.getUsername());return "declined";}catch(BusinessConflictException exception){return "conflict";}};
        var result=race(List.of(accept,decline));
        assertThat(result).contains("conflict");
        if(state(requestId).equals("Accepted")){assertThat(result).contains("accepted");assertThat(bookingCount()).isEqualTo(1);}
        else{assertThat(result).contains("declined");assertThat(bookingCount()).isZero();} noMoney();
    }

    /** Preconditions: publication update races request creation using old display. Postconditions: either old snapshot or 409, never silent new price.
     * @throws Exception on worker failure */
    @Test
    void priceUpdateAndSubmissionUseOneConsistentSnapshot() throws Exception {
        var results=race(List.of(()->{publications.publish(new PublishRateRequest(new BigDecimal("150")),()->provider.getUsername());return "updated";},
                ()->{try{long id=service.create(input("100"),()->customer.getUsername()).id();assertThat(requestAmount(id)).isEqualByComparingTo("100");return "submitted";}catch(BusinessConflictException exception){return "stale";}}));
        assertThat(results).contains("updated"); assertThat(requestCount()).isEqualTo(results.contains("submitted")?2:1); noMoney();
    }

    /** Preconditions: pending request and zero wallets; provider/customer deactivation competes with acceptance.
     * Postconditions: accepted implies both active; otherwise no booking and actor inactive.
     * @param providerSide which participant deactivates
     * @throws Exception on worker failure */
    @ParameterizedTest @ValueSource(booleans={true,false})
    void deactivationAndAcceptanceAreSerialized(boolean providerSide) throws Exception {
        User actor=providerSide?provider:customer;
        var result=race(List.of(()->{try{service.accept(requestId,()->provider.getUsername());return "accepted";}catch(BusinessConflictException|org.springframework.security.access.AccessDeniedException exception){return "unavailable";}},
                ()->{try{profiles.deactivateMe(()->actor.getUsername());return "deactivated";}catch(BusinessConflictException exception){return "outstanding";}}));
        if(state(requestId).equals("Accepted")){assertThat(result).containsExactlyInAnyOrder("accepted","outstanding");assertThat(bookingCount()).isEqualTo(1);}
        else{assertThat(result).containsExactlyInAnyOrder("unavailable","deactivated");assertThat(bookingCount()).isZero();} noMoney();
    }

    /** Preconditions: provider eligibility removal competes with acceptance. Postconditions: accepted work stays eligible or acceptance fails.
     * @throws Exception on worker failure */
    @Test
    void eligibilityRemovalAndAcceptanceAreSerialized() throws Exception {
        var result=race(List.of(()->{try{service.accept(requestId,()->provider.getUsername());return "accepted";}catch(BusinessConflictException exception){return "unavailable";}},
                ()->{try{profiles.updateMe(new UpdateUserRequest(provider.getUsername(),"Provider","0","p@example.test",false),()->provider.getUsername());return "removed";}catch(BusinessConflictException exception){return "outstanding";}}));
        boolean hero=jdbc.queryForObject("SELECT is_superhero FROM users WHERE id=?",Boolean.class,provider.getId());
        assertThat(result).containsExactlyInAnyOrder(hero?"accepted":"unavailable",hero?"outstanding":"removed");
        assertThat(bookingCount()).isEqualTo(hero?1:0); noMoney();
    }

    /** Preconditions: identical successful customer submissions. Postconditions: separate proposals, never silently deduplicated. */
    @Test
    void repeatedSubmissionsAreIndependentRequests() {
        long first=service.create(input("100"),()->customer.getUsername()).id(); long second=service.create(input("100"),()->customer.getUsername()).id();
        assertThat(first).isNotEqualTo(second); assertThat(requestCount()).isEqualTo(3); assertThat(bookingCount()).isZero();
    }

    /** Preconditions: committed fixtures may exist. Postconditions: only captured fixture rows removed in FK order. */
    @AfterEach
    void cleanup() {
        Object[] ids={customerId,providerId,outsiderId,customerId,providerId,outsiderId};
        jdbc.update("DELETE FROM bookings WHERE customer_account_id IN (?,?,?) OR provider_account_id IN (?,?,?)",ids);
        jdbc.update("DELETE FROM protection_requests WHERE customer_account_id IN (?,?,?) OR provider_account_id IN (?,?,?)",ids);
        jdbc.update("DELETE FROM hero_rates WHERE provider_account_id IN (?,?,?)",customerId,providerId,outsiderId);
        jdbc.update("DELETE FROM transfer_records WHERE source_account_id IN (?,?,?) OR destination_account_id IN (?,?,?)",ids);
        jdbc.update("DELETE FROM accounts WHERE id IN (?,?,?)",customerId,providerId,outsiderId);
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?,?)",customer==null?null:customer.getId(),provider==null?null:provider.getId(),outsider==null?null:outsider.getId(),accountless==null?null:accountless.getId());
    }

    /** Preconditions: test values. Postconditions: new unsaved identity.
     * @param name unique name
     * @param hero eligibility
     * @return identity */
    private User identity(String name,boolean hero){return new User(name,"test-only-hash","R1 Test","0","test@example.test",hero);}
    /** Preconditions: provider fixture exists. Postconditions: valid typed request.
     * @param amount displayed price
     * @return input */
    private CreateProtectionRequest input(String amount){return new CreateProtectionRequest(provider.getId(),new BigDecimal(amount));}
    /** Preconditions: controlled values. Postconditions: JSON without caller identity.
     * @param userId hero ID
     * @param amount numeric JSON literal
     * @return body */
    private String body(Long userId,String amount){return "{\"serviceProviderUserId\":"+userId+",\"expectedAmount\":"+amount+"}";}
    /** Preconditions: active saved actor. Postconditions: real JWT added without logging.
     * @param request HTTP request
     * @param actor identity
     * @return authenticated builder */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,User actor){return request.header("Authorization","Bearer "+tokens.issue(actor).token());}
    /** Preconditions: default proposal exists. Postconditions: no writes.
     * @return resource URL */
    private String requestUrl(){return "/api/v1/protection-requests/"+requestId;}
    /** Preconditions: fixture values. Postconditions: unauthenticated route builder.
     * @param route method
     * @param id proposal ID
     * @return request */
    private MockHttpServletRequestBuilder operation(Route route,long id){return switch(route){
        case PUBLISH->put("/api/v1/users/me/rate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}");
        case RATE->get("/api/v1/users/"+provider.getId()+"/rate");
        case CREATE->post("/api/v1/protection-requests").contentType(MediaType.APPLICATION_JSON).content(body(provider.getId(),"100"));
        case READ->get("/api/v1/protection-requests/"+id);case LIST->get("/api/v1/protection-requests");
        case ACCEPT->post("/api/v1/protection-requests/"+id+"/accept");case DECLINE->post("/api/v1/protection-requests/"+id+"/decline");};}
    /** Preconditions: proposal persisted. Postconditions: no writes.
     * @param id proposal ID
     * @return persisted state */
    private String state(long id){return jdbc.queryForObject("SELECT status FROM protection_requests WHERE id=?",String.class,id);}
    /** Preconditions: proposal persisted. Postconditions: no writes.
     * @param id proposal ID
     * @return preserved amount */
    private BigDecimal requestAmount(long id){return jdbc.queryForObject("SELECT amount FROM protection_requests WHERE id=?",BigDecimal.class,id);}
    /** Preconditions: fixtures captured. Postconditions: no writes.
     * @return scoped proposal count */
    private long requestCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM protection_requests WHERE customer_account_id IN (?,?,?) OR provider_account_id IN (?,?,?)",Long.class,customerId,providerId,outsiderId,customerId,providerId,outsiderId);}
    /** Preconditions: fixtures captured. Postconditions: no writes.
     * @return scoped booking count */
    private long bookingCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE customer_account_id IN (?,?,?) OR provider_account_id IN (?,?,?)",Long.class,customerId,providerId,outsiderId,customerId,providerId,outsiderId);}
    /** Preconditions: fixtures captured. Postconditions: no writes.
     * @return scoped publication count */
    private long rateCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM hero_rates WHERE provider_account_id IN (?,?,?)",Long.class,customerId,providerId,outsiderId);}
    /** Preconditions: zero-funded fixture operations ended. Postconditions: no financial effects. */
    private void noMoney(){assertBalances("0","0",0);}
    /** Preconditions: operations ended or intentionally flushed. Postconditions: committed/transaction-local financial values checked.
     * @param customerAmount expected customer funds
     * @param providerAmount expected provider funds
     * @param ledgerCount expected fixture ledger entries */
    private void assertBalances(String customerAmount,String providerAmount,long ledgerCount){
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,customerId)).isEqualByComparingTo(customerAmount);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,providerId)).isEqualByComparingTo(providerAmount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_records WHERE source_account_id IN (?,?,?) OR destination_account_id IN (?,?,?)",Long.class,customerId,providerId,outsiderId,customerId,providerId,outsiderId)).isEqualTo(ledgerCount);
    }
    /** Preconditions: none. Postconditions: four routes times three invalid path cases.
     * @return route/path pairs */
    private static java.util.stream.Stream<Arguments> invalidPaths(){return java.util.stream.Stream.of(Route.RATE,Route.READ,Route.ACCEPT,Route.DECLINE).flatMap(route->java.util.stream.Stream.of("not-an-id","0","-1").map(id->Arguments.of(route,id)));}
    /** Preconditions: service calls use independent transactions. Postconditions: all workers finish before cleanup.
     * @param tasks workers
     * @return outcomes
     * @throws Exception on timeout or worker failure */
    private List<String> race(List<Callable<String>> tasks)throws Exception{
        var pool=Executors.newFixedThreadPool(tasks.size());var ready=new CountDownLatch(tasks.size());var start=new CountDownLatch(1);
        try{var futures=tasks.stream().map(task->pool.submit(()->{ready.countDown();if(!start.await(WAIT_SECONDS,TimeUnit.SECONDS)){throw new IllegalStateException("Race start timed out");}return task.call();})).toList();
            assertThat(ready.await(WAIT_SECONDS,TimeUnit.SECONDS)).isTrue();start.countDown();var values=new java.util.ArrayList<String>();for(var future:futures){values.add(future.get(WAIT_SECONDS,TimeUnit.SECONDS));}return values;
        }finally{start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(WAIT_SECONDS,TimeUnit.SECONDS)).isTrue();}}
}
