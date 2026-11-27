package com.lrj.trade.core.query;

import java.time.Instant;
import java.util.List;
import java.util.function.LongFunction;

/** 页码、总数和结果须由调用方在同一个数据库快照内读取。 */
public record AdminNumberedPage<T>(
        List<T> items, long page, int pageSize, long totalCount, long totalPages, Instant observedAt) {

    /** 先按真实总量归正页码，避免无效深页触发大偏移查询。 */
    public static <T> AdminNumberedPage<T> of(long requestedPage, int pageSize, long totalCount,
            Instant observedAt, LongFunction<List<T>> readRows) {
        AdminNumbers.require(totalCount);
        if (totalCount == 0) {
            return new AdminNumberedPage<>(List.of(), 1, pageSize, 0, 0, observedAt);
        }
        long totalPages = (totalCount - 1) / pageSize + 1;
        long page = Math.min(requestedPage, totalPages);
        long offset = (page - 1) * pageSize;
        return new AdminNumberedPage<>(List.copyOf(readRows.apply(offset)),
                page, pageSize, totalCount, totalPages, observedAt);
    }
}
