package com.lrj.trade.refund;

import com.lrj.trade.core.*;
import com.lrj.trade.refund.mapper.RefundInboxMapper;
import com.lrj.trade.refund.model.*;
import java.security.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** 退款证据先耐久接收再租约消费，业务故障不能吞掉已到达的真实渠道事实。 */
@Service
public class RefundInboxService implements RefundEvidencePort,RefundCallbackPort {
    private static final String NAMESPACE="refund-callback-v1";
    private final RefundInboxMapper mapper;
    private final RefundService refunds;
    private final EvidenceProtectionPort protector;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** 接收使用独立短事务，处理由外层租约事务负责原子提交资金与处理标记。 */
    public RefundInboxService(RefundInboxMapper mapper,RefundService refunds,EvidenceProtectionPort protector,
        JobSchedulerPort jobs,PlatformTransactionManager transactions,Clock clock) {
        this.mapper=mapper; this.refunds=refunds; this.protector=protector; this.jobs=jobs;
        this.clock=clock;
        this.tx=new TransactionTemplate(transactions); this.tx.setTimeout(10);
    }

    /** 正常返回才代表密文与任务已提交；不受发起查询的旧租约控制。 */
    @Override @Transactional(propagation=Propagation.NEVER)
    public void accept(long tenant,VerifiedRefundEvidence input) { receive(tenant,input); }

    /** 内部接收编号供可信回调应答追踪，不向普通客户端开放资金事实入口。 */
    @Transactional(propagation=Propagation.NEVER)
    public String receive(long tenant,VerifiedRefundEvidence input) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("退款证据接收必须独立于业务事务");
        Checks.positive(tenant); validate(input);
        // 归属查询也放短事务，避免NEVER同步上下文持有闲置连接而导致双连接耗尽。
        var route=tx.execute(status->refunds.evidenceBinding(tenant,input.merchantRefundNo()));
        if(!route.channelCode().equals(input.channelCode()) || !route.channelMerchantNo().equals(input.channelMerchantNo()))
            throw new TradeException("REFUND_CHANNEL_BINDING_CONFLICT","退款证据渠道归属不一致");
        Instant occurred=input.occurredAt().truncatedTo(ChronoUnit.MILLIS);
        byte[] raw=input.payload(),hash=digest(raw);
        String facts=facts(input.merchantRefundNo(),input.kind().name(),input.providerTransactionNo(),
            input.providerRefundNo(),input.amount(),input.currency(),occurred);
        String number="RCB-"+UUID.randomUUID();
        EvidenceProtectionPort.Envelope encrypted;
        try {
            encrypted=protector.encrypt(NAMESPACE,binding(tenant,number,route.refundNo(),input.channelCode(),
                input.channelMerchantNo(),input.eventId(),facts,input.signatureVersion(),hash),raw);
        } finally { Arrays.fill(raw,(byte)0); }
        return tx.execute(status->{
            Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            List<RefundCallbackRecord> stored=List.of();
            // 接收号与验签后的密文同事务写入；重放始终读取原事件和原绑定。
            for(int attempt=0;attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS;attempt++) {
                String businessNo=BusinessNumber.next(BusinessNumber.Type.REFUND_CALLBACK,now);
                mapper.insert(tenant,number,route.refundNo(),input.channelCode(),input.channelMerchantNo(),input.eventId(),
                input.merchantRefundNo(),input.kind().name(),input.providerTransactionNo(),input.providerRefundNo(),
                input.amount(),input.currency(),occurred,hash,facts,encrypted.ciphertext(),encrypted.keyVersion(),input.signatureVersion(),businessNo,now);
                stored=mapper.lockEvent(tenant,input.channelCode(),input.channelMerchantNo(),input.eventId());
                if(!stored.isEmpty()) break;
            }
            if(stored.isEmpty()) throw new TradeException("REFUND_CALLBACK_FACT_CONFLICT","退款事件唯一键与租户冲突");
            var row=stored.getFirst();
            if(!MessageDigest.isEqual(row.payloadHash(),hash) || !row.factHash().equals(facts)
                || !row.refundNo().equals(route.refundNo()))
                throw new TradeException("REFUND_CALLBACK_FACT_CONFLICT","重复退款事件的原文或解析事实改变");
            jobs.enqueue(tenant,JobType.REFUND_CALLBACK,row.callbackNo());
            return row.callbackNo();
        });
    }

    /** 先验证证据完整性再裁决资金，失败由Worker另一个受租约保护的事务标记核对。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void process(long tenant,String number) {
        var record=record(tenant,number);
        if(record.status().equals("PROCESSED")) return;
        if(!record.status().equals("PENDING")) throw new TradeException("STATE_CONFLICT","退款证据已转核对");
        String facts=facts(record.merchantRefundNo(),record.kind(),record.transactionNo(),record.providerRefundNo(),
            record.amount(),record.currency(),record.occurredAt());
        if(!facts.equals(record.factHash())) throw new TradeException("CALLBACK_EVIDENCE_INVALID","退款解析事实摘要无效");
        byte[] raw=protector.decrypt(NAMESPACE,binding(tenant,number,record.refundNo(),record.channelCode(),
            record.channelMerchantNo(),record.eventId(),facts,record.signatureVersion(),record.payloadHash()),
            new EvidenceProtectionPort.Envelope(record.keyVersion(),record.payloadCipher()));
        try {
            if(!MessageDigest.isEqual(digest(raw),record.payloadHash()))
                throw new TradeException("CALLBACK_EVIDENCE_INVALID","退款原文摘要无效");
        } finally { Arrays.fill(raw,(byte)0); }
        var route=refunds.evidenceBinding(tenant,record.merchantRefundNo());
        if(!route.refundNo().equals(record.refundNo()) || !route.channelCode().equals(record.channelCode())
            || !route.channelMerchantNo().equals(record.channelMerchantNo())
            || !route.providerTransactionNo().equals(record.transactionNo()))
            throw new TradeException("REFUND_CALLBACK_BINDING_CONFLICT","退款证据原资金来源异常");
        if(record.kind().equals("SUCCEEDED")) {
            if(record.amount()==null || record.amount()!=route.amount() || !route.currency().equals(record.currency()))
                throw new TradeException("REFUND_CALLBACK_BINDING_CONFLICT","退款实际金额或币种异常");
            refunds.confirmSucceeded(tenant,route.refundNo(),record.providerRefundNo(),record.amount(),
                record.currency(),record.occurredAt(),number);
        } else if(record.kind().equals("FAILED_FINAL")) {
            refunds.confirmFailed(tenant,route.refundNo(),number);
        } else throw new TradeException("CALLBACK_EVIDENCE_INVALID","退款证据类型无效");
        if(mapper.processed(tenant,number)!=1) throw new TradeException("STATE_CONFLICT","退款证据状态已改变");
    }

    /** 保留原文及解析事实，只更新固定核对分类；外层必须末尾校验租约。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void markReview(long tenant,String number,String errorCode) {
        Checks.positive(tenant); Checks.text(number,64);
        if(errorCode==null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new TradeException("INVALID_ARGUMENT","仅接受脱敏错误分类");
        mapper.review(tenant,number,errorCode);
    }
    private RefundCallbackRecord record(long tenant,String number) {
        Checks.positive(tenant); Checks.text(number,64);
        return mapper.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","退款证据不存在"));
    }
    private static void validate(VerifiedRefundEvidence input) {
        if(input==null) throw new TradeException("INVALID_ARGUMENT","退款证据不能为空");
        Checks.text(input.channelCode(),64); Checks.text(input.channelMerchantNo(),255); Checks.text(input.eventId(),255);
        Checks.text(input.merchantRefundNo(),64); Checks.text(input.providerTransactionNo(),255); Checks.text(input.signatureVersion(),64);
        byte[] raw=input.payload();
        try {
            if(raw==null || raw.length==0 || raw.length>65536 || input.occurredAt()==null)
                throw new TradeException("INVALID_ARGUMENT","退款证据原文或时间无效");
        } finally { if(raw!=null) Arrays.fill(raw,(byte)0); }
        if(input.kind()==VerifiedRefundEvidence.Kind.SUCCEEDED) {
            Checks.text(input.providerRefundNo(),255);
            if(input.amount()==null || input.amount()<=0 || input.amount()>9007199254740991L
                || input.currency()==null || !input.currency().matches("[A-Z]{3}"))
                throw new TradeException("INVALID_ARGUMENT","退款成功事实无效");
        } else if(input.providerRefundNo()!=null || input.amount()!=null || input.currency()!=null)
            throw new TradeException("INVALID_ARGUMENT","可靠失败不能附带成功资金字段");
    }
    private static String facts(String request,String kind,String original,String provider,Long amount,String currency,Instant occurred) {
        return Checks.hash(NAMESPACE,request,kind,original,provider,amount,currency,occurred);
    }
    private static String binding(long tenant,String number,String refund,String code,String merchant,String event,String facts,String signature,byte[] hash) {
        return Checks.hash(NAMESPACE,tenant,number,refund,code,merchant,event,facts,signature,HexFormat.of().formatHex(hash));
    }
    private static byte[] digest(byte[] raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("缺少标准摘要算法"); }
    }
}
