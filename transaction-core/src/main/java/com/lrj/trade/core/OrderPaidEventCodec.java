package com.lrj.trade.core;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;

/** 本地正常支付事件V1的严格编解码边界；后续发布器复用，不能采用缺字段的默认零值。 */
public final class OrderPaidEventCodec {
    private static final JsonMapper JSON=JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
            DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private OrderPaidEventCodec() {}
    /** 写入已验证不可变对象；保持原始事件身份，不生成发布时替代值。 */
    public static String encode(OrderPaidEvent event) { return JSON.writeValueAsString(java.util.Objects.requireNonNull(event,"正常支付事件不能为空")); }
    /** 本地已存事件校验拒绝未知类型/缺失字段/重复键，不能把损坏数据发送出去。 */
    public static OrderPaidEvent decode(String payload) {
        Checks.text(payload,65536);
        return java.util.Objects.requireNonNull(JSON.readValue(payload,OrderPaidEvent.class),"正常支付事件根值不能为空");
    }
}
