package com.datanote.domain.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.domain.project.model.DnProjectAccess;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DnProjectAccessMapper extends BaseMapper<DnProjectAccess> {

    /** 访问记录原子 upsert(靠 uk_acc_user_proj 唯一键)，消除 select-then-insert 竞态与全局锁。 */
    @Insert("INSERT INTO dn_project_access (username, project_id, access_at) VALUES (#{username}, #{projectId}, #{accessAt}) "
            + "ON DUPLICATE KEY UPDATE access_at = VALUES(access_at)")
    int upsertAccess(@Param("username") String username, @Param("projectId") Long projectId,
                     @Param("accessAt") java.time.LocalDateTime accessAt);
}
