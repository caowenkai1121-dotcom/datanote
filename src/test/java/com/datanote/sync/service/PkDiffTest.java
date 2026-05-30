package com.datanote.sync.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M2：主键级 diff 纯函数——缺失(源有目标无)/多余(目标有源无) 计数与样本。 */
public class PkDiffTest {

    private Set<String> set(String... ks) { return new HashSet<>(Arrays.asList(ks)); }

    @Test
    void identicalSetsNoDiff() {
        DataReconciliationService.PkDiff d = DataReconciliationService.computePkDiff(set("1", "2", "3"), set("3", "2", "1"), 100);
        assertEquals(0, d.missingCount);
        assertEquals(0, d.extraCount);
    }

    @Test
    void missingAndExtra() {
        // 源 {1,2,3,4}，目标 {3,4,5} => 缺失 {1,2}，多余 {5}
        DataReconciliationService.PkDiff d = DataReconciliationService.computePkDiff(set("1", "2", "3", "4"), set("3", "4", "5"), 100);
        assertEquals(2, d.missingCount);
        assertEquals(1, d.extraCount);
        assertTrue(d.missingSample.containsAll(Arrays.asList("1", "2")));
        assertEquals(Collections.singletonList("5"), d.extraSample);
    }

    @Test
    void sampleIsCapped() {
        Set<String> src = new HashSet<>();
        for (int i = 0; i < 50; i++) src.add("k" + i);
        DataReconciliationService.PkDiff d = DataReconciliationService.computePkDiff(src, new HashSet<>(), 10);
        assertEquals(50, d.missingCount, "计数应为全量");
        assertEquals(10, d.missingSample.size(), "样本应被截断到 sample 上限");
    }

    @Test
    void emptyTargetAllMissing() {
        DataReconciliationService.PkDiff d = DataReconciliationService.computePkDiff(set("a", "b"), new HashSet<>(), 100);
        assertEquals(2, d.missingCount);
        assertEquals(0, d.extraCount);
    }
}
