-- 数据治理 M7：数据标准（数据元 + 命名词根 + 码表 + 落标稽核）。幂等，CREATE TABLE IF NOT EXISTS。
USE datanote;

-- 数据元（数据标准核心）
CREATE TABLE IF NOT EXISTS dn_data_element (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  element_code   VARCHAR(80)  NOT NULL COMMENT '数据元编码(唯一,落标按列名比对)',
  name_cn        VARCHAR(120) DEFAULT NULL COMMENT '中文名称',
  data_type      VARCHAR(60)  DEFAULT NULL COMMENT '标准数据类型(如 varchar/bigint)',
  length         INT          DEFAULT NULL COMMENT '标准长度',
  value_domain   VARCHAR(500) DEFAULT NULL COMMENT '值域(取值范围/枚举)',
  sensitive_type VARCHAR(40)  DEFAULT NULL COMMENT '敏感类型建议',
  security_level VARCHAR(40)  DEFAULT NULL COMMENT '密级建议',
  description    VARCHAR(500) DEFAULT NULL COMMENT '描述',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_element_code (element_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据元';

-- 命名词根（中英）
CREATE TABLE IF NOT EXISTS dn_word_root (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  word_cn   VARCHAR(60) DEFAULT NULL COMMENT '中文词',
  word_en   VARCHAR(60) DEFAULT NULL COMMENT '英文全称',
  abbr      VARCHAR(40) DEFAULT NULL COMMENT '英文缩写',
  category  VARCHAR(40) DEFAULT NULL COMMENT '分类',
  KEY idx_word_en (word_en),
  KEY idx_abbr (abbr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='命名词根';

-- 码表（参考数据）
CREATE TABLE IF NOT EXISTS dn_code_dict (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  dict_code   VARCHAR(80)  NOT NULL COMMENT '码表编码(唯一)',
  dict_name   VARCHAR(120) DEFAULT NULL COMMENT '码表名称',
  description VARCHAR(500) DEFAULT NULL COMMENT '描述',
  UNIQUE KEY uk_dict_code (dict_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='码表';

-- 码表明细项
CREATE TABLE IF NOT EXISTS dn_code_dict_item (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  dict_id    BIGINT       NOT NULL COMMENT '所属码表ID',
  item_key   VARCHAR(120) DEFAULT NULL COMMENT '码值',
  item_value VARCHAR(255) DEFAULT NULL COMMENT '含义',
  sort       INT          DEFAULT 0 COMMENT '排序',
  KEY idx_dict_id (dict_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='码表明细项';

-- 落标稽核结果
CREATE TABLE IF NOT EXISTS dn_standard_check_run (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope           VARCHAR(200) DEFAULT NULL COMMENT '稽核范围(all 或 库名)',
  total_count     INT          DEFAULT 0 COMMENT '稽核字段总数',
  violation_count INT          DEFAULT 0 COMMENT '不合规数',
  pass_rate       DECIMAL(5,2) DEFAULT 0 COMMENT '落标率(%)',
  detail          TEXT COMMENT '不合规清单(JSON)',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='落标稽核结果';

-- 预置常用命名词根（仅当词根表为空时插入，避免重复）
INSERT INTO dn_word_root (word_cn, word_en, abbr, category)
SELECT * FROM (
  SELECT '标识' AS word_cn, 'id' AS word_en, 'id' AS abbr, '通用' AS category
  UNION ALL SELECT '编号', 'number', 'no', '通用'
  UNION ALL SELECT '名称', 'name', 'name', '通用'
  UNION ALL SELECT '编码', 'code', 'code', '通用'
  UNION ALL SELECT '金额', 'amount', 'amt', '财务'
  UNION ALL SELECT '数量', 'quantity', 'qty', '通用'
  UNION ALL SELECT '时间', 'time', 'time', '时间'
  UNION ALL SELECT '日期', 'date', 'date', '时间'
  UNION ALL SELECT '状态', 'status', 'status', '通用'
  UNION ALL SELECT '类型', 'type', 'type', '通用'
  UNION ALL SELECT '描述', 'description', 'desc', '通用'
  UNION ALL SELECT '用户', 'user', 'user', '主体'
  UNION ALL SELECT '订单', 'order', 'order', '业务'
  UNION ALL SELECT '创建', 'create', 'create', '操作'
  UNION ALL SELECT '更新', 'update', 'update', '操作'
  UNION ALL SELECT '是否', 'is', 'is', '通用'
  UNION ALL SELECT '手机', 'phone', 'phone', '联系'
  UNION ALL SELECT '邮箱', 'email', 'email', '联系'
  UNION ALL SELECT '地址', 'address', 'addr', '联系'
  UNION ALL SELECT '备注', 'remark', 'remark', '通用'
) seed
WHERE NOT EXISTS (SELECT 1 FROM dn_word_root LIMIT 1);
