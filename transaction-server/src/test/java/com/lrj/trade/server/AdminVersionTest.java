package com.lrj.trade.server;
import com.lrj.trade.core.TradeException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdminVersionTest {
    @Test void strongVersionIsCanonicalAndBounded() {
        assertEquals(0,AdminVersion.parse("\"0\""));assertEquals(Long.MAX_VALUE,AdminVersion.parse("\"9223372036854775807\""));
        assertEquals("PRECONDITION_REQUIRED",assertThrows(TradeException.class,()->AdminVersion.parse(null)).code());
        for(String value:new String[]{"*","0","W/\"0\"","\"01\"","\"-1\"","\"0\",\"1\"","\"9223372036854775808\""})
            assertEquals("INVALID_ARGUMENT",assertThrows(TradeException.class,()->AdminVersion.parse(value)).code());
    }
    @Test void mismatchedBodyDoesNotExecuteAndOnlyVersionConflictIsTranslated() {
        var calls=new AtomicInteger();
        assertEquals("PRECONDITION_FAILED",assertThrows(TradeException.class,()->AdminVersion.execute("\"1\"",0,calls::incrementAndGet)).code());assertEquals(0,calls.get());
        assertEquals("PRECONDITION_FAILED",assertThrows(TradeException.class,()->AdminVersion.execute("\"1\"",1,()->{throw new TradeException("VERSION_CONFLICT","stale");})).code());
        assertEquals("STATE_CONFLICT",assertThrows(TradeException.class,()->AdminVersion.execute("\"1\"",1,()->{throw new TradeException("STATE_CONFLICT","state");})).code());
        assertEquals("original",AdminVersion.execute("\"1\"",1,()->"original"));
    }
}
