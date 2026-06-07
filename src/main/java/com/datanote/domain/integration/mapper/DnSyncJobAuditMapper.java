package com.datanote.domain.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.integration.model.DnSyncJobAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步任务操作审计 Mapper。
 */
@Mapper
public interface DnSyncJobAuditMapper extends BaseMapper<DnSyncJobAudit> {
}
