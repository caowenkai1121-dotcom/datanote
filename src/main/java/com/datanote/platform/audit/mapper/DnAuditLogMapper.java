package com.datanote.platform.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.platform.audit.model.DnAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnAuditLogMapper extends BaseMapper<DnAuditLog> {
}
