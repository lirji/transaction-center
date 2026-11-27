package com.lrj.trade.server.auditarchive;

/** 档案写入和恢复只支持固定对象版本，业务层不持有存储凭据。 */
interface RefundArchiveObjects {
    String putOnce(String key, byte[] bytes, String sha256);

    byte[] verify(String key, String version, int bytes);

    byte[] recover(String key, String version, int bytes);
}
