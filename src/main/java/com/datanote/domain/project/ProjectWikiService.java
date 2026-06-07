package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectWikiPageMapper;
import com.datanote.domain.project.model.DnProjectWikiPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 项目文档 Wiki：页面 CRUD（树由 parent_id 表达，前端构树）。 */
@Service
@RequiredArgsConstructor
public class ProjectWikiService {

    private final DnProjectWikiPageMapper wikiMapper;
    private final ProjectService projectService;

    /** 列表只返回 id/title/parentId/sortOrder（不含 content，避免大字段），按排序+id。 */
    public List<DnProjectWikiPage> listPages(Long projectId) {
        projectService.getById(projectId);
        return wikiMapper.selectList(new LambdaQueryWrapper<DnProjectWikiPage>()
                .select(DnProjectWikiPage::getId, DnProjectWikiPage::getProjectId, DnProjectWikiPage::getParentId,
                        DnProjectWikiPage::getTitle, DnProjectWikiPage::getSortOrder, DnProjectWikiPage::getUpdatedAt,
                        DnProjectWikiPage::getUpdatedBy)
                .eq(DnProjectWikiPage::getProjectId, projectId)
                .orderByAsc(DnProjectWikiPage::getSortOrder).orderByAsc(DnProjectWikiPage::getId));
    }

    public DnProjectWikiPage getPage(Long projectId, Long pageId) {
        DnProjectWikiPage p = wikiMapper.selectById(pageId);
        if (p == null || !projectId.equals(p.getProjectId())) throw new IllegalArgumentException("文档不存在");
        return p;
    }

    public DnProjectWikiPage savePage(Long projectId, DnProjectWikiPage p) {
        projectService.getById(projectId);
        if (p.getTitle() == null || p.getTitle().trim().isEmpty()) throw new IllegalArgumentException("文档标题不能为空");
        p.setTitle(p.getTitle().trim());
        p.setProjectId(projectId);
        if (p.getParentId() == null) p.setParentId(0L);
        String user = ProjectService.currentUser();
        if (p.getId() == null) {
            p.setCreatedBy(user);
            p.setUpdatedBy(user);
            wikiMapper.insert(p);
        } else {
            DnProjectWikiPage old = wikiMapper.selectById(p.getId());
            if (old == null || !projectId.equals(old.getProjectId())) throw new IllegalArgumentException("文档不存在");
            p.setCreatedBy(old.getCreatedBy());
            p.setCreatedAt(old.getCreatedAt());
            p.setUpdatedBy(user);
            wikiMapper.updateById(p);
        }
        return p;
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deletePage(Long projectId, Long pageId) {
        DnProjectWikiPage p = wikiMapper.selectById(pageId);
        if (p == null) return;
        if (!projectId.equals(p.getProjectId())) throw new IllegalArgumentException("文档不属于该项目");
        // 子页面提升为根
        wikiMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DnProjectWikiPage>()
                .eq(DnProjectWikiPage::getParentId, pageId).set(DnProjectWikiPage::getParentId, 0L));
        wikiMapper.deleteById(pageId);
    }
}
