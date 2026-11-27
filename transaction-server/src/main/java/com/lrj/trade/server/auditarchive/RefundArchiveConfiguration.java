package com.lrj.trade.server.auditarchive;

import com.lrj.trade.core.AuditWriter;
import com.lrj.trade.core.mapper.AuditMapper;
import com.lrj.trade.refund.AdminRefundQueryService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 交易退款归档独立配置、凭据和执行器，默认不参与核心业务启动。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "trade.refund-archive", name = "enabled", havingValue = "true")
class RefundArchiveConfiguration {
    @Bean
    S3RefundArchiveObjects refundArchiveObjects(@Value("${trade.refund-archive.endpoint}") URI endpoint,
            @Value("${trade.refund-archive.bucket}") String bucket,
            @Value("${trade.refund-archive.region}") String region,
            @Value("${trade.refund-archive.write-key}") String writeKey,
            @Value("${trade.refund-archive.write-secret}") String writeSecret,
            @Value("${trade.refund-archive.restore-key}") String restoreKey,
            @Value("${trade.refund-archive.restore-secret}") String restoreSecret) {
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || writeKey.isBlank() || restoreKey.isBlank()
                || writeSecret.isBlank() || restoreSecret.isBlank() || writeKey.equals(restoreKey) || writeSecret.equals(restoreSecret)) {
            throw new IllegalArgumentException("交易退款归档需要固定桶及分离的写入和恢复身份");
        }
        return new S3RefundArchiveObjects(client(endpoint, region, writeKey, writeSecret),
                client(endpoint, region, restoreKey, restoreSecret), bucket);
    }

    @Bean
    RefundAuditArchive refundAuditArchive(RefundArchiveMapper mapper, AuditMapper audits, AuditWriter audit,
            AdminRefundQueryService refunds, RefundArchiveObjects objects, PlatformTransactionManager manager, Clock clock,
            @Value("${trade.refund-archive.tenant-id}") long tenant,
            @Value("${trade.refund-archive.minimum-age}") Duration minimumAge) {
        if (tenant <= 0 || minimumAge.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("交易退款归档需要单一来源租户和有效的最小归档年龄");
        }
        return new RefundAuditArchive(tenant, minimumAge, clock, mapper, audits, audit, refunds, objects, manager);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trade.refund-archive", name = "scheduler-enabled", havingValue = "true")
    XxlJobSpringExecutor refundArchiveExecutor(@Value("${trade.refund-archive.scheduler-admin}") String admin,
            @Value("${trade.refund-archive.scheduler-token}") String token,
            @Value("${trade.refund-archive.scheduler-port}") int port) {
        if (admin.isBlank() || token.isBlank() || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("交易退款归档执行器需要独立的调度地址、凭据和端口");
        }
        var executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(admin);
        executor.setAccessToken(token);
        executor.setAppname("trade-refund-audit-archive");
        executor.setPort(port);
        return executor;
    }

    @Bean
    RefundArchiveJobs refundArchiveJobs(RefundAuditArchive archive) {
        return new RefundArchiveJobs(archive);
    }

    private static S3Client client(URI endpoint, String region, String key, String secret) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(12))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
    }

    /** 调度不接受外部租户。记录批次或清理数量，避免把取证数据写入调度日志。 */
    record RefundArchiveJobs(RefundAuditArchive archive) {
        @XxlJob("tradeRefundAuditArchiveVerify")
        public void verify() {
            String batchId = archive.verify(XxlJobHelper.getJobParam());
            XxlJobHelper.log("交易退款归档核验批次 {}", batchId == null ? "无符合范围的工作项" : batchId);
        }

        @XxlJob("tradeRefundAuditArchiveCleanRestored")
        public void cleanRestored() {
            XxlJobHelper.log("交易退款归档过期临时副本清理数 {}", archive.cleanExpiredRestorations());
        }
    }
}
