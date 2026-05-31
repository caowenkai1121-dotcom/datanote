package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectAssetMapper;
import com.datanote.mapper.DnProjectMemberMapper;
import com.datanote.mapper.DnProjectReleaseMapper;
import com.datanote.model.DnProject;
import com.datanote.model.DnProjectAsset;
import com.datanote.model.DnProjectMember;
import com.datanote.model.DnProjectRelease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** 项目概览大盘：资产/成员/发布统计 + 合成活动流。 */
@Service
@RequiredArgsConstructor
public class ProjectOverviewService {

    private final ProjectService projectService;
    private final ProjectAssetService assetService;
    private final DnProjectMemberMapper memberMapper;
    private final DnProjectAssetMapper assetMapper;
    private final DnProjectReleaseMapper releaseMapper;

    public Map<String, Object> overview(Long projectId) {
        DnProject p = projectService.getById(projectId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("project", p);

        Map<String, Long> counts = assetService.countsByType(projectId);
        long assetTotal = 0;
        for (Long v : counts.values()) assetTotal += v;
        r.put("assetCounts", counts);
        r.put("assetTotal", assetTotal);

        Long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId));
        r.put("memberCount", memberCount == null ? 0 : memberCount);

        List<DnProjectRelease> releases = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getProjectId, projectId));
        long pending = 0, released = 0;
        for (DnProjectRelease rel : releases) {
            if ("PENDING".equals(rel.getStatus())) pending++;
            else if ("RELEASED".equals(rel.getStatus())) released++;
        }
        r.put("releaseTotal", releases.size());
        r.put("releasePending", pending);
        r.put("releaseReleased", released);

        r.put("activity", buildActivity(projectId, releases));
        return r;
    }

    /** 合成活动流：成员加入 / 资产绑定 / 发布事件，按时间倒序取前 15。 */
    private List<Map<String, Object>> buildActivity(Long projectId, List<DnProjectRelease> releases) {
        List<Object[]> events = new ArrayList<>(); // [LocalDateTime, kind, text]
        for (DnProjectMember m : memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId))) {
            events.add(new Object[]{m.getCreatedAt(), "member",
                    "成员 " + m.getUsername() + " 加入（" + ProjectRoles.label(m.getProjectRole()) + "）"});
        }
        for (DnProjectAsset a : assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId))) {
            events.add(new Object[]{a.getCreatedAt(), "asset",
                    "绑定" + ProjectAssetService.typeLabel(a.getAssetType()) + " " + (a.getAssetName() == null ? a.getAssetId() : a.getAssetName())});
        }
        for (DnProjectRelease rel : releases) {
            LocalDateTime at = rel.getReleasedAt() != null ? rel.getReleasedAt()
                    : (rel.getSubmittedAt() != null ? rel.getSubmittedAt() : rel.getCreatedAt());
            events.add(new Object[]{at, "release", "发布 v" + rel.getVersionNo() + " " + relStatusLabel(rel.getStatus())});
        }
        events.sort((x, y) -> {
            LocalDateTime a = (LocalDateTime) x[0], b = (LocalDateTime) y[0];
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return b.compareTo(a);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < events.size() && i < 15; i++) {
            Object[] e = events.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e[0] == null ? null : e[0].toString());
            m.put("kind", e[1]);
            m.put("text", e[2]);
            out.add(m);
        }
        return out;
    }

    private static String relStatusLabel(String s) {
        switch (s == null ? "" : s) {
            case "PENDING": return "待审批";
            case "APPROVED": return "已通过";
            case "REJECTED": return "已驳回";
            case "RELEASED": return "已发布";
            case "ROLLED_BACK": return "已回滚";
            default: return s;
        }
    }
}
