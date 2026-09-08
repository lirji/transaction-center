package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.nio.file.*;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
/** 完成、取消和退款使用相同严格边界，黄金样例保留各自业务语义。 */
class LifecycleRefundEventContractTest {
    @Test void lifecycleAndRefundFixturesKeepExactFactsAndRejectVersionConflicts() throws Exception {
        Map<String,Function<String,String>> codecs=Map.of(
            "order-completed",s->OrderCompletedEventCodec.encode(OrderCompletedEventCodec.decode(s)),
            "order-cancelled",s->OrderCancelledEventCodec.encode(OrderCancelledEventCodec.decode(s)),
            "refund-evidence",s->RefundEvidenceEventCodec.encode(RefundEvidenceEventCodec.decode(s)));
        var json=JsonMapper.builder().build();
        for(var entry:codecs.entrySet()) {
            Path root=Path.of("..","docs","contracts",entry.getKey()+"-v1");
            for(String file:new String[]{"minimal.json","complete.json"}) {
                String source=Files.readString(root.resolve(file));
                assertEquals(json.readTree(source),json.readTree(entry.getValue().apply(source)));
            }
            assertThrows(RuntimeException.class,()->entry.getValue().apply(Files.readString(root.resolve("invalid-version.json"))));
            assertThrows(NullPointerException.class,()->entry.getValue().apply("null"));
        }
    }
}
