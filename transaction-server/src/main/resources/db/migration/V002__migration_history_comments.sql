-- Flyway技术表同样纳入注释规范；保持原字段类型、默认值和索引不变。
ALTER TABLE flyway_schema_history
  COMMENT = 'Flyway数据库迁移历史，记录版本校验和及执行结果',
  MODIFY COLUMN installed_rank INT NOT NULL COMMENT '迁移安装顺序，主键',
  MODIFY COLUMN version VARCHAR(50) NULL DEFAULT NULL COMMENT '迁移版本号，可重复迁移时允许为空',
  MODIFY COLUMN description VARCHAR(200) NOT NULL COMMENT '迁移描述',
  MODIFY COLUMN type VARCHAR(20) NOT NULL COMMENT '迁移脚本类型',
  MODIFY COLUMN script VARCHAR(1000) NOT NULL COMMENT '迁移脚本名称',
  MODIFY COLUMN checksum INT NULL DEFAULT NULL COMMENT '脚本校验和，用于检测已执行脚本变更',
  MODIFY COLUMN installed_by VARCHAR(100) NOT NULL COMMENT '执行迁移的数据库用户',
  MODIFY COLUMN installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '迁移记录创建时间',
  MODIFY COLUMN execution_time INT NOT NULL COMMENT '迁移执行耗时，单位毫秒',
  MODIFY COLUMN success TINYINT(1) NOT NULL COMMENT '迁移是否执行成功';
