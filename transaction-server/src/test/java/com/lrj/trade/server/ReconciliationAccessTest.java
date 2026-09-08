package com.lrj.trade.server;
import com.lrj.trade.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
/** 财务入口缺省关闭，不能因营销证据授权或空白配置意外开放。 */
class ReconciliationAccessTest {
    @Test void reportAccessDefaultsClosedAndRequiresIndependentActor() {
        var actor=new Actor(1,"https://identity.test","reporter");
        assertEquals("RECONCILIATION_DISABLED",assertThrows(TradeException.class,()->new ReconciliationReportAccess(false,"","recon-platform").require(actor)).code());
        assertThrows(IllegalArgumentException.class,()->new ReconciliationReportAccess(true,"","recon-platform"));
        var access=new ReconciliationReportAccess(true,actor.key(),"recon-platform");
        assertEquals("recon-platform",access.require(actor));
        assertEquals("RECONCILIATION_UNTRUSTED",assertThrows(TradeException.class,()->access.require(new Actor(1,"https://other.test","reporter"))).code());
    }
    @Test void defaultConfigurationFailsClosed() {
        new ApplicationContextRunner().withBean(ReconciliationAccess.class).run(c->{
            var error=assertThrows(TradeException.class,()->c.getBean(ReconciliationAccess.class).require(new Actor(1,"https://identity.test","reader")));
            assertEquals("RECONCILIATION_DISABLED",error.code());
        });
    }
    @Test void enabledConfigurationRequiresValidIdentityAndChecksIssuer() {
        assertThrows(IllegalArgumentException.class,()->new ReconciliationAccess(true,""));
        var actor=new Actor(1,"https://identity.test","reader");var access=new ReconciliationAccess(true,actor.key());
        assertDoesNotThrow(()->access.require(actor));
        assertEquals("RECONCILIATION_UNTRUSTED",assertThrows(TradeException.class,()->access.require(new Actor(1,"https://other.test","reader"))).code());
    }
}
