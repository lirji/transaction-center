package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 可信报告与管理摘要分开授权，不开放任意资金状态修改。 */
@RestController
@RequestMapping("")
public class ReconciliationCaseController {
    private final ReconciliationCaseService cases;private final ReconciliationReportAccess access;private final ReconciliationAccess readAccess;
    /** 控制器只编排可信身份和领域服务，不访问Mapper。 */
    public ReconciliationCaseController(ReconciliationCaseService cases,ReconciliationReportAccess access,ReconciliationAccess readAccess) {this.cases=cases;this.access=access;this.readAccess=readAccess;}
    /** 身份决定租户及固定来源，正文中的其他字段仍由领域入口严格验证。 */
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PostMapping("/internal/v1/reconciliation/reports")
    public ReconciliationCaseService.Result accept(@AuthenticationPrincipal Jwt jwt,@RequestBody Input input) {
        var actor=actor(jwt);var source=access.require(actor);
        if(input==null || input.revision()==null) throw new TradeException("INVALID_ARGUMENT","差异报告缺少修订版本");
        long channel;
        try {if(input.channelId()==null || !input.channelId().matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();channel=Long.parseLong(input.channelId());}
        catch(RuntimeException invalid) {throw new TradeException("INVALID_ARGUMENT","原渠道编号无效");}
        return cases.accept(actor,source,new ReconciliationCaseService.Report(input.eventId(),channel,input.statementDate(),input.externalLineKey(),input.revision(),input.differenceType(),input.referenceType(),input.businessRef(),input.evidenceHash(),input.evidenceRef(),input.priorCaseNo()));
    }
    /** 租户后台有界读取摘要，不能取得证据正文与凭据。 */
    @GetMapping("/api/v1/trade/admin/reconciliation-cases")
    public List<ReconciliationCaseService.Summary> page(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="OPEN") String state,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) {
        return cases.page(actor(jwt).tenantId(),state,after,limit);
    }
    /** 单项证据引用读取要求独立财务服务身份，与管理摘要权限分开。 */
    @GetMapping("/internal/v1/reconciliation/cases/{caseNo}")
    public ReconciliationCaseService.Detail read(@AuthenticationPrincipal Jwt jwt,@PathVariable String caseNo) {
        var actor=actor(jwt);readAccess.require(actor);return cases.read(actor.tenantId(),caseNo);
    }
    private Actor actor(Jwt jwt) {
        try {return new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject());}
        catch(RuntimeException invalid) {throw new TradeException("INVALID_IDENTITY","令牌缺少有效身份");}
    }
    /** 渠道ID为字符串，引用不代表执行授权；逻辑来源不接受正文覆盖。 */
    public record Input(String eventId,String channelId,LocalDate statementDate,String externalLineKey,Long revision,String differenceType,String referenceType,String businessRef,String evidenceHash,String evidenceRef,String priorCaseNo) {}
}
