package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** 黄金样例覆盖正常支付的版本/金额/首单口径，错误字段不能默认为非首单。 */
class OrderPaidEventContractTest {
    @Test void paidEventGoldenFixturesAndInvalidVariants() throws Exception {
        var json=JsonMapper.builder().build(); var root=Path.of("..","docs","contracts","order-paid-v1");
        for(String name:new String[]{"minimal.json","complete.json"}) {
            String source=Files.readString(root.resolve(name));
            assertEquals(json.readTree(source),json.readTree(OrderPaidEventCodec.encode(OrderPaidEventCodec.decode(source))));
        }
        assertThrows(RuntimeException.class,()->OrderPaidEventCodec.decode(Files.readString(root.resolve("invalid-version.json"))));
        String valid=Files.readString(root.resolve("minimal.json"));
        for(String field:new String[]{"paidAmount","isFirstConfirmedPaidOrder","evidenceVersion"}) {
            var missing=(ObjectNode)json.readTree(valid); ((ObjectNode)missing.path("data")).remove(field);
            assertThrows(RuntimeException.class,()->OrderPaidEventCodec.decode(missing.toString()),field);
        }
        var zero=(ObjectNode)json.readTree(valid); ((ObjectNode)zero.path("data")).put("paidAmount",0);
        assertThrows(RuntimeException.class,()->OrderPaidEventCodec.decode(zero.toString()));
        assertThrows(NullPointerException.class,()->OrderPaidEventCodec.decode("null"));
    }
}
