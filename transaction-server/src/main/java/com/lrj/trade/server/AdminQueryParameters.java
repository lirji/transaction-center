package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.core.query.AdminNumbers;
import java.util.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
/** 每个资源独立参数白名单，避免错拼条件变成扩大范围的查询。 */
public final class AdminQueryParameters {
    private static final AdminQuery.WindowKeys APPROVAL_DEADLINE=new AdminQuery.WindowKeys("approvalDeadlineFrom","approvalDeadlineTo");
    private static final AdminQuery.WindowKeys RECEIPT_CONFIRMATION=new AdminQuery.WindowKeys("confirmedFrom","confirmedTo");
    private static final AdminQuery.WindowKeys MEMBER_REGISTRATION=new AdminQuery.WindowKeys("registeredFrom","registeredTo");
    /** 创建时间可重复，用主键打破并列以保证游标顺序。 */
    private enum OrderSort { ID_ASC, CREATED_DESC }
    private AdminQueryParameters() {}
    /** 原队列默认值只在未传状态时补充，空值或重复值仍拒绝。 */
    public static AdminQuery parse(String resource,MultiValueMap<String,String> params) {
        Map<String,List<String>> input=new HashMap<>(params);
        Map<String,Set<String>> fields=switch(resource) {
            case "order" -> AdminQuery.fields("orderNo","memberId","status","financialStatus","warehouseId","currency");
            case "return" -> AdminQuery.fields("returnNo","orderNo","memberId","orderItemId","warehouseId","status");
            case "payment" -> AdminQuery.fields("paymentNo","orderNo","memberId","status");
            case "attempt" -> AdminQuery.fields("status");
            case "receipt" -> AdminQuery.fields("receiptNo","paymentNo","orderNo","channelCode","disposition","abnormalOnly");
            case "quote" -> AdminQuery.fields("quoteId","memberId","businessNo");
            case "refund" -> AdminQuery.fields("refundNo","orderNo","memberId","status","approvalNo");
            case "job" -> AdminQuery.fields("jobType","status","leaseExpired","callbackNo");
            case "product" -> AdminQuery.fields("productNo","skuNo","name","status");
            case "member" -> AdminQuery.fields("memberId","status");
            case "inventory" -> AdminQuery.fields("warehouseId","skuId","maxAvailable");
            default -> throw new IllegalArgumentException("未知管理查询资源");
        };
        switch(resource) {
            case "order" -> { fields.put("sort",Set.of(OrderSort.ID_ASC.name(),OrderSort.CREATED_DESC.name()));fields.put("status",Set.of("PENDING_PAYMENT","CLOSING","CANCELLED","PAID","FULFILLING","COMPLETED"));fields.put("financialStatus",Set.of("UNPAID","NO_PAYMENT_REQUIRED","PAID","PARTIALLY_REFUNDED","REFUNDED")); }
            case "quote" -> {
                var numbers=input.get("quoteId");
                if(numbers!=null && numbers.size()==1 && com.lrj.trade.core.BusinessNumber.matches(
                    com.lrj.trade.core.BusinessNumber.Type.QUOTE,numbers.getFirst())) {
                    if(input.putIfAbsent("businessNo",numbers)!=null) throw new TradeException("INVALID_ARGUMENT","报价编号条件重复");
                    input.remove("quoteId");
                }
            }
            case "product" -> fields.put("status",Set.of("DRAFT","PUBLISHED","OFFLINE"));
            case "member" -> fields.put("status",Set.of("ACTIVE","FROZEN"));
            case "return" -> fields.put("status",Set.of("OPEN","INSPECTED"));
            case "payment" -> fields.put("status",Set.of("READY","PENDING","SUCCEEDED","CLOSED","REVIEW"));
            case "attempt" -> fields.put("status",Set.of("PREPARED","UNKNOWN","SUCCEEDED","CLOSED"));
            case "receipt" -> fields.put("disposition",Set.of("NORMAL","EXCESS","AMOUNT_MISMATCH","AFTER_CLOSE","AFTER_TERMINAL"));
            case "refund" -> { fields.put("status",Set.of("ALL","PENDING_APPROVAL","READY","SUBMITTED","SUCCEEDED","FAILED","REJECTED","EXPIRED"));input.putIfAbsent("status",List.of("PENDING_APPROVAL")); }
            case "job" -> { fields.put("status",Set.of("READY","RUNNING","DONE","REVIEW"));fields.put("jobType",Arrays.stream(JobType.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet())); }
            default -> { }
        }
        return switch(resource) {
            case "refund" -> AdminQuery.parse(input,fields,true,APPROVAL_DEADLINE);
            case "receipt" -> AdminQuery.parse(input,fields,true,RECEIPT_CONFIRMATION);
            case "member" -> AdminQuery.parse(input,fields,false,MEMBER_REGISTRATION);
            case "attempt", "product", "inventory" -> AdminQuery.parse(input,fields,false);
            default -> AdminQuery.parse(input,fields,true);
        };
    }
    /** 页码和游标是两种定位方式，只接受单一页码且沿用原订单筛选校验。 */
    public static Numbered orderPage(MultiValueMap<String, String> params) {
        return numbered(params, input -> parse("order", input));
    }

    /** 把公用页码输入与各资源独立校验组合，旧接口继续只消费原参数。 */
    public static Numbered numbered(MultiValueMap<String, String> params,
            java.util.function.Function<MultiValueMap<String, String>, AdminQuery> parseFilters) {
        Map<String, List<String>> input = new HashMap<>(params);
        var pages = input.remove("page");
        if (input.containsKey("after") || pages != null && pages.size() != 1) {
            throw AdminQuery.invalid();
        }
        long page = pages == null ? 1 : AdminQuery.number(pages.getFirst(), false);
        if (page > AdminNumbers.MAX_SAFE_INTEGER) {
            throw AdminQuery.invalid();
        }
        return new Numbered(parseFilters.apply(new LinkedMultiValueMap<>(input)), page);
    }

    /** 页码和筛选条件独立，查询总量始终基于完整筛选范围。 */
    public record Numbered(AdminQuery query, long page) {}

    /** 审计使用时间游标，拒绝混入通用的主键游标或重复条件。 */
    public static AdminQuery operationAudit(MultiValueMap<String,String> params) {
        Map<String,List<String>> input = new HashMap<>(params);
        var cursor = input.remove("cursor");
        if (input.containsKey("after") || cursor != null && cursor.size() != 1)
            throw new TradeException("INVALID_ARGUMENT","审计查询参数无效");
        return AdminQuery.parse(input,AdminQuery.fields("resourceType","resourceRef"),true);
    }
    /** 详情无查询参数，未知字段不会被静默忽略。 */
    public static void none(MultiValueMap<String,String> params) { if(!params.isEmpty()) throw new TradeException("INVALID_ARGUMENT","详情接口不接受查询参数"); }
}
