package com.lrj.trade.server.auditarchive;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.mapper.AuditMapper.Row;
import com.lrj.trade.server.auditarchive.RefundArchiveMapper.Batch;
import com.lrj.trade.server.auditarchive.RefundArchiveMapper.Fact;
import com.lrj.trade.server.auditarchive.RefundArchiveMapper.Subject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 交易退款归档使用带稳定格式版本的 NDJSON。正常回读和恢复执行相同的边界核验。 */
final class RefundArchivePayload {
    static final int FORMAT = 1;
    static final int MAX_FACTS = 200;
    private static final int MAX_COMPRESSED = 512 * 1024;
    private static final int MAX_EXPANDED = 2 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RefundArchivePayload() {}

    /** 首行固定文档版本、原退款和两类证据数量。 */
    record Header(int schemaVersion, Subject subject, int operationCount, int referenceCount) {}

    /** 归档和恢复共用同一结构，禁止拼接跨退款证据。 */
    record Snapshot(Subject refund, List<Row> operations, List<Fact> references) {}

    /** 压缩对象不含领取时间和随机值，重复领取相同快照会得到相同摘要。 */
    static byte[] encode(Snapshot snapshot) {
        var line = new StringBuilder(JSON.writeValueAsString(
                new Header(FORMAT, snapshot.refund(), snapshot.operations().size(), snapshot.references().size()))).append('\n');
        for (var row : snapshot.operations()) line.append(JSON.writeValueAsString(row)).append('\n');
        for (var row : snapshot.references()) line.append(JSON.writeValueAsString(row)).append('\n');
        byte[] plain = line.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_EXPANDED) throw invalid();
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) {
                gzip.write(plain);
            }
            if (bytes.size() > MAX_COMPRESSED) throw invalid();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw invalid();
        }
    }

    /** 只接受精确版本匹配的摘要与结构；解压长度受独立上限约束。 */
    static Snapshot decode(Batch batch, byte[] bytes) {
        if (bytes.length != batch.payloadBytes() || bytes.length > MAX_COMPRESSED || !batch.sha256().equals(digest(bytes))) throw invalid();
        try (var input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] plain = input.readNBytes(MAX_EXPANDED + 1);
            if (plain.length > MAX_EXPANDED) throw invalid();
            var lines = new String(plain, StandardCharsets.UTF_8).lines().toList();
            if (lines.size() != batch.factCount() + 1 || lines.size() > MAX_FACTS + 1) throw invalid();
            var header = JSON.readValue(lines.getFirst(), Header.class);
            if (header == null || header.schemaVersion() != FORMAT || batch.schemaVersion() != FORMAT
                    || header.subject() == null || header.subject().tenantId() != batch.tenantId()
                    || header.subject().id() != batch.refundId() || !batch.refundNo().equals(header.subject().refundNo())
                    || header.operationCount() < 0 || header.referenceCount() < 0
                    || header.operationCount() > MAX_FACTS || header.referenceCount() > MAX_FACTS
                    || header.operationCount() + header.referenceCount() != batch.factCount()) throw invalid();
            var operations = new ArrayList<Row>(header.operationCount());
            var references = new ArrayList<Fact>(header.referenceCount());
            int firstReference = header.operationCount() + 1;
            for (int i = 1; i < firstReference; i++) operations.add(JSON.readValue(lines.get(i), Row.class));
            for (int i = firstReference; i < lines.size(); i++) references.add(JSON.readValue(lines.get(i), Fact.class));
            return new Snapshot(header.subject(), List.copyOf(operations), List.copyOf(references));
        } catch (IOException | JacksonException failure) {
            throw invalid();
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("运行环境缺少档案摘要算法");
        }
    }

    static String encodeRestored(Snapshot snapshot) {
        return JSON.writeValueAsString(snapshot);
    }

    static Snapshot decodeRestored(String snapshot) {
        return JSON.readValue(snapshot, Snapshot.class);
    }

    private static TradeException invalid() {
        return new TradeException("EVIDENCE_UNAVAILABLE", "交易退款档案校验未完成");
    }
}
