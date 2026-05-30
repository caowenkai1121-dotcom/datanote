package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnSyncJobDependency;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步任务依赖 Mapper
 */
@Mapper
public interface DnSyncJobDependencyMapper extends BaseMapper<DnSyncJobDependency> {
}
