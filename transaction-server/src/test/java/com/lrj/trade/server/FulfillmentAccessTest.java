package com.lrj.trade.server;
import com.lrj.trade.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FulfillmentAccessTest {
    @Test void disabledEntryRejectsMemberAndPrivilegedSource() {
        var access=new FulfillmentAccess(false,"","","");var actor=new Actor(1,"https://identity.test","trusted");
        assertEquals("FULFILLMENT_DISABLED",assertThrows(TradeException.class,access::memberEnabled).code());
        assertEquals("FULFILLMENT_DISABLED",assertThrows(TradeException.class,()->access.require(actor,"DELIVER")).code());
    }
    @Test void enabledRequiresExplicitAndWellFormedTrustForAllOperations() {
        assertThrows(IllegalArgumentException.class,()->new FulfillmentAccess(true,"","",""));
        assertThrows(IllegalArgumentException.class,()->new FulfillmentAccess(false,"bad-key","",""));
    }
    @Test void sourceRolesRemainSeparateDespiteMatchingSubjectInAnotherIssuer() {
        var delivery=new Actor(1,"https://identity.test","delivery");var completion=new Actor(1,"https://identity.test","completion");var warehouse=new Actor(1,"https://identity.test","warehouse");
        var access=new FulfillmentAccess(true,delivery.key(),completion.key(),warehouse.key());
        assertDoesNotThrow(()->access.require(delivery,"DELIVER"));assertDoesNotThrow(()->access.require(completion,"COMPLETE"));assertDoesNotThrow(()->access.require(warehouse,"RETURN"));
        assertEquals("FULFILLMENT_UNTRUSTED",assertThrows(TradeException.class,()->access.require(delivery,"RETURN")).code());
        assertEquals("FULFILLMENT_UNTRUSTED",assertThrows(TradeException.class,()->access.require(new Actor(1,"https://other.test","delivery"),"DELIVER")).code());
    }
}
