package com.datanote.domain.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.integration.model.DnSyncErrorRow;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnSyncErrorRowMapper extends BaseMapper<DnSyncErrorRow> {
}
