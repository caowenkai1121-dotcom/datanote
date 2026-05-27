package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnCdcOffset;

/**
 * CDC binlog 位点 Mapper。
 */
public interface DnCdcOffsetMapper extends BaseMapper<DnCdcOffset> {

    /**
     * 按 (job_id, offset_key) 原子 upsert 单条位点（依赖唯一键 uk_job_key）。
     * 替代"先删全量再批量插入"，避免两步无事务中途崩溃丢该 job 全部 offset。
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO dn_cdc_offset(job_id, offset_key, offset_value, updated_at) VALUES(#{jobId}, #{offsetKey}, #{offsetValue}, NOW()) ON DUPLICATE KEY UPDATE offset_value=VALUES(offset_value), updated_at=NOW()")
    int upsert(@org.apache.ibatis.annotations.Param("jobId") Long jobId, @org.apache.ibatis.annotations.Param("offsetKey") String offsetKey, @org.apache.ibatis.annotations.Param("offsetValue") String offsetValue);
}
