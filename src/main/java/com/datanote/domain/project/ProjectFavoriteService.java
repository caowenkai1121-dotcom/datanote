package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectAccessMapper;
import com.datanote.domain.project.mapper.DnProjectFavoriteMapper;
import com.datanote.domain.project.model.DnProjectAccess;
import com.datanote.domain.project.model.DnProjectFavorite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 项目收藏/置顶 + 最近访问（按当前用户）。 */
@Service
@RequiredArgsConstructor
public class ProjectFavoriteService {

    private final DnProjectFavoriteMapper favoriteMapper;
    private final DnProjectAccessMapper accessMapper;

    public synchronized boolean toggleFavorite(Long projectId) {
        String user = ProjectService.currentUser();
        DnProjectFavorite f = favoriteMapper.selectOne(fw(user, projectId));
        if (f != null) {
            favoriteMapper.deleteById(f.getId());
            return false;
        }
        DnProjectFavorite n = new DnProjectFavorite();
        n.setUsername(user);
        n.setProjectId(projectId);
        n.setPinned(0);
        favoriteMapper.insert(n);
        return true;
    }

    public synchronized void setPinned(Long projectId, boolean pinned) {
        String user = ProjectService.currentUser();
        DnProjectFavorite f = favoriteMapper.selectOne(fw(user, projectId));
        if (f == null) {
            f = new DnProjectFavorite();
            f.setUsername(user);
            f.setProjectId(projectId);
            f.setPinned(pinned ? 1 : 0);
            favoriteMapper.insert(f);
        } else {
            f.setPinned(pinned ? 1 : 0);
            favoriteMapper.updateById(f);
        }
    }

    public List<DnProjectFavorite> listFavorites() {
        return favoriteMapper.selectList(new LambdaQueryWrapper<DnProjectFavorite>()
                .eq(DnProjectFavorite::getUsername, ProjectService.currentUser()));
    }

    /** 记录访问（按 用户+项目 唯一，access_at 自动刷新）。原子 upsert, 去除 select-then-insert+全局锁: 每次开项目都调, 高并发下不再串行。 */
    public void recordAccess(Long projectId) {
        accessMapper.upsertAccess(ProjectService.currentUser(), projectId, LocalDateTime.now());
    }

    public List<DnProjectAccess> recent(int limit) {
        return accessMapper.selectList(new LambdaQueryWrapper<DnProjectAccess>()
                .eq(DnProjectAccess::getUsername, ProjectService.currentUser())
                .orderByDesc(DnProjectAccess::getAccessAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
    }

    private LambdaQueryWrapper<DnProjectFavorite> fw(String user, Long projectId) {
        return new LambdaQueryWrapper<DnProjectFavorite>()
                .eq(DnProjectFavorite::getUsername, user).eq(DnProjectFavorite::getProjectId, projectId);
    }
}
