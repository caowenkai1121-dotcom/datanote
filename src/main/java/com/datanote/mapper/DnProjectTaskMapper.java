package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.project.model.DnProjectTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnProjectTaskMapper extends BaseMapper<DnProjectTask> {
}
