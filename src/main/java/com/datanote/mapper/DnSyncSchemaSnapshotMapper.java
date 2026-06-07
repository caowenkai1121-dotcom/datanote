package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.integration.model.DnSyncSchemaSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnSyncSchemaSnapshotMapper extends BaseMapper<DnSyncSchemaSnapshot> {
}
