package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.platform.audit.model.DnLabelAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnLabelAuditMapper extends BaseMapper<DnLabelAudit> {
}
