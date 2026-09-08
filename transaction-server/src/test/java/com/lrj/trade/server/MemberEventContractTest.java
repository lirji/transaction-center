package com.lrj.trade.server;

import com.lrj.trade.core.MemberRegisteredEvent;
import com.lrj.trade.core.MemberEventCodec;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 黄金固件核对类型化事件，错误载荷不能被宽松默认值接受。 */
class MemberEventContractTest {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void validFixturesRoundTripWithoutLosingRequiredFields() throws Exception {
        Path root=Path.of("..","docs","contracts","member-registered-v1");
        for(String name:new String[]{"minimal.json","complete.json"}) {
            String source=Files.readString(root.resolve(name));
            var event=MemberEventCodec.decode(source);
            assertEquals(json.readTree(source),json.readTree(MemberEventCodec.encode(event)));
        }
    }
    @Test void inconsistentUnknownAndMissingFieldsAreRejected() throws Exception {
        assertThrows(NullPointerException.class,()->MemberEventCodec.encode(null));
        assertThrows(NullPointerException.class,()->MemberEventCodec.decode("null"));
        Path root=Path.of("..","docs","contracts","member-registered-v1");
        assertThrows(RuntimeException.class,()->MemberEventCodec.decode(Files.readString(root.resolve("invalid-aggregate.json"))));
        String valid=Files.readString(root.resolve("minimal.json"));
        var unknown=json.readTree(valid).deepCopy(); ((tools.jackson.databind.node.ObjectNode)unknown).put("subject","untrusted");
        assertThrows(RuntimeException.class,()->MemberEventCodec.decode(unknown.toString()));
        var missing=(tools.jackson.databind.node.ObjectNode)json.readTree(valid); missing.remove("eventType");
        assertThrows(RuntimeException.class,()->MemberEventCodec.decode(missing.toString()));
        for(String field:new String[]{"aggregateVersion","schemaVersion","tenantId","traceId"}) {
            var absent=(tools.jackson.databind.node.ObjectNode)json.readTree(valid); absent.remove(field);
            assertThrows(RuntimeException.class,()->MemberEventCodec.decode(absent.toString()),field);
            absent.putNull(field); assertThrows(RuntimeException.class,()->MemberEventCodec.decode(absent.toString()),field);
        }
        for(String id:new String[]{"01","+1","9223372036854775808"}) {
            var invalid=(tools.jackson.databind.node.ObjectNode)json.readTree(valid); invalid.put("tenantId",id);
            assertThrows(RuntimeException.class,()->MemberEventCodec.decode(invalid.toString()));
        }
        var uuid=(tools.jackson.databind.node.ObjectNode)json.readTree(valid); uuid.put("traceId","1-1-1-1-1");
        assertThrows(RuntimeException.class,()->MemberEventCodec.decode(uuid.toString()));
        assertThrows(RuntimeException.class,()->MemberEventCodec.decode(valid.replace("\"schemaVersion\": 1","\"schemaVersion\": 1, \"schemaVersion\": 1")));
        assertThrows(IllegalArgumentException.class,()->MemberRegisteredEvent.create(1,2,Instant.parse("2026-09-07T08:00:00.123456Z")));
    }
}
