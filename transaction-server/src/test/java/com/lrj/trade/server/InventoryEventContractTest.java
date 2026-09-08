package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 黄金合同覆盖完整快照和超出浮点安全整数范围的库存，禁止静默丢精度。 */
class InventoryEventContractTest {
    @Test void inventoryFixturesPreserveInt64AndRejectBrokenConservation() throws Exception {
        var json=JsonMapper.builder().build();
        Path root=Path.of("..","docs","contracts","inventory-changed-v1");
        for(String file:new String[]{"minimal.json","complete.json"}) {
            String source=Files.readString(root.resolve(file));
            assertEquals(json.readTree(source),json.readTree(InventoryChangedEventCodec.encode(InventoryChangedEventCodec.decode(source))));
        }
        assertThrows(RuntimeException.class,()->InventoryChangedEventCodec.decode(Files.readString(root.resolve("invalid-balance.json"))));
        var full=new InventoryChangedEvent.Data("2","3","operation",Long.MAX_VALUE,0,0,Long.MAX_VALUE,1);
        var event=InventoryChangedEvent.create(1,java.time.Instant.parse("2026-09-08T00:00:00Z"),full);
        assertEquals(event,InventoryChangedEventCodec.decode(InventoryChangedEventCodec.encode(event)));
        assertThrows(ArithmeticException.class,()->new InventoryChangedEvent.Data("2","3","operation",Long.MAX_VALUE,1,0,0,1));
        String encoded=InventoryChangedEventCodec.encode(event);
        assertThrows(RuntimeException.class,()->InventoryChangedEventCodec.decode(encoded.replace("\"aggregateId\":\"2:3\"","\"aggregateId\":\"3:2\"")));
    }
}
