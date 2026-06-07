package com.datanote.domain.orchestration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.orchestration.model.DnBackfillTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnBackfillTaskMapper extends BaseMapper<DnBackfillTask> {
}
