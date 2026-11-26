package com.lrj.trade.refund;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderRefundPort;
import com.lrj.trade.payment.PaymentRefundPort;
import com.lrj.trade.refund.mapper.RefundMapper;
import com.lrj.trade.refund.model.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 退款应用组合公开订单/支付端口；额度、明细、审批与事件不可部分提交。 */
@Service
public class RefundService implements RefundApprovalPort,RefundExecutionPort,RefundRecoveryPort,RefundCompensationPort {
    private final RefundMapper mapper;
    private final OrderRefundPort orders;
    private final PaymentRefundPort funds;
    private final RefundPolicyPort policies;
    private final Commands commands;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final Clock clock;
    private final JobSchedulerPort jobs;
    /** 业务模块不导入他域Mapper，所有端口使用同一数据源事务。 */
    public RefundService(RefundMapper mapper,OrderRefundPort orders,PaymentRefundPort funds,RefundPolicyPort policies,
        Commands commands,AuditWriter audit,Outbox outbox,Clock clock,JobSchedulerPort jobs) {
        this.mapper=mapper; this.orders=orders; this.funds=funds; this.policies=policies;
        this.commands=commands; this.audit=audit; this.outbox=outbox; this.clock=clock; this.jobs=jobs;
    }

    /** 正常本人退款不接受渠道/收款标识；价格、归属与可退上限均从冻结事实获取。 */
    public RefundView request(Actor actor,String key,String orderNo,long amount,List<RefundSelection> input,String reason) {
        Checks.text(orderNo,64); Checks.text(key,128); Checks.text(reason,255); money(amount);
        var selected=normalize(input); long sum=selected.stream().mapToLong(RefundSelection::amount).sum();
        if(sum!=amount) throw new TradeException("REFUND_ALLOCATION_INVALID","退款明细合计必须等于申请金额");
        var hashParts=new ArrayList<Object>(List.of(orderNo,amount,reason));
        for(var item:selected) { hashParts.add(item.componentType()); hashParts.add(item.orderItemId()); hashParts.add(item.quantity()); hashParts.add(item.amount()); }
        String hash=Checks.hash(hashParts.toArray());
        String number=commands.run(actor,"refund.request",key,hash,()->{
            var order=orders.lockOwn(actor,orderNo); long orderId=Long.parseLong(order.orderId());
            var source=funds.lockNormalSource(actor.tenantId(),orderId);
            if(!source.disposition().equals("NORMAL") || source.amount()!=order.payableAmount() || !source.currency().equals(order.currency()))
                throw new TradeException("REFUND_BINDING_CONFLICT","订单正常实收与冻结金额不一致");
            var components=components(order,selected);
            var policy=policies.evaluate(actor.tenantId(),source.currency(),source.refundedAmount()+source.reservedAmount()+amount,false);
            var now=now();
            String no="RF-"+UUID.randomUUID();
            String approval=policy.requiresApproval()?"RA-"+UUID.randomUUID():null;
            // 新聚合尚未提交、没有其他工作者可见；先占永久业务请求键，再建立资金及组件。
            // V1资金事件仍校验RF-UUID，业务展示号独立分配，旧消费者可继续核对。
            var row=issueRefund(now,businessNo->
                mapper.insertRefund(actor.tenantId(),no,businessNo,key,actor.key(),Long.parseLong(order.memberId()),orderId,orderNo,
                    Long.parseLong(source.receiptId()),source.receiptNo(),"MRF-"+UUID.randomUUID(),amount,source.currency(),reason,hash,
                    policy.requiresApproval()?"PENDING_APPROVAL":"READY",policy.version(),policy.limit(),approval,
                    approval==null?null:BusinessNumber.next(BusinessNumber.Type.REFUND_APPROVAL,now),
                    approval==null?null:now.plus(Duration.ofDays(1)),now,null),
                ()->mapper.lockByRequest(actor.tenantId(),actor.key(),key));

            if(!row.requestHash().equals(hash)) throw new TradeException("IDEMPOTENCY_CONFLICT","永久退款请求号已绑定其他内容");
            if(!row.refundNo().equals(no)) return row.refundNo();
            funds.reserve(actor.tenantId(),no,source.receiptNo(),orderId,amount,source.currency());
            for(var component:components) {
                var item=component.selection(); Long itemId=item.orderItemId()==null?null:Long.parseLong(item.orderItemId());
                mapper.initializeBudget(actor.tenantId(),orderId,component.key(),item.componentType(),itemId,component.limit(),now);
                if(mapper.reserveBudget(actor.tenantId(),orderId,component.key(),component.limit(),item.amount(),now)!=1)
                    throw new TradeException("REFUND_COMPONENT_EXCEEDED","累计退款不能超过本组件冻结金额");
                mapper.insertItem(actor.tenantId(),row.refundId(),orderId,component.key(),item.componentType(),itemId,item.quantity(),item.amount(),now);
            }
            refreshOrder(actor.tenantId(),row,"REFUND_RESERVED");
            audit.append(actor,"refund.request","REFUND",no,reason,0,0);
            outbox.append(actor.tenantId(),policy.requiresApproval()?"refund.approval-requested.internal.v0":"refund.ready.internal.v0",no);
            jobs.schedule(actor.tenantId(),JobType.REFUND_RECOVER,no,approval==null?now:now.plus(Duration.ofDays(1)));
            return no;
        });
        return read(actor,number);
    }

    /**
     * 仅供可信内部资金补偿编排，不能直接映射为接收客户端租户/金额的HTTP接口。
     * 一笔异常实收建立永久唯一全额补偿；人工审批及原路执行复用正常退款链路。
     */
    public RefundView requestCompensation(long tenant,String receiptNo) {
        Checks.positive(tenant); Checks.text(receiptNo,64);
        var actor=new Actor(tenant,"urn:transaction-center:system","refund-compensation");
        String key="compensation:"+receiptNo;
        String number=commands.run(actor,"refund.compensation",key,Checks.hash(receiptNo),()->{
            // Port先锁订单、意图、收款；补偿聚合必须最后锁，不能按申请键倒置资金锁序。
            var source=funds.lockSource(tenant,receiptNo);
            if(!Set.of("EXCESS","AFTER_TERMINAL","AFTER_CLOSE","AMOUNT_MISMATCH").contains(source.disposition()))
                throw new TradeException("REFUND_COMPENSATION_INVALID","仅异常真实收款可建立补偿");
            long receipt=Long.parseLong(source.receiptId()),orderId=Long.parseLong(source.orderId());
            var order=orders.lockTrusted(tenant,orderId);
            var policy=policies.evaluate(tenant,source.currency(),source.amount(),true);
            String no="RF-"+UUID.randomUUID(), approval="RA-"+UUID.randomUUID(); var now=now();
            String reason="abnormal-receipt:"+source.disposition();
            String hash=Checks.hash(receiptNo,source.amount(),source.currency(),source.disposition());
            // 继续按原始收款键串行化补偿，同源重放返回原退款号。
            var row=issueRefund(now,businessNo->
                mapper.insertRefund(tenant,no,businessNo,key,actor.key(),Long.parseLong(order.memberId()),orderId,
                    order.orderNo(),receipt,receiptNo,"MRF-"+UUID.randomUUID(),source.amount(),source.currency(),reason,hash,
                    "PENDING_APPROVAL",policy.version(),policy.limit(),approval,BusinessNumber.next(BusinessNumber.Type.REFUND_APPROVAL,now),
                    now.plus(Duration.ofDays(1)),now,receipt),
                ()->mapper.lockCompensation(tenant,receipt));

            if(!row.refundNo().equals(no)) return row.refundNo();
            // 成功或释放后的重放先返回永久申请，不能因已退余额拒绝正确重试。
            if(source.refundedAmount()!=0 || source.reservedAmount()!=0)
                throw new TradeException("REFUND_COMPENSATION_CONFLICT","异常收款已有资金处置，须核对后处理");
            funds.reserve(tenant,no,receiptNo,orderId,source.amount(),source.currency());
            String component="EXCESS:"+receipt;
            mapper.initializeBudget(tenant,orderId,component,"EXCESS",null,source.amount(),now);
            changed(mapper.reserveBudget(tenant,orderId,component,source.amount(),source.amount(),now));
            mapper.insertItem(tenant,row.refundId(),orderId,component,"EXCESS",null,0,source.amount(),now);
            // 异常资金不属于正常消费，不能触发正常订单财务状态/首单/库存更新。
            audit.append(actor,"refund.compensation","REFUND",no,reason,0,0);
            outbox.append(tenant,"refund.approval-requested.internal.v0",no);
            jobs.schedule(tenant,JobType.REFUND_RECOVER,no,now.plus(Duration.ofDays(1)));
            return no;
        });
        return view(tenant,find(tenant,number));
    }

    /** 后台只读队列，HTTP调用方须先验证后台作用域；状态变化后须重新从零扫描。 */
    @Transactional(readOnly=true,timeout=10)
    public List<RefundQueueItem> listQueue(long tenant,String status,long after,int limit) {
        Checks.positive(tenant);
        if(status==null || !Set.of("PENDING_APPROVAL","READY","SUBMITTED","SUCCEEDED","FAILED","REJECTED","EXPIRED").contains(status)
            || after<0 || limit<1 || limit>100)
            throw new TradeException("INVALID_ARGUMENT","退款状态或分页参数无效");
        return List.copyOf(mapper.listQueue(tenant,status,after,limit));
    }

    /** UPSERT持有唯一键写锁。优先返回已有来源，来源缺失时有界重试分配新号。 */
    private RefundRow issueRefund(Instant now,java.util.function.Consumer<String> write,
            java.util.function.Supplier<List<RefundRow>> readSource) {
        for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
            String candidate=BusinessNumber.next(BusinessNumber.Type.REFUND,now);
            write.accept(candidate);
            var existing=readSource.get();
            if (!existing.isEmpty()) return existing.getFirst();

        }
        throw new TradeException("REFUND_COMPENSATION_CONFLICT","永久请求已有不同退款关联，须核对后处理");
    }

    /** 仅申请主体可读取；后台与多身份委托须通过后续显式授权入口，不能靠猜编号。 */
    public RefundView read(Actor actor,String number) {
        var row=BusinessNumber.matches(BusinessNumber.Type.REFUND,number)
            ? mapper.findRefundByBusinessNumber(actor.tenantId(),number).stream().findFirst()
              .orElseThrow(()->new TradeException("NOT_FOUND","退款申请不存在"))
            : find(actor.tenantId(),number);
        if(!row.applicantKey().equals(actor.key())) throw new TradeException("NOT_FOUND","退款申请不存在");
        return view(actor.tenantId(),row);
    }

    /** 此端口不等同于流程签名验证，必须先认证审批主体与调用服务。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundView applyApproval(Actor approver,String number,String approvalRef,long decisionVersion,boolean approve,String evidenceRef) {
        Checks.text(approvalRef,64); Checks.positive(decisionVersion); Checks.text(evidenceRef,255);
        long tenant=approver.tenantId(); var row=lock(tenant,number); String decision=approve?"APPROVE":"REJECT";
        if(!approvalRef.equals(row.approvalRef()) || approver.key().equals(row.applicantKey()))
            throw new TradeException("REFUND_APPROVAL_INVALID","审批关联不匹配或申请人不能自审自批");
        if(row.decisionVersion()!=null) {
            if(row.decisionVersion()!=decisionVersion || !row.approvalActorKey().equals(approver.key())
                || !row.approvalDecision().equals(decision) || !row.approvalEvidence().equals(evidenceRef))
                throw new TradeException("REFUND_APPROVAL_CONFLICT","审批决定已受理，不能覆盖或换版本");
            return view(tenant,row);
        }
        if(!row.status().equals("PENDING_APPROVAL") || row.approvalDeadline()==null || !now().isBefore(row.approvalDeadline()))
            throw new TradeException("REFUND_APPROVAL_INVALID","审批状态或受理期限无效");
        changed(mapper.decide(tenant,number,row.version(),approve?"READY":"REJECTED",decisionVersion,approver.key(),decision,evidenceRef,now()));
        if(!approve) {
            funds.releaseUnsubmitted(tenant,number,evidenceRef); releaseComponents(tenant,row);
            refreshOrder(tenant,row,"REFUND_RELEASED");
        }
        audit.append(approver,approve?"refund.approved":"refund.rejected","REFUND",number,evidenceRef,row.version(),row.version()+1);
        outbox.append(tenant,approve?"refund.ready.internal.v0":"refund.rejected.internal.v0",number);
        jobs.wake(tenant,JobType.REFUND_RECOVER,number);
        return view(tenant,mapper.lockRefund(tenant,number).getFirst());
    }

    /** 仅本地许可；调用方还要在同事务末尾校验任务租约，提交后才调用原渠道。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Plan prepare(long tenant,String number) {
        var row=lock(tenant,number); String action;
        // 数据库约束之外再验证批准依据，防未来内部状态迁移误把待审批改成就绪。
        if(Set.of("READY","SUBMITTED").contains(row.status()) &&
            (row.approvalRef()!=null?(row.decisionVersion()==null || !"APPROVE".equals(row.approvalDecision())):
                (row.policyVersion()==0 || row.policyLimit()==0)))
            throw new TradeException("REFUND_APPROVAL_INVALID","缺少冻结政策或有效批准决定");
        if(row.status().equals("READY")) {
            funds.markSubmitted(tenant,number); changed(mapper.markSubmitted(tenant,number,row.version(),now())); action="CREATE";
        } else action=switch(row.status()) { case "SUBMITTED"->"QUERY"; case "PENDING_APPROVAL"->"WAIT_APPROVAL"; default->"DONE"; };
        var source=funds.lockSource(tenant,row.receiptNo());
        return new Plan(tenant,row.refundNo(),row.merchantRefundNo(),row.receiptNo(),source.attemptId(),
            source.channelCode(),source.channelMerchantNo(),source.providerTransactionNo(),row.amount(),row.currency(),action);
    }

    /** 成功后订单只更新财务状态，组件与资金始终同事务，不触发实物回库。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundView confirmSucceeded(long tenant,String number,String providerRefundNo,long amount,String currency,Instant occurredAt,String evidenceRef) {
        var row=lock(tenant,number);
        if(!Set.of("SUBMITTED","SUCCEEDED").contains(row.status()))
            throw new TradeException("REFUND_TERMINAL_CONFLICT","未派发或已释放的矛盾成功须耐久保留并核对");
        funds.confirmSucceeded(tenant,number,providerRefundNo,amount,currency,occurredAt,evidenceRef);
        if(row.status().equals("SUCCEEDED")) return view(tenant,row);
        for(var item:mapper.items(tenant,row.refundId())) changed(mapper.confirmBudget(tenant,row.orderId(),item.componentKey(),item.amount(),now()));
        changed(mapper.markSucceeded(tenant,number,row.version(),providerRefundNo,now()));
        refreshOrder(tenant,row,"REFUND_SUCCEEDED"); outbox.append(tenant,"refund.succeeded.internal.v0",number);
        jobs.wake(tenant,JobType.REFUND_RECOVER,number);
        return view(tenant,mapper.lockRefund(tenant,number).getFirst());
    }

    /** UNKNOWN不能进入此方法；调用方必须验证实际原渠道不可再成功终态并保留证据。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundView confirmFailed(long tenant,String number,String evidenceRef) {
        Checks.text(evidenceRef,255); var row=lock(tenant,number);
        if(row.status().equals("FAILED")) return view(tenant,row);
        if(!row.status().equals("SUBMITTED")) throw new TradeException("REFUND_TERMINAL_CONFLICT","当前退款不能按渠道失败释放");
        funds.releaseVerifiedFailure(tenant,number,evidenceRef); releaseComponents(tenant,row);
        changed(mapper.markFailed(tenant,number,row.version(),now()));
        refreshOrder(tenant,row,"REFUND_RELEASED"); outbox.append(tenant,"refund.failed.internal.v0",number);
        jobs.wake(tenant,JobType.REFUND_RECOVER,number);
        return view(tenant,mapper.lockRefund(tenant,number).getFirst());
    }

    /** 到期撤销不伪造人工审批结果，保留关联并以独立EXPIRED终态记录。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundView expireApproval(long tenant,String number) {
        var row=lock(tenant,number);
        if(row.status().equals("EXPIRED")) return view(tenant,row);
        if(!row.status().equals("PENDING_APPROVAL") || row.approvalDeadline()==null || now().isBefore(row.approvalDeadline()))
            throw new TradeException("REFUND_APPROVAL_INVALID","当前申请不能按审批到期释放");
        funds.releaseUnsubmitted(tenant,number,"refund:approval-expired"); releaseComponents(tenant,row);
        changed(mapper.markExpired(tenant,number,row.version(),now()));
        refreshOrder(tenant,row,"REFUND_RELEASED"); outbox.append(tenant,"refund.expired.internal.v0",number);
        return view(tenant,mapper.lockRefund(tenant,number).getFirst());
    }

    /** 内部短事务读取已冻结的证据归属；不改变派发状态，也不要求会员仍可交易。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public RefundEvidenceBinding evidenceBinding(long tenant,String merchantRefundNo) {
        Checks.positive(tenant); Checks.text(merchantRefundNo,64);
        var row=mapper.findByMerchantRefund(tenant,merchantRefundNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","退款申请不存在"));
        orders.lockTrusted(tenant,row.orderId());
        var source=funds.lockSource(tenant,row.receiptNo());
        return new RefundEvidenceBinding(row.refundNo(),row.merchantRefundNo(),source.channelCode(),
            source.channelMerchantNo(),source.providerTransactionNo(),row.amount(),row.currency());
    }

    /** 到期裁决与现有订单锁一致；审批仍待定时不获得渠道执行许可。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Step prepareRecovery(long tenant,String number) {
        var row=lock(tenant,number);
        if(row.status().equals("PENDING_APPROVAL") && !now().isBefore(row.approvalDeadline())) {
            expireApproval(tenant,number);
            return new Step(prepare(tenant,number),null);
        }
        return new Step(prepare(tenant,number),row.status().equals("PENDING_APPROVAL")?row.approvalDeadline():null);
    }

    /** 原号重放不新建申请、不重新占款，已完成/释放或待审批不能复活。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Step prepareReplay(long tenant,String number) {
        var current=prepareRecovery(tenant,number); var plan=current.plan();
        if(!plan.action().equals("QUERY")) return current;
        return new Step(new Plan(plan.tenantId(),plan.refundNo(),plan.merchantRefundNo(),plan.receiptNo(),
            plan.paymentAttemptId(),plan.channelCode(),plan.channelMerchantNo(),plan.providerTransactionNo(),
            plan.amount(),plan.currency(),"CREATE"),null);
    }

    private RefundRow lock(long tenant,String number) {
        var reference=find(tenant,number); orders.lockTrusted(tenant,reference.orderId());
        // 已提交申请必有永久资金hold，重放reserve只锁并校验绑定，不重新占款。
        funds.reserve(tenant,number,reference.receiptNo(),reference.orderId(),reference.amount(),reference.currency());
        return mapper.lockRefund(tenant,number).getFirst();
    }
    private RefundRow find(long tenant,String number) {
        Checks.positive(tenant); Checks.text(number,64);
        return mapper.findRefund(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","退款申请不存在"));
    }
    private void releaseComponents(long tenant,RefundRow row) {
        for(var item:mapper.items(tenant,row.refundId())) changed(mapper.releaseBudget(tenant,row.orderId(),item.componentKey(),item.amount(),now()));
    }
    private void refreshOrder(long tenant,RefundRow row,String reason) {
        // 资金已经改变；这里必须再次当前读，不能沿用reserve前的在途金额。
        var source=funds.lockSource(tenant,row.receiptNo());
        if(!source.disposition().equals("NORMAL")) return;
        var change=orders.recordRefundChange(tenant,row.orderId(),source.refundedAmount());
        if(!change.orderId().equals(source.orderId())) throw new TradeException("STATE_CONFLICT","退款与订单证据归属不一致");
        var data=new RefundEvidenceEvent.Data(change.orderId(),change.memberId(),row.refundNo(),row.amount(),source.amount(),
            source.refundedAmount(),source.reservedAmount(),source.amount()-source.refundedAmount(),
            source.amount()-source.refundedAmount()-source.reservedAmount(),source.currency(),change.evidenceVersion(),reason);
        var at=now();
        outbox.refundEvidence(RefundEvidenceEvent.create(tenant,"trade.order-evidence-changed.v1",at,data));
        if(reason.equals("REFUND_SUCCEEDED")) outbox.refundEvidence(RefundEvidenceEvent.create(tenant,"trade.refund-succeeded.v1",at,data));
    }
    private RefundView view(long tenant,RefundRow row) {
        return new RefundView(row.refundNo(),row.orderNo(),row.amount(),row.currency(),row.reason(),row.status(),row.approvalRef(),
            row.policyVersion(),row.version(),row.createdAt(),mapper.items(tenant,row.refundId()).stream()
                .map(i->new RefundSelection(i.componentType(),i.orderItemId()==null?null:Long.toString(i.orderItemId()),i.quantity(),i.amount())).toList(),row.businessNo());
    }
    private List<RefundSelection> normalize(List<RefundSelection> input) {
        if(input==null || input.isEmpty() || input.size()>100) throw new TradeException("INVALID_ARGUMENT","退款明细数量必须为1至100");
        var result=new ArrayList<RefundSelection>(); var keys=new HashSet<String>();
        for(var item:input) {
            if(item==null || item.componentType()==null || !Set.of("GOODS","FREIGHT","TAX").contains(item.componentType()) || item.quantity()<0)
                throw new TradeException("REFUND_ALLOCATION_INVALID","退款组件类型或数量无效");
            money(item.amount()); String id=null;
            if(item.componentType().equals("GOODS")) {
                if(item.orderItemId()==null || !item.orderItemId().matches("[1-9][0-9]{0,18}"))
                    throw new TradeException("REFUND_ALLOCATION_INVALID","商品明细编号格式无效");
                try { id=Long.toString(Checks.positive(Long.parseLong(item.orderItemId()))); }
                catch(NumberFormatException invalid) { throw new TradeException("REFUND_ALLOCATION_INVALID","商品明细编号无效"); }
            } else if(item.orderItemId()!=null || item.quantity()!=0) throw new TradeException("REFUND_ALLOCATION_INVALID","非商品组件不能附带商品编号或数量");
            var normalized=new RefundSelection(item.componentType(),id,item.quantity(),item.amount());
            if(!keys.add(componentKey(normalized))) throw new TradeException("REFUND_ALLOCATION_INVALID","同一退款不能重复金额组件");
            result.add(normalized);
        }
        result.sort(Comparator.comparing(this::componentKey)); return List.copyOf(result);
    }
    private List<Component> components(OrderRefundPort.Snapshot order,List<RefundSelection> selected) {
        var result=new ArrayList<Component>();
        for(var item:selected) {
            long limit;
            if(item.componentType().equals("GOODS")) {
                var original=order.items().stream().filter(i->i.itemId().equals(item.orderItemId())).findFirst()
                    .orElseThrow(()->new TradeException("REFUND_ALLOCATION_INVALID","商品明细不属于原订单"));
                if(item.quantity()>original.quantity()) throw new TradeException("REFUND_ALLOCATION_INVALID","涉及商品数量超过原订单");
                limit=original.payableAmount();
            } else limit=item.componentType().equals("FREIGHT")?order.freightAmount():order.taxAmount();
            if(item.amount()>limit) throw new TradeException("REFUND_COMPONENT_EXCEEDED","本次金额超过组件冻结上限");
            result.add(new Component(componentKey(item),limit,item));
        }
        return result;
    }
    private String componentKey(RefundSelection item) { return item.componentType().equals("GOODS")?"GOODS:"+item.orderItemId():item.componentType(); }
    private void changed(int rows) { if(rows!=1) throw new TradeException("STATE_CONFLICT","退款状态或组件额度改变"); }
    private void money(long amount) { if(amount<=0 || amount>9007199254740991L) throw new TradeException("INVALID_ARGUMENT","退款金额必须为有效正整数"); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    /** 服务端冻结组件上限与规范化申请，不能由前端提供limit。 */
    private record Component(String key,long limit,RefundSelection selection) {}
}
