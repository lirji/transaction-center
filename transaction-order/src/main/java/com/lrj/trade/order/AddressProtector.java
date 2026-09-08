package com.lrj.trade.order;

import com.lrj.trade.order.model.EncryptedAddress;
import com.lrj.trade.order.model.ShippingAddress;

/** 地址加密端口，可由受控密钥服务适配器替换，不允许明文降级。 */
public interface AddressProtector {
    /** 密文绑定租户、订单及字段用途，防止不同记录之间替换密文。 */
    EncryptedAddress encrypt(long tenant,String orderNo,ShippingAddress address);
}
