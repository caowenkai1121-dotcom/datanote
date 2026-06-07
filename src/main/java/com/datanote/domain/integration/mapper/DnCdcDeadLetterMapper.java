package com.datanote.domain.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.integration.model.DnCdcDeadLetter;
import org.apache.ibatis.annotations.Mapper;

/**
 * CDC 死信（坏事件）Mapper。
 */
@Mapper
public interface DnCdcDeadLetterMapper extends BaseMapper<DnCdcDeadLetter> {
}
