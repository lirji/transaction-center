package com.lrj.trade.server.auditarchive;

import com.lrj.trade.core.TradeException;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 交易档案的条件写入与精确回读只使用当前来源的独立身份。 */
final class S3RefundArchiveObjects implements RefundArchiveObjects, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(S3RefundArchiveObjects.class);
    private final S3Client writer;
    private final S3Client reader;
    private final String bucket;

    S3RefundArchiveObjects(S3Client writer, S3Client reader, String bucket) {
        this.writer = writer;
        this.reader = reader;
        this.bucket = bucket;
    }

    @Override
    public String putOnce(String key, byte[] bytes, String sha256) {
        try {
            if (writer.getBucketVersioning(request -> request.bucket(bucket)).status() != BucketVersioningStatus.ENABLED) {
                throw unavailable();
            }
            try {
                var result = writer.putObject(request -> request.bucket(bucket).key(key)
                        .ifNoneMatch("*").contentType("application/gzip").metadata(Map.of("sha256", sha256)),
                        RequestBody.fromBytes(bytes));
                return requireVersion(result.versionId());
            } catch (S3Exception failure) {
                if (failure.statusCode() != 412) throw failure;
                // 对象写入成功但响应可能丢失；仅复用相同摘要的已有版本，并由调用方再次核验。
                var existing = writer.headObject(request -> request.bucket(bucket).key(key));
                if (!sha256.equals(existing.metadata().get("sha256"))) throw unavailable();
                return requireVersion(existing.versionId());
            }
        } catch (SdkException failure) {
            storageFailure("put", failure);
            throw unavailable();
        }
    }

    @Override
    public byte[] verify(String key, String version, int bytes) {
        return read(writer, key, version, bytes);
    }

    @Override
    public byte[] recover(String key, String version, int bytes) {
        return read(reader, key, version, bytes);
    }

    private byte[] read(S3Client client, String key, String version, int bytes) {
        requireVersion(version);
        try (var input = client.getObject(request -> request.bucket(bucket).key(key).versionId(version))) {
            if (!version.equals(input.response().versionId()) || input.response().contentLength() != bytes) throw unavailable();
            return input.readNBytes(bytes + 1);
        } catch (SdkException | IOException failure) {
            storageFailure("read", failure);
            throw unavailable();
        }
    }

    private static void storageFailure(String operation, Exception failure) {
        Integer status = failure instanceof S3Exception response ? response.statusCode() : null;
        LOG.warn("交易归档存储请求未确认 operation={} status={} errorType={}",
                operation, status, failure.getClass().getSimpleName());
    }

    private static String requireVersion(String version) {
        if (version == null || version.isBlank() || "null".equals(version) || version.length() > 255) throw unavailable();
        return version;
    }

    private static TradeException unavailable() {
        return new TradeException("EVIDENCE_UNAVAILABLE", "交易退款档案暂不可用");
    }

    @Override
    public void close() {
        writer.close();
        reader.close();
    }
}
