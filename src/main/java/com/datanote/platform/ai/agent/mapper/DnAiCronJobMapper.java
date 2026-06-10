package com.datanote.platform.ai.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.platform.ai.agent.model.DnAiCronJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnAiCronJobMapper extends BaseMapper<DnAiCronJob> {
}
