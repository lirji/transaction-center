package com.lrj.trade.server.auditarchive;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.mapper.AuditMapper;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 恢复与读权限分离，无有效工单时不公布批次和退款是否存在。 */
@RestController
@RequestMapping("/api/v1/trade/admin/refund-audit-archives")
@ConditionalOnProperty(prefix = "trade.refund-archive", name = {"enabled", "recovery-enabled"}, havingValue = "true")
public class RefundArchiveController {
    private final RefundAuditArchive archive;

    RefundArchiveController(RefundAuditArchive archive) {
        this.archive = archive;
    }

    /** 恢复只返回写入临时查询区的行数，证据内容需要独立查阅授权。 */
    @PostMapping("/{ticket}/restore")
    Restored restore(@AuthenticationPrincipal Jwt token, @PathVariable String ticket,
            @RequestParam MultiValueMap<String, String> params) {
        requireNoFilters(params);
        var snapshot = archive.restore(actor(token), ticket);
        return new Restored(snapshot.operations().size() + snapshot.references().size());
    }

    /** 只读取已恢复且当前有效的工单投影。 */
    @GetMapping("/{ticket}")
    View read(@AuthenticationPrincipal Jwt token, @PathVariable String ticket,
            @RequestParam MultiValueMap<String, String> params) {
        requireNoFilters(params);
        var snapshot = archive.restored(actor(token), ticket);
        var refund = snapshot.refund();
        return new View(new Refund(refund.refundNo(), refund.orderNo(), refund.approvalNo(), refund.receiptNo(),
                refund.amountMinor(), refund.currency(), refund.state(), refund.updatedAt()), snapshot.operations(), snapshot.references());
    }

    private static Actor actor(Jwt token) {
        return new Actor(AdminQuery.number(token.getClaimAsString("tenant_id"), false), token.getIssuer().toString(), token.getSubject());
    }

    private static void requireNoFilters(MultiValueMap<String, String> params) {
        if (!params.isEmpty()) throw new TradeException("INVALID_ARGUMENT", "恢复工单查询参数无效");
    }

    record Restored(int factCount) {}

    record Refund(String refundNo, String orderNo, String approvalNo, String receiptNo, String amountMinor,
                  String currency, String state, Instant updatedAt) {}

    record View(Refund refund, List<AuditMapper.Row> operations, List<RefundArchiveMapper.Fact> references) {}
}
