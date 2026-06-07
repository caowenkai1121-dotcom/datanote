package com.datanote.service;

import com.datanote.domain.project.ProjectHealthScorer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PM2-M2：项目健康分纯函数。 */
public class ProjectHealthScorerTest {

    @Test
    void emptyProjectLow() {
        Map<String, Object> r = ProjectHealthScorer.score(0, 0, 0, 0, 0, 0);
        // 仅 run 维度无运行给中性 12
        assertEquals(12, r.get("total"));
        assertEquals("待完善", r.get("level"));
    }

    @Test
    void richProjectHigh() {
        Map<String, Object> r = ProjectHealthScorer.score(10, 5, 5, 9, 1, 20);
        // asset20+member20+release20+run18+active20 = 98
        assertEquals(98, r.get("total"));
        assertEquals("优秀", r.get("level"));
    }

    @Test
    void runRatio() {
        // 3成功1失败 → 20*3/4=15
        Map<String, Object> r = ProjectHealthScorer.score(0, 0, 0, 3, 1, 0);
        Map<?, ?> dims = (Map<?, ?>) r.get("dims");
        assertEquals(15, dims.get("run"));
    }

    @Test
    void caps() {
        Map<String, Object> r = ProjectHealthScorer.score(100, 100, 100, 100, 0, 100);
        assertEquals(100, r.get("total"));
        Map<?, ?> dims = (Map<?, ?>) r.get("dims");
        assertEquals(20, dims.get("asset"));
        assertEquals(20, dims.get("active"));
    }

    @Test
    void memberTiers() {
        assertEquals(8, ((Map<?, ?>) ProjectHealthScorer.score(0, 1, 0, 0, 0, 0).get("dims")).get("member"));
        assertEquals(14, ((Map<?, ?>) ProjectHealthScorer.score(0, 2, 0, 0, 0, 0).get("dims")).get("member"));
        assertEquals(20, ((Map<?, ?>) ProjectHealthScorer.score(0, 3, 0, 0, 0, 0).get("dims")).get("member"));
    }
}
