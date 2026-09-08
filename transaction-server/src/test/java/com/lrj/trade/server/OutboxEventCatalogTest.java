package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 发布边界验证真实合同并保留持久载荷，拒绝v0和索引/消息身份拼接。 */
class OutboxEventCatalogTest {
    @Test void allSupportedContractsKeepOriginalPayloadAndRejectIndexSubstitution() throws Exception {
        var json=JsonMapper.builder().build();
        for(String type:new String[]{"member-registered","order-paid","order-completed","order-cancelled","refund-evidence","inventory-changed"}) {
            String payload=" \n"+Files.readString(Path.of("..","docs","contracts",type+"-v1","complete.json"))+" \n";
            var e=json.readTree(payload);
            long tenant=Long.parseLong(e.get("tenantId").asText());
            String id=e.get("eventId").asText(),eventType=e.get("eventType").asText(),aggregate=e.get("aggregateId").asText();
            var message=OutboxEventCatalog.validate(tenant,id,eventType,aggregate,payload);
            assertEquals(payload,message.payload());
            assertEquals(tenant+":"+e.get("aggregateType").asText()+":"+aggregate,message.partitionKey());
            assertThrows(IllegalArgumentException.class,()->OutboxEventCatalog.validate(tenant+1,id,eventType,aggregate,payload));
            assertThrows(IllegalArgumentException.class,()->OutboxEventCatalog.validate(tenant,"wrong",eventType,aggregate,payload));
            assertThrows(IllegalArgumentException.class,()->OutboxEventCatalog.validate(tenant,id,eventType,"wrong",payload));
        }
        assertEquals(7,OutboxEventCatalog.supportedTypes().size());
        assertThrows(IllegalArgumentException.class,()->OutboxEventCatalog.validate(1,"id","order.paid.internal.v0","1","{}"));
        String source=Files.readString(Path.of("..","docs","contracts","refund-evidence-v1","complete.json"));
        var e=json.readTree(source);
        String other=e.get("eventType").asText().equals("trade.refund-succeeded.v1")?"trade.order-evidence-changed.v1":"trade.refund-succeeded.v1";
        assertThrows(IllegalArgumentException.class,()->OutboxEventCatalog.validate(Long.parseLong(e.get("tenantId").asText()),e.get("eventId").asText(),other,e.get("aggregateId").asText(),source));
    }
}
