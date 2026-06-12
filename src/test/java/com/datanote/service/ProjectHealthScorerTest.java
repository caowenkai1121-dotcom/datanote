package com.datanote.service;

import com.datanote.domain.project.ProjectHealthScorer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1 重写：运行驱动五维健康分 run30/quality25/task20/release15/collab10。 */
public class ProjectHealthScorerTest {

    private static Map<?, ?> dims(Map<String, Object> r) { return (Map<?, ?>) r.get("dims"); }

    @Test
    void emptyProjectNeutral() {
        // 无运行15 + 无规则12 + 无任务10 + 无积压15 + 无协作0 = 52
        Map<String, Object> r = ProjectHealthScorer.score(0, 0, null, 0, 0, 0, 0, 0);
        assertEquals(52, r.get("total"));
        assertEquals("一般", r.get("level"));
    }

    @Test
    void healthyProjectHigh() {
        // run 30*9/10=27 + quality 25*96/100=24 + task 20*(1-1/10)=18 + release 15-5=10 + collab 10 = 89
        Map<String, Object> r = ProjectHealthScorer.score(9, 1, 96, 10, 1, 1, 3, 5);
        assertEquals(89, r.get("total"));
        assertEquals("优秀", r.get("level"));
    }

    @Test
    void runRatio() {
        // 3成功1失败 → 30*3/4 ≈ 23
        Map<String, Object> r = ProjectHealthScorer.score(3, 1, null, 0, 0, 0, 0, 0);
        assertEquals(23, dims(r).get("run"));
    }

    @Test
    void qualityNullVsZero() {
        // 无可评估规则=中性12; 通过率0=0分(真实差)
        assertEquals(12, dims(ProjectHealthScorer.score(0, 0, null, 0, 0, 0, 0, 0)).get("quality"));
        assertEquals(0, dims(ProjectHealthScorer.score(0, 0, 0, 0, 0, 0, 0, 0)).get("quality"));
        assertEquals(25, dims(ProjectHealthScorer.score(0, 0, 100, 0, 0, 0, 0, 0)).get("quality"));
    }

    @Test
    void taskOverduePenalty() {
        // 10任务4超期 → 20*(1-0.4)=12; 全超期=0
        assertEquals(12, dims(ProjectHealthScorer.score(0, 0, null, 10, 4, 0, 0, 0)).get("task"));
        assertEquals(0, dims(ProjectHealthScorer.score(0, 0, null, 5, 5, 0, 0, 0)).get("task"));
    }

    @Test
    void releaseBacklogFloor() {
        // 每单待审批扣5, 不为负
        assertEquals(15, dims(ProjectHealthScorer.score(0, 0, null, 0, 0, 0, 0, 0)).get("release"));
        assertEquals(5, dims(ProjectHealthScorer.score(0, 0, null, 0, 0, 2, 0, 0)).get("release"));
        assertEquals(0, dims(ProjectHealthScorer.score(0, 0, null, 0, 0, 9, 0, 0)).get("release"));
    }

    @Test
    void collabAndDimMax() {
        Map<String, Object> r = ProjectHealthScorer.score(0, 0, null, 0, 0, 0, 2, 3);
        assertEquals(10, dims(r).get("collab"));
        Map<?, ?> max = (Map<?, ?>) r.get("dimMax");
        assertEquals(30, max.get("run"));
        assertEquals(25, max.get("quality"));
    }
}
