-- 关系库同步 迭代V3：任务文件夹 + 删除策略/同步时间戳/folderId 字段
USE datanote;

-- 同步任务文件夹（前端建树用，parent_id=0 为根）
CREATE TABLE IF NOT EXISTS dn_sync_folder (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    folder_name VARCHAR(200) NOT NULL,
    parent_id   BIGINT       DEFAULT 0 COMMENT '父文件夹,0=根',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系库同步任务文件夹';

-- dn_sync_job 增量字段：所属文件夹 + 删除策略 + 同步时间戳标记
ALTER TABLE dn_sync_job
    ADD COLUMN folder_id            BIGINT      DEFAULT 0    COMMENT '所属文件夹',
    ADD COLUMN delete_mode          VARCHAR(20) DEFAULT 'PHYSICAL' COMMENT 'PHYSICAL/LOGICAL',
    ADD COLUMN logical_delete_field VARCHAR(64) DEFAULT NULL,
    ADD COLUMN logical_delete_value VARCHAR(64) DEFAULT '1',
    ADD COLUMN mark_sync_ts         TINYINT     DEFAULT 0    COMMENT '是否标记同步时间戳',
    ADD COLUMN sync_ts_field        VARCHAR(64) DEFAULT NULL;
