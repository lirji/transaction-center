package com.lrj.trade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.lrj.trade.client.RetryDecision.Action.*;

class RetryDecisionTest {
    private TradeFailure failure(String code, boolean retryable) { return new TradeFailure(code,"说明","ARJ-test",retryable); }
    @Test void unknownCodesAndOpaqueAuditIdsSurviveWithoutAutomaticRetry() {
        var f=failure("FUTURE_ERROR",true);
        assertEquals("ARJ-test",f.traceId());
        assertEquals("FUTURE_ERROR",f.code());
        assertEquals(STOP,RetryDecision.from(503,f,true,"1").action());
        assertEquals(STOP,RetryDecision.from(503,null,true,"1").action());
    }
    @Test void retryRequiresStatusFlagAndOriginalKeyTogether() {
        for(int status:new int[]{400,409,422,500,200})
            assertEquals(STOP,RetryDecision.from(status,failure(ErrorCodes.RETRY_SAME_KEY,true),true,"1").action());
        assertEquals(STOP,RetryDecision.from(503,failure(ErrorCodes.RETRY_SAME_KEY,false),true,"1").action());
        assertEquals(STOP,RetryDecision.from(503,failure(ErrorCodes.RETRY_SAME_KEY,true),false,"1").action());
        assertEquals(RETRY_SAME_KEY,RetryDecision.from(503,failure(ErrorCodes.RETRY_SAME_KEY,true),true,"1").action());
        assertEquals(RETRY_SAME_KEY,RetryDecision.from(429,failure(ErrorCodes.HOT_SKU_BUSY,true),true,"1").action());
        assertEquals(STOP,RetryDecision.from(503,failure(ErrorCodes.IDEMPOTENCY_CONFLICT,true),true,"1").action());
    }
    @Test void adminQueryCodesRespectRetryPolicy() {
        assertEquals(RETRY_SAME_KEY,RetryDecision.from(429,failure(ErrorCodes.ADMIN_QUERY_BUSY,true),true,"2").action());
        assertEquals(STOP,RetryDecision.from(429,failure(ErrorCodes.ADMIN_QUERY_BUSY,true),false,"2").action());
        assertEquals(STOP,RetryDecision.from(503,failure(ErrorCodes.ADMIN_QUERY_UNAVAILABLE,true),true,"2").action());
    }
    @Test void unknownPaymentAndTimeoutNeverCreateANewAttempt() {
        assertEquals(QUERY_ORIGINAL,RetryDecision.from(409,failure(ErrorCodes.PAYMENT_UNRESOLVED,true),true,"1").action());
        assertEquals(QUERY_ORIGINAL,RetryDecision.transportUncertain().action());
        assertEquals(REFRESH_VERSION,RetryDecision.from(412,failure(ErrorCodes.PRECONDITION_FAILED,false),true,null).action());
    }
    @Test void retryAfterCannotBeShortenedOrOverflowed() {
        var f=failure(ErrorCodes.RETRY_SAME_KEY,true);
        assertEquals(0,RetryDecision.from(503,f,true,"0").delaySeconds());
        assertEquals(300,RetryDecision.from(503,f,true,"300").delaySeconds());
        assertEquals(STOP,RetryDecision.from(503,f,true,"301").action());
        assertEquals(STOP,RetryDecision.from(503,f,true,"9999999999").action());
        assertEquals(1,RetryDecision.from(503,f,true,null).delaySeconds());
        for(String header:new String[]{"-1","bogus","10000000000","99999999999999999999999999999","Wed, 09 Sep 2026 10:00:00 GMT"})
            assertEquals(STOP,RetryDecision.from(503,f,true,header).action());
    }
}
