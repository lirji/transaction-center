package com.lrj.trade.server;

import com.lrj.trade.core.AuthoritativeSubjectMappingPort;
import com.lrj.trade.core.AuthoritativeSubjectMappingPort.*;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.server.AuthoritativeReferralMappingAdapter.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 无数据库纯合同测试：防止未接权威事实时产生伪新客、订单或合并后的资格。 */
class AuthoritativeReferralMappingAdapterTest {
    private static final Instant NOW=Instant.parse("2026-09-08T01:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final Authority AUTHORITY=new Authority("fixture-authority","event-1",7,NOW.minusSeconds(60));
    private static AuthoritativeReferralMappingAdapter adapter(AuthoritativeSubjectMappingPort port) { return new AuthoritativeReferralMappingAdapter(port,CLOCK); }
    private static Readiness customer(AuthoritativeReferralMappingAdapter adapter,String subject) { return adapter.customer(1,subject,NOW,"new-customer","v1"); }
    private static Readiness order(AuthoritativeReferralMappingAdapter adapter) { return adapter.firstOrder(1,"subject-A",NOW.minusSeconds(3600),NOW,"first-order-v1"); }

    @Test void subjectLengthCountsUnicodeCodePointsAndRejectsInvalidSurrogates() {
        String supplementary=new String(Character.toChars(0x1F600));
        String longest=supplementary.repeat(256);
        assertEquals(longest,new Query(1,longest,NOW).canonicalSubject());
        var query=new Query(1,"original",NOW);
        assertEquals(longest,new Merged(query,AUTHORITY,longest).newCanonicalSubject());
        for(String invalid:new String[]{"a".repeat(257),supplementary.repeat(257),String.valueOf((char)0xD800),String.valueOf((char)0xDC00)}) {
            assertEquals("INVALID_ARGUMENT",assertThrows(TradeException.class,()->new Query(1,invalid,NOW)).code());
            assertEquals("INVALID_ARGUMENT",assertThrows(TradeException.class,()->new Merged(query,AUTHORITY,invalid)).code());
        }
    }

    @Test void unconfiguredAuthorityNeverGrantsEligibilityOrInventsNotFound() {
        var adapter=adapter(new UnavailableSubjectMapping());
        assertEquals(new Readiness(State.PENDING,Reason.AUTHORITY_UNAVAILABLE),customer(adapter,"subject-A"));
        assertEquals(new Readiness(State.PENDING,Reason.AUTHORITY_UNAVAILABLE),order(adapter));
        assertThrows(IllegalArgumentException.class,()->State.valueOf("READY"));
    }

    @Test void mappedMemberDoesNotImplyPolicyOrOrganizationShopSettlementAgreement() {
        var adapter=adapter(q->new Mapped(q,AUTHORITY,42));
        assertEquals(new Readiness(State.PENDING,Reason.CUSTOMER_POLICY_UNCONFIRMED),customer(adapter,"subject-A"));
        assertEquals(new Readiness(State.PENDING,Reason.ORDER_POLICY_SCOPE_UNCONFIRMED),order(adapter));
        assertEquals(State.PENDING,adapter.customer(1,"42",NOW,"NEVER_CONFIRMED_PAID_V1","0").state());
    }

    @Test void sourceExceptionIsClosedWithoutLeakingSubjectOrInternalAddress() {
        var adapter=adapter(q->{ throw new IllegalStateException("subject-A at https://identity.internal/private"); });
        var customer=customer(adapter,"subject-A");
        var order=order(adapter);
        assertEquals(new Readiness(State.PENDING,Reason.AUTHORITY_UNAVAILABLE),customer);
        assertEquals(customer,order);
        assertFalse(customer.toString().contains("subject-A"));
        assertFalse(customer.toString().contains("identity.internal"));
    }

    @Test void authoritativeAbsenceDoesNotPretendToProveNoOrderInPeriod() {
        var adapter=adapter(q->new NotFound(q,AUTHORITY));
        assertEquals(new Readiness(State.NOT_FOUND,Reason.AUTHORITATIVE_NOT_FOUND),customer(adapter,"subject-A"));
        assertEquals(new Readiness(State.PENDING,Reason.AUTHORITATIVE_NOT_FOUND),order(adapter));
    }

    @Test void mergedSubjectIsNotRedirectedAndOrderRemainsPending() {
        var calls=new AtomicInteger();
        var adapter=adapter(q->{ calls.incrementAndGet(); assertEquals("subject-A",q.canonicalSubject()); return new Merged(q,AUTHORITY,"subject-B"); });
        assertEquals(new Readiness(State.MERGED,Reason.SUBJECT_MERGED),customer(adapter,"subject-A"));
        assertEquals(new Readiness(State.PENDING,Reason.SUBJECT_MERGED),order(adapter));
        assertEquals(2,calls.get());
    }

    @Test void responseMustBindExactTenantSubjectAndObservationWatermark() {
        for(Query incorrect:new Query[]{new Query(2,"subject-A",NOW),new Query(1,"subject-a",NOW),
                new Query(1," subject-A",NOW),new Query(1,"subject-A",NOW.minusMillis(1))}) {
            assertEquals(new Readiness(State.PENDING,Reason.MAPPING_INCONSISTENT),
                customer(adapter(q->new NotFound(incorrect,AUTHORITY)),"subject-A"));
        }
        assertEquals(Reason.MAPPING_INCONSISTENT,customer(adapter(q->null),"subject-A").reason());
    }

    @Test void canonicalSubjectsAreNotTrimmedOrUnicodeNormalized() {
        for(String subject:new String[]{" subject-A ","é","e\u0301"}) {
            var adapter=adapter(q->{ assertEquals(subject,q.canonicalSubject()); return new Pending(q); });
            assertEquals(State.PENDING,customer(adapter,subject).state());
        }
    }

    @Test void futureEffectiveMappingCannotBeUsedForPastEvaluation() {
        var adapter=adapter(q->new NotFound(q,AUTHORITY));
        assertEquals(Reason.MAPPING_NOT_EFFECTIVE,adapter.customer(1,"subject-A",NOW.minusSeconds(61),"new-customer","v1").reason());
        assertEquals(State.NOT_FOUND,adapter.customer(1,"subject-A",NOW.minusSeconds(60),"new-customer","v1").state());
    }

    @Test void invalidQueryIsRejectedBeforeResolvingAnything() {
        var calls=new AtomicInteger();
        var adapter=adapter(q->{ calls.incrementAndGet(); return new Pending(q); });
        assertThrows(TradeException.class,()->adapter.customer(0,"s",NOW,"p","v"));
        assertThrows(TradeException.class,()->adapter.customer(1," ",NOW,"p","v"));
        assertThrows(TradeException.class,()->adapter.customer(1,"s",null,"p","v"));
        assertThrows(TradeException.class,()->adapter.customer(1,"s",NOW.plusNanos(1),"p","v"));
        assertThrows(TradeException.class,()->adapter.customer(1,"s",NOW,"","v"));
        assertThrows(TradeException.class,()->adapter.firstOrder(1,"s",NOW,NOW,"p"));
        assertThrows(TradeException.class,()->adapter.firstOrder(1,"s",NOW,NOW.plusSeconds(1),""));
        assertEquals(0,calls.get());
    }

    @Test void authorityAndMergeFactsRequirePositiveRevisionAndDistinctDestination() {
        assertThrows(TradeException.class,()->new Authority("source","event",0,NOW));
        assertThrows(TradeException.class,()->new Authority("","event",1,NOW));
        var query=new Query(1,"private-subject",NOW);
        assertThrows(TradeException.class,()->new Mapped(query,AUTHORITY,0));
        assertThrows(TradeException.class,()->new Merged(query,AUTHORITY,"private-subject"));
        assertFalse(query.toString().contains("private-subject"));
        assertFalse(new Merged(query,AUTHORITY,"private-target").toString().contains("private-target"));
    }
}
