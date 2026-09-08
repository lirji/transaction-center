package com.lrj.trade.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只探测部署方明确映射的数据库存储卷，绝不默认使用API容器根分区。 */
public final class DatabaseDiskSpace implements java.util.function.LongSupplier {
    private final Path path;
    private final String expectedStore;
    /** 必须显式给出已核对的挂载路径及文件系统标识；远程库不可随意填写本机路径。 */
    public DatabaseDiskSpace(String path,String expectedStore) {
        if(path==null || path.isBlank() || expectedStore==null || expectedStore.isBlank())
            throw new IllegalArgumentException("启用自动准入必须提供数据库存储卷路径和标识");
        this.path=Path.of(path);this.expectedStore=expectedStore;
        if(!this.path.isAbsolute()) throw new IllegalArgumentException("数据库存储卷路径必须为绝对路径");
    }
    /** 每次重新识别挂载，卷被替换或移除时失败关闭；文件系统操作由采样线程执行。 */
    @Override public long getAsLong() {
        try {
            var store=Files.getFileStore(path);
            if(!store.name().equals(expectedStore)) throw new IllegalStateException("数据库存储卷标识不匹配");
            return store.getUsableSpace();
        } catch(IOException error) { throw new IllegalStateException("无法读取数据库存储卷余量"); }
    }
}
