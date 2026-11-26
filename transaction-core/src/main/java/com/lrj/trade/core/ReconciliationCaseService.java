package com.lrj.trade.core;
import com.lrj.trade.core.mapper.ReconciliationCaseMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 可信报告只建立待核对差异，不能修改资金、审批或后台租约。 */
@Service
public class ReconciliationCaseService {
    private final ReconciliationCaseMapper mapper;private final ReconciliationOwnershipPort ownership;private final AuditWriter audit;private final Clock clock;
    /** 来源凭证与差异审计共享数据库事务，无外部网络调用。 */
    public ReconciliationCaseService(ReconciliationCaseMapper mapper,ReconciliationOwnershipPort ownership,AuditWriter audit,Clock clock) {this.mapper=mapper;this.ownership=ownership;this.audit=audit;this.clock=clock;}
    /** 来源由可信入口配置传入；新传输事件可指向同一不可变业务内容。 */
    @Transactional(timeout=10)
    public Result accept(Actor actor,String source,Report report) {
        Checks.text(source,64);validate(report);
        String content=Checks.hash(source,report.channelId(),report.statementDate(),report.externalLineKey(),report.revision(),report.differenceType(),report.referenceType(),report.businessRef(),report.evidenceHash(),report.evidenceRef(),report.priorCaseNo());
        String request=Checks.hash(report.eventId(),content);long tenant=actor.tenantId();
        mapper.receipt(tenant,source,report.eventId(),request);var receipt=mapper.lockReceipt(tenant,source,report.eventId()).getFirst();
        if(!receipt.requestHash().equals(request)) throw new TradeException("IDEMPOTENCY_CONFLICT","来源事件已绑定其他差异报告");
        if(receipt.caseNo()!=null) return new Result(receipt.caseNo(),"OPEN",receipt.acceptedAt());
        ownership.require(tenant,report.channelId(),report.referenceType(),report.businessRef());
        if(report.priorCaseNo()!=null) {
            var prior=mapper.find(tenant,report.priorCaseNo());
            if(prior.size()!=1 || !prior.getFirst().sourceId().equals(source) || prior.getFirst().channelId()!=report.channelId()
                || !prior.getFirst().statementDate().equals(report.statementDate()) || !prior.getFirst().externalLineKey().equals(report.externalLineKey()) || prior.getFirst().revision()>=report.revision())
                throw new TradeException("RECONCILIATION_CASE_CONFLICT","更正关联不属于同一账单行的较早版本");
        }
        String number=UUID.randomUUID().toString();
        Instant createdAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        ReconciliationCase current=null;
        for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
            String readable=BusinessNumber.next(BusinessNumber.Type.RECON_CASE,createdAt);
            mapper.insertCase(tenant,number,readable,createdAt,source,report,content);
            current=mapper.lockBusiness(tenant,source,report).stream().findFirst().orElse(null);
            // 来源回执和修订链继续引用原 UUID；这里只重试可读号冲突。
            if(current!=null || mapper.lockBusinessNumber(readable)==null) break;
        }
        if(current==null) throw new TradeException("RECONCILIATION_CASE_CONFLICT","无法建立差异编号");
        if(!current.contentHash().equals(content)) throw new TradeException("RECONCILIATION_CASE_CONFLICT","账单差异业务键已有不同证据");
        audit.append(actor,"reconciliation.report","RECONCILIATION_CASE",current.caseNo(),"可信差异报告受理",current.version(),current.version());
        if(mapper.finish(tenant,receipt.id(),current.caseNo())!=1) throw new TradeException("STATE_CONFLICT","来源回执已变化");
        var result=mapper.lockReceipt(tenant,source,report.eventId()).getFirst();return new Result(result.caseNo(),"OPEN",result.acceptedAt());
    }
    /** 管理队列只投影元信息，不输出证据引用或完整业务正文。 */
    @Transactional(readOnly=true,timeout=5)
    public List<Summary> page(long tenant,String state,long after,int limit) {
        return page(tenant,state,after,limit,null);
    }
    /** 通过原 UUID 或展示号精确查找，仍限制租户和状态。 */
    @Transactional(readOnly=true,timeout=5)
    public List<Summary> page(long tenant,String state,long after,int limit,String number) {
        Checks.positive(tenant);
        if(state==null || !Set.of("OPEN","PROCESSING","CLOSED").contains(state) || after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","差异队列参数无效");
        if(number!=null) Checks.text(number,64);
        return mapper.page(tenant,state,after,limit,number).stream().map(c->new Summary(c.id(),c.caseNo(),Long.toString(c.channelId()),c.statementDate(),c.differenceType(),c.status(),c.version(),c.businessNo())).toList();
    }
    /** 财务可信入口才可读取证据引用，普通管理列表不能取得此详情。 */
    @Transactional(readOnly=true,timeout=5)
    public Detail read(long tenant,String number) {
        Checks.positive(tenant);
        try {if(!BusinessNumber.matches(BusinessNumber.Type.RECON_CASE,number) && !UUID.fromString(number).toString().equals(number)) throw new IllegalArgumentException();}
        catch(RuntimeException invalid) {throw new TradeException("INVALID_ARGUMENT","差异编号无效");}
        var rows=mapper.find(tenant,number);if(rows.size()!=1) throw new TradeException("NOT_FOUND","差异不存在");
        var c=rows.getFirst();return new Detail(c.caseNo(),c.sourceId(),Long.toString(c.channelId()),c.statementDate(),c.externalLineKey(),c.revision(),c.differenceType(),c.referenceType(),c.businessRef(),c.evidenceHash(),c.evidenceRef(),c.priorCaseNo(),c.status(),c.version(),c.createdAt(),c.businessNo());
    }
    /** 可信财务详情，不含来源传输键、凭据或原始账单正文。 */
    public record Detail(String caseNo,String sourceId,String channelId,LocalDate statementDate,String externalLineKey,long revision,String differenceType,String referenceType,String businessRef,String evidenceHash,String evidenceRef,String priorCaseNo,String status,long version,Instant createdAt,String businessNo) {}
    private void validate(Report r) {
        if(r==null) throw new TradeException("INVALID_ARGUMENT","差异报告不能为空");
        Checks.text(r.eventId(),128);Checks.positive(r.channelId());Checks.text(r.externalLineKey(),128);Checks.text(r.evidenceRef(),255);
        if(r.statementDate()==null || r.statementDate().getYear()<1000 || r.statementDate().getYear()>9999 || r.revision()<0 || r.differenceType()==null || !Set.of("EXCESS","SHORT","AMOUNT","STATUS","FEE").contains(r.differenceType())
            || r.evidenceHash()==null || !r.evidenceHash().matches("[a-f0-9]{64}")) throw new TradeException("INVALID_ARGUMENT","差异报告日期版本类型或证据摘要无效");
        if((r.referenceType()==null)!=(r.businessRef()==null)) throw new TradeException("INVALID_ARGUMENT","业务引用类型和编号须同时提供");
        if(r.referenceType()!=null) {if(!Set.of("PAYMENT_ATTEMPT","RECEIPT","REFUND").contains(r.referenceType())) throw new TradeException("INVALID_ARGUMENT","业务引用类型无效");Checks.text(r.businessRef(),64);}
        if(r.priorCaseNo()!=null) try {if(!UUID.fromString(r.priorCaseNo()).toString().equals(r.priorCaseNo())) throw new IllegalArgumentException();} catch(IllegalArgumentException invalid) {throw new TradeException("INVALID_ARGUMENT","前序差异编号无效");}
    }
    /** REFUND指支付域资金退款编号，不能由客户端引用字符串触发退款。 */
    public record Report(String eventId,long channelId,LocalDate statementDate,String externalLineKey,long revision,String differenceType,String referenceType,String businessRef,String evidenceHash,String evidenceRef,String priorCaseNo) {}
    /** 永久返回首次OPEN受理结果，不冒充当前处置完成状态。 */
    public record Result(String caseNo,String initialStatus,Instant acceptedAt) {}
    /** 脱敏管理摘要；内部主键和渠道ID使用JSON安全字符串。 */
    public record Summary(String id,String caseNo,String channelId,LocalDate statementDate,String differenceType,String status,long version,String businessNo) {}
}
