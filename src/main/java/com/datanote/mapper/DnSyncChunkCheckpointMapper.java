package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.integration.model.DnSyncChunkCheckpoint;
import org.apache.ibatis.annotations.Mapper;

/**
 * 全量分片断点续传 Mapper。
 */
@Mapper
public interface DnSyncChunkCheckpointMapper extends BaseMapper<DnSyncChunkCheckpoint> {
}
