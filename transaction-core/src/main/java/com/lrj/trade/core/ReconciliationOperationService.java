package com.lrj.trade.core;
import com.lrj.trade.core.query.AdminQuery;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.mapper.ReconciliationOperationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 查询申请永久幂等，原归属、差异关联、任务和审计在一个事务内提交。 */
@Service
public class ReconciliationOperationService {
    private static final Set<String> QUEUE_STATES = Set.of("QUEUED", "RUNNING", "RESULT_ACCEPTED", "REVIEW");
    private final ReconciliationOperationMapper mapper;private final ReconciliationQueryExecutorPort executor;private final JobSchedulerPort jobs;private final AuditWriter audit;private final Clock clock;private final ReconciliationCaseService cases;
    /** 使用同一快照读取准确条数和页内记录，沿用当前租户与允许的业务筛选。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AdminNumberedPage<View> numberedPage(long tenant, String state, int limit, long page, String number) {
        Checks.positive(tenant);
        if (state == null || !QUEUE_STATES.contains(state)) throw AdminQuery.invalid();
        if (number != null) Checks.text(number, 64);
        return AdminNumberedPage.of(page, limit, mapper.pageCount(tenant, state, number), Instant.now(),
                offset -> mapper.pageAtOffset(tenant, state, number, limit, offset).stream().map(ReconciliationOperationService::safeView).toList());
    }

    /** 不在申请事务发网络，QueryExecutor.validate仅执行原绑定只读核验。 */
    public ReconciliationOperationService(ReconciliationOperationMapper mapper,ReconciliationQueryExecutorPort executor,JobSchedulerPort jobs,AuditWriter audit,Clock clock,ReconciliationCaseService cases) {this.mapper=mapper;this.executor=executor;this.jobs=jobs;this.audit=audit;this.clock=clock;this.cases=cases;}
    /** RC保证持差异锁读取旧操作终态时不使用更早快照，不需要反向锁旧操作。 */
    @Transactional(timeout=10,isolation=Isolation.READ_COMMITTED)
    public Result request(Actor actor,String key,String number,long expectedVersion,String action,String reason,String ticket) {
        Checks.text(key,128);Checks.text(reason,255);Checks.text(ticket,128);
        // 幂等指纹与租约始终绑定原 UUID，输入查询号不会复制操作。
        String caseNo=BusinessNumber.matches(BusinessNumber.Type.RECON_CASE,number)
                ? cases.read(actor.tenantId(),number).caseNo() : number;
        uuid(caseNo);
        if(expectedVersion<0 || !Set.of("QUERY_PAYMENT","QUERY_REFUND").contains(action==null?"":action)) throw new TradeException("INVALID_ARGUMENT","查询版本或动作无效");
        long tenant=actor.tenantId();String hash=Checks.hash(caseNo,expectedVersion,action,reason,ticket);
        String originalNo=UUID.randomUUID().toString();
        Instant createdAt=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ReconciliationOperation operation=null;
        for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
            String readable=BusinessNumber.next(BusinessNumber.Type.RECON_OPERATION,createdAt);
            mapper.insert(tenant,originalNo,readable,createdAt,actor.key(),key,hash,caseNo,action,reason,ticket);
            operation=mapper.lockRequest(tenant,actor.key(),key).stream().findFirst().orElse(null);
            if(operation!=null) break;
        }
        if(operation==null) throw new TradeException("RECONCILIATION_OPERATION_INVALID","无法建立对账操作编号");
        if(!operation.requestHash().equals(hash)) throw new TradeException("IDEMPOTENCY_CONFLICT","查询请求键已绑定其他内容");
        if(operation.acceptedAt()!=null) return result(operation);
        var cases=mapper.lockCase(tenant,caseNo);if(cases.size()!=1) throw new TradeException("NOT_FOUND","差异不存在");var current=cases.getFirst();
        // 版本条件与合法状态分别拒绝，避免有效If-Match被误报为过期。
        if(current.version()!=expectedVersion) throw new TradeException("VERSION_CONFLICT","差异版本已变化");
        if(!Set.of("OPEN","PROCESSING").contains(current.status())) throw new TradeException("STATE_CONFLICT","差异状态已变化");
        if(current.resolutionRef()!=null) {
            var prior=mapper.find(tenant,current.resolutionRef());
            if(prior.size()!=1 || !terminal(prior.getFirst().status())) throw new TradeException("STATE_CONFLICT","当前差异查询仍在处理中");
        }
        executor.validate(tenant,current.channelId(),action,current.referenceType(),current.businessRef());
        if(mapper.beginCase(tenant,caseNo,expectedVersion,operation.operationNo())!=1) throw new TradeException("STATE_CONFLICT","差异版本已变化");
        long version=Math.addExact(expectedVersion,1);
        if(mapper.accept(tenant,operation.id(),current.channelId(),current.referenceType(),current.businessRef(),version)!=1) throw new TradeException("STATE_CONFLICT","操作回执已变化");
        jobs.enqueue(tenant,JobType.RECON_QUERY,operation.operationNo());audit.append(actor,"reconciliation.query","RECONCILIATION_CASE",caseNo,reason,expectedVersion,version);
        return result(mapper.lockRequest(tenant,actor.key(),key).getFirst());
    }
    /** Worker首先当前读自身操作，缺失或未提交受理结果不能发网络。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public ReconciliationOperation lock(long tenant,String number) {
        var rows=mapper.lock(tenant,number);return rows.size()!=1 || rows.getFirst().acceptedAt()==null?null:rows.getFirst();
    }
    /** Worker再锁差异，校验该操作仍对应当前case版本及原查询引用。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void assertCurrent(long tenant,ReconciliationOperation operation) {
        var rows=mapper.lockCase(tenant,operation.caseNo());
        if(rows.size()!=1) throw new TradeException("RECONCILIATION_OPERATION_INVALID","差异已丢失");var c=rows.getFirst();
        if(!"PROCESSING".equals(c.status()) || c.version()!=operation.caseVersion() || !Objects.equals(c.resolutionRef(),operation.operationNo()) || c.channelId()!=operation.channelId() || !Objects.equals(c.referenceType(),operation.referenceType()) || !Objects.equals(c.businessRef(),operation.businessRef())) throw new TradeException("RECONCILIATION_OPERATION_INVALID","查询操作已经失去当前差异关联");
    }
    /** 本地状态更新必须与调用方的任务fencing同事务；旧租约更新会整体回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void mark(long tenant,ReconciliationOperation operation,String status,String code) {
        if(!Set.of("QUEUED","RUNNING","RESULT_ACCEPTED","REVIEW").contains(status) || code==null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new TradeException("INVALID_ARGUMENT","查询结果分类无效");
        if(mapper.mark(tenant,operation.id(),status,code,terminal(status))!=1) throw new TradeException("STATE_CONFLICT","查询操作已结束");
        if(terminal(status)) audit.append(new Actor(tenant,"internal:worker","reconciliation-query"),"reconciliation.query.result","RECONCILIATION_OPERATION",operation.operationNo(),code,operation.version(),operation.version()+1);
    }
    /** 管理状态视图独立于首次永久回执；无需解析命令缓存推测执行结果。 */
    @Transactional(readOnly=true,timeout=5)
    public List<View> page(long tenant,String state,long after,int limit) {
        return page(tenant,state,after,limit,null);
    }
    /** 管理操作号为精确筛选，历史 UUID 查询继续沿用。 */
    @Transactional(readOnly=true,timeout=5)
    public List<View> page(long tenant,String state,long after,int limit,String number) {
        Checks.positive(tenant);if(state==null || !QUEUE_STATES.contains(state) || after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","查询操作分页参数无效");
        if(number!=null) Checks.text(number,64);
        return mapper.page(tenant,state,after,limit,number).stream().map(ReconciliationOperationService::safeView).toList();
    }
    private static View safeView(ReconciliationOperation o) { return new View(Long.toString(o.id()),o.operationNo(),o.caseNo(),o.action(),o.status(),o.lastCode(),o.version(),o.acceptedAt(),o.businessNo()); }

    private Result result(ReconciliationOperation operation) {return new Result(operation.operationNo(),operation.caseNo(),operation.caseVersion(),"QUEUED",operation.acceptedAt(),operation.businessNo());}
    private static void uuid(String value) {try {if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();} catch(RuntimeException invalid) {throw new TradeException("INVALID_ARGUMENT","差异编号必须为规范UUID");}}
    /** 操作终态不等于case关闭或真实资金成功。 */
    public static boolean terminal(String status) {return "RESULT_ACCEPTED".equals(status)||"REVIEW".equals(status);}
    /** 永久首次结果，后续完成仍返回QUEUED及原case版本。 */
    public record Result(String operationNo,String caseNo,long caseVersion,String initialStatus,Instant acceptedAt,String businessNo) {}
    /** 只展示执行分类，不暴露永久请求键、主体摘要、凭据或原文。 */
    public record View(String id,String operationNo,String caseNo,String action,String status,String lastCode,long version,Instant acceptedAt,String businessNo) {}
}
