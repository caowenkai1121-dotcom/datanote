package com.datanote.platform.ai.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.platform.ai.agent.model.DnAiMemorySkill;
import org.apache.ibatis.annotations.Mapper;

/** AI 自学习记忆/技能 Mapper。 */
@Mapper
public interface DnAiMemorySkillMapper extends BaseMapper<DnAiMemorySkill> {
}
