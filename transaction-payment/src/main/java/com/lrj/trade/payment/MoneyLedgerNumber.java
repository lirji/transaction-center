package com.lrj.trade.payment;

import com.lrj.trade.core.BusinessNumber;
import com.lrj.trade.payment.mapper.PaymentMapper;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;

/** 实收和退款共用资金流水的唯一号空间。 */
final class MoneyLedgerNumber {
    private MoneyLedgerNumber() {}

    /** 仅因流水号本身碰撞时重试，业务去重键冲突仍由原事务裁决。 */
    static void write(PaymentMapper mapper, Instant createdAt, Consumer<String> insert) {
        for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
            String number=BusinessNumber.next(BusinessNumber.Type.MONEY_LEDGER,createdAt);
            try {
                insert.accept(number);
                return;
            } catch (DuplicateKeyException duplicate) {
                if (attempt==BusinessNumber.MAX_ISSUE_ATTEMPTS-1 || mapper.lockLedgerBusinessNumber(number)==null)
                    throw duplicate;
            }
        }
        throw new IllegalStateException("money ledger number allocation exhausted");
    }
}
