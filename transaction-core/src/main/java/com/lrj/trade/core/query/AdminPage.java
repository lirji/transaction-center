package com.lrj.trade.core.query;

import java.time.Instant;
import java.util.List;

/** 主键游标只用于继续读取，不承诺跨请求的一致性快照或总数。 */
public record AdminPage<T extends AdminPage.Row>(List<T> items,String nextAfter,boolean hasMore,Instant observedAt) {
    /** 内部游标由白名单投影提供，不能从业务金额或时间猜测。 */
    public interface Row {
        /** 取内部主键推进游标，JSON投影必须隐藏该辅助字段。 */
        String cursor();
    }
    /** 多读一条判断末页，返回容器保持不可变。 */
    public static <T extends Row> AdminPage<T> of(List<T> rows,int limit,Instant at) {
        boolean more=rows.size()>limit;
        List<T> items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new AdminPage<>(items,more?items.getLast().cursor():null,more,at);
    }
}
