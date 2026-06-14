package com.datanote.domain.develop;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnSqlSnippetMapper;
import com.datanote.domain.develop.model.DnSqlSnippet;
import com.datanote.platform.iam.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 片段库服务 — 按创建人隔离的 CRUD + 一键插入热度计数。
 * 数据开发常用 SQL 片段沉淀, 编辑器一键插入提升复用效率。
 */
@Service
@RequiredArgsConstructor
public class SqlSnippetService {

    private final DnSqlSnippetMapper snippetMapper;

    /** 可见片段列表 = 自己的 + 团队共享的(可按关键词过滤名称/分类/说明), 按使用次数与更新时间降序。 */
    public List<DnSqlSnippet> list(String keyword) {
        String me = CurrentUserUtil.currentUser();
        QueryWrapper<DnSqlSnippet> qw = new QueryWrapper<>();
        qw.and(w -> w.eq("created_by", me).or().eq("shared", 1));   // 本人私有 ∪ 团队共享
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            qw.and(w -> w.like("name", kw).or().like("category", kw).or().like("description", kw));
        }
        qw.orderByDesc("use_count").orderByDesc("updated_at");
        List<DnSqlSnippet> list = snippetMapper.selectList(qw);
        return list == null ? new ArrayList<>() : list;
    }

    /** 保存(新建/更新)。校验名称/内容非空; 同一用户下片段名唯一。 */
    public DnSqlSnippet save(DnSqlSnippet s) {
        if (s == null) throw new BusinessException("片段不能为空");
        if (s.getName() == null || s.getName().trim().isEmpty()) throw new BusinessException("片段名不能为空");
        if (s.getContent() == null || s.getContent().trim().isEmpty()) throw new BusinessException("片段内容不能为空");
        String me = CurrentUserUtil.currentUser();
        String name = s.getName().trim();
        s.setName(name);
        // 同名查重(同一用户下唯一; 更新时排除自身)
        QueryWrapper<DnSqlSnippet> dupQ = new QueryWrapper<>();
        dupQ.eq("created_by", me).eq("name", name);
        if (s.getId() != null) dupQ.ne("id", s.getId());
        Long dup = snippetMapper.selectCount(dupQ);
        if (dup != null && dup > 0) throw new BusinessException("已存在同名片段: " + name);

        LocalDateTime now = LocalDateTime.now();
        if (s.getId() != null) {
            DnSqlSnippet old = snippetMapper.selectById(s.getId());
            if (old == null) throw new BusinessException("片段不存在");
            if (!me.equals(old.getCreatedBy()) && !"admin".equals(me)) throw new BusinessException("只能修改自己的片段");
            s.setUpdatedAt(now);
            s.setCreatedBy(old.getCreatedBy());
            s.setUseCount(old.getUseCount());   // 保留热度
            if (s.getShared() == null) s.setShared(old.getShared() != null ? old.getShared() : 0);
            snippetMapper.updateById(s);
        } else {
            s.setCreatedBy(me);
            s.setUseCount(0);
            if (s.getShared() == null) s.setShared(0);
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            snippetMapper.insert(s);
        }
        return s;
    }

    /** 删除(仅创建人或 admin)。 */
    public void delete(Long id) {
        if (id == null) throw new BusinessException("片段 ID 不能为空");
        DnSqlSnippet old = snippetMapper.selectById(id);
        if (old == null) return;
        String me = CurrentUserUtil.currentUser();
        if (me != null && !me.equals(old.getCreatedBy()) && !"admin".equals(me)) {
            throw new BusinessException("只能删除自己的片段");
        }
        snippetMapper.deleteById(id);
    }

    /** 插入计数 +1(热度排序用)。原子自增, 限本人片段或团队共享片段(可见即可计数)。 */
    public void incrementUse(Long id) {
        if (id == null) return;
        String me = CurrentUserUtil.currentUser();
        snippetMapper.update(null, new UpdateWrapper<DnSqlSnippet>()
                .eq("id", id).and(w -> w.eq("created_by", me).or().eq("shared", 1))
                .setSql("use_count = use_count + 1"));
    }
}
