package com.datanote.platform.ai.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.platform.ai.agent.model.DnAiUserProfile;
import org.apache.ibatis.annotations.Mapper;

/** AI 用户画像 Mapper。 */
@Mapper
public interface DnAiUserProfileMapper extends BaseMapper<DnAiUserProfile> {
}
