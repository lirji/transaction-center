package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 验签之后的耐久接收与业务处理，公开HTTP不能绕过可信适配器直接调用。 */
@Service
public class PaymentCallbackService implements PaymentCallbackPort {
    private final CallbackMapper callbacks;
    private final PaymentMapper payments;
    private final ChannelMapper channels;
    private final CallbackPayloadProtector protector;
    private final PaymentObservationPort observations;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** 接收独立提交，处理加入租约事务；两阶段避免业务故障导致已应答报文丢失。 */
    public PaymentCallbackService(CallbackMapper callbacks,PaymentMapper payments,ChannelMapper channels,
        CallbackPayloadProtector protector,PaymentObservationPort observations,JobSchedulerPort jobs,PlatformTransactionManager transactions,Clock clock) {
        this.callbacks=callbacks; this.payments=payments; this.channels=channels; this.protector=protector;
        this.observations=observations; this.jobs=jobs;
        this.clock=clock;
        this.tx=new TransactionTemplate(transactions); this.tx.setTimeout(10);
    }

    /** 只供已经完成原始验签和可靠语义验证的适配器；返回时密文和任务已一起提交。 */
    @Transactional(propagation=Propagation.NEVER)
    public String accept(long tenant,VerifiedPaymentCallback input) {
        Checks.positive(tenant); validate(input);
        // NEVER仍可能建立事务同步上下文；所有Mapper访问必须在自有短事务中释放连接，
        // 否则先读后开写事务会使同一请求占用两个连接，并发时耗尽连接池。
        String attemptId=tx.execute(status->{
            var attempt=payments.findAttempt(tenant,input.requestNo()).stream().findFirst()
                .orElseThrow(()->new TradeException("NOT_FOUND","支付尝试不存在"));
            var channel=channels.findById(tenant,Long.parseLong(attempt.channelId())).getFirst();
            if(!channel.channelCode().equals(input.channelCode()) || !channel.channelMerchantNo().equals(input.channelMerchantNo()))
                throw new TradeException("CALLBACK_BINDING_CONFLICT","渠道收款主体与支付尝试不一致");
            return attempt.attemptId();
        });
        Instant paid=input.providerPaidAt()==null?null:input.providerPaidAt().truncatedTo(ChronoUnit.MILLIS);
        byte[] raw=input.payload(),rawHash=digest(raw);
        String facts=facts(input.requestNo(),input.kind(),input.transactionNo(),input.amount(),input.currency(),paid);
        String number="CB-"+UUID.randomUUID();
        String binding=binding(tenant,number,attemptId,input.channelCode(),input.channelMerchantNo(),input.eventId(),facts,input.signatureVersion(),rawHash);
        EncryptedCallback encrypted;
        try { encrypted=protector.encrypt(binding,raw); }
        finally { Arrays.fill(raw,(byte)0); }
        return tx.execute(status->{
            Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            List<CallbackRecord> stored=List.of();
            // 接收号与验签后的密文同事务写入；重放始终读取原事件和原绑定。
            for(int attempt=0;attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS;attempt++) {
                String businessNo=BusinessNumber.next(BusinessNumber.Type.PAYMENT_CALLBACK,now);
                callbacks.insert(tenant,number,Long.parseLong(attemptId),input.channelCode(),input.channelMerchantNo(),input.eventId(),
                input.requestNo(),input.kind(),input.transactionNo(),input.amount(),input.currency(),paid,rawHash,facts,
                encrypted.ciphertext(),encrypted.keyVersion(),input.signatureVersion(),businessNo,now);
                stored=callbacks.lockEvent(tenant,input.channelCode(),input.channelMerchantNo(),input.eventId());
                if(!stored.isEmpty()) break;
            }
            if(stored.isEmpty()) throw new TradeException("CALLBACK_FACT_CONFLICT","事件唯一键与租户路由冲突");
            var row=stored.getFirst();
            if(!MessageDigest.isEqual(row.payloadHash(),rawHash) || !row.factHash().equals(facts))
                throw new TradeException("CALLBACK_FACT_CONFLICT","相同事件键的原文或解析事实发生冲突");
            jobs.enqueue(tenant,JobType.PAYMENT_CALLBACK,row.callbackNo());
            return row.callbackNo();
        });
    }

    /** 验证密文完整性后处理可信事实，处理标记与外层资金／租约一起提交。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void process(long tenant,String number) {
        var record=record(tenant,number);
        if(record.status().equals("PROCESSED")) return;
        if(!record.status().equals("PENDING")) throw new TradeException("STATE_CONFLICT","回调已转人工核对");
        String facts=facts(record.requestNo(),record.kind(),record.transactionNo(),record.amount(),record.currency(),record.providerPaidAt());
        if(!facts.equals(record.factHash())) throw new TradeException("CALLBACK_EVIDENCE_INVALID","回调解析事实摘要不一致");
        byte[] raw=protector.decrypt(binding(tenant,number,record.attemptId(),record.channelCode(),record.channelMerchantNo(),record.eventId(),facts,
            record.signatureVersion(),record.payloadHash()),new EncryptedCallback(record.keyVersion(),record.payloadCipher()));
        try {
            if(!MessageDigest.isEqual(digest(raw),record.payloadHash()))
                throw new TradeException("CALLBACK_EVIDENCE_INVALID","回调原文摘要不一致");
        } finally { Arrays.fill(raw,(byte)0); }
        if(record.kind().equals("RECEIVED")) {
            observations.received(tenant,record.requestNo(),record.transactionNo(),record.amount(),record.currency(),record.providerPaidAt(),number);
        } else if(record.kind().equals("CLOSED")) {
            observations.closed(tenant,record.requestNo(),number);
        } else throw new TradeException("CALLBACK_EVIDENCE_INVALID","回调事实类型无效");
        // 不先持有回调行锁再锁订单；重复处理依赖资金业务唯一性，最终由外层租约防陈旧提交。
        if(callbacks.processed(tenant,number)!=1) throw new TradeException("STATE_CONFLICT","回调处理状态已改变");
    }

    /** 异常证据不删除也不伪装处理成功，当前租约失效时此标记一起回滚。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void markReview(long tenant,String number,String errorCode) {
        Checks.positive(tenant); Checks.text(number,64);
        if(errorCode==null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) throw new TradeException("INVALID_ARGUMENT","只允许错误分类码");
        callbacks.review(tenant,number,errorCode);
    }

    private CallbackRecord record(long tenant,String number) {
        Checks.positive(tenant); Checks.text(number,64);
        return callbacks.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","回调记录不存在"));
    }

    private static void validate(VerifiedPaymentCallback input) {
        if(input==null) throw new TradeException("INVALID_ARGUMENT","可信回调不能为空");
        Checks.text(input.channelCode(),64); Checks.text(input.channelMerchantNo(),255); Checks.text(input.eventId(),255);
        Checks.text(input.requestNo(),64); Checks.text(input.signatureVersion(),64);
        byte[] payload=input.payload();
        if(payload==null || payload.length<1 || payload.length>65536) throw new TradeException("INVALID_ARGUMENT","回调原文大小无效");
        if("RECEIVED".equals(input.kind())) {
            Checks.text(input.transactionNo(),255);
            if(input.amount()==null || input.amount()<=0 || input.amount()>9007199254740991L || input.currency()==null
                || !input.currency().matches("[A-Z]{3}") || input.providerPaidAt()==null)
                throw new TradeException("INVALID_ARGUMENT","收款事实无效");
        } else if(!"CLOSED".equals(input.kind()) || input.transactionNo()!=null || input.amount()!=null || input.currency()!=null || input.providerPaidAt()!=null) {
            throw new TradeException("INVALID_ARGUMENT","可靠关闭事实字段无效");
        }
    }

    private static String facts(String request,String kind,String transaction,Long amount,String currency,Instant paid) {
        return Checks.hash("payment-observation-v1",request,kind,transaction,amount,currency,paid);
    }

    private static String binding(long tenant,String number,String attempt,String code,String merchant,String event,String facts,String signature,byte[] hash) {
        return Checks.hash("callback-binding-v1",tenant,number,attempt,code,merchant,event,facts,signature,HexFormat.of().formatHex(hash));
    }

    private static byte[] digest(byte[] payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("缺少标准摘要算法"); }
    }
}
