package com.datanote.domain.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.project.model.DnProject;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnProjectMapper extends BaseMapper<DnProject> {
}
