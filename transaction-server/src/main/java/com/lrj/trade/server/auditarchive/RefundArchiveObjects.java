package com.lrj.trade.server.auditarchive;

/** 档案写入和恢复只支持固定对象版本，业务层不持有存储凭据。 */
interface RefundArchiveObjects {
    /** 按固定键条件写入并返回精确对象版本。 */
    String putOnce(String key, byte[] bytes, String sha256);

    /** 以归档身份回读和核验写入内容。 */
    byte[] verify(String key, String version, int bytes);

    /** 以独立恢复身份回读指定版本。 */
    byte[] recover(String key, String version, int bytes);
}
