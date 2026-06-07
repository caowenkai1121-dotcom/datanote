package com.datanote.service;

import com.datanote.domain.governance.HealthScoreService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 健康分加权纯函数单测 —— 权重归一化、缺维度、边界。
 */
class HealthScoreWeightedTest {

    @Test
    void allDimensionsPresentWeightedAverage() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("规范", 80.0);
        scores.put("质量", 90.0);
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("规范", 20.0);
        weights.put("质量", 20.0);
        // 等权 → (80+90)/2 = 85
        assertEquals(85.0, HealthScoreService.weightedScore(scores, weights), 1e-9);
    }

    @Test
    void weightsNormalizedWhenSumNotHundred() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("规范", 60.0);
        scores.put("质量", 100.0);
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("规范", 1.0);  // 占 1/4
        weights.put("质量", 3.0);  // 占 3/4
        // 60*0.25 + 100*0.75 = 15 + 75 = 90
        assertEquals(90.0, HealthScoreService.weightedScore(scores, weights), 1e-9);
    }

    @Test
    void missingDimensionInWeightsSkipped() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("规范", 50.0);
        scores.put("质量", 80.0);
        scores.put("血缘", 100.0); // weights 无此维度 → 跳过
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("规范", 50.0);
        weights.put("质量", 50.0);
        // 仅按规范/质量等权 → (50+80)/2 = 65
        assertEquals(65.0, HealthScoreService.weightedScore(scores, weights), 1e-9);
    }

    @Test
    void missingDimensionInScoresSkipped() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("规范", 70.0); // 只有规范有分
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("规范", 20.0);
        weights.put("质量", 80.0); // 质量无分 → 不计入，规范独占
        assertEquals(70.0, HealthScoreService.weightedScore(scores, weights), 1e-9);
    }

    @Test
    void emptyReturnsZero() {
        assertEquals(0.0, HealthScoreService.weightedScore(new HashMap<>(), new HashMap<>()), 1e-9);
        assertEquals(0.0, HealthScoreService.weightedScore(null, null), 1e-9);
    }

    @Test
    void allZeroWeightsReturnsZero() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("规范", 80.0);
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("规范", 0.0);
        assertEquals(0.0, HealthScoreService.weightedScore(scores, weights), 1e-9);
    }

    @Test
    void scoreClampedToZeroHundred() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("规范", 150.0);  // 越界高
        scores.put("质量", -30.0);  // 越界低
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("规范", 50.0);
        weights.put("质量", 50.0);
        // clamp 后：100 与 0 等权 → 50
        assertEquals(50.0, HealthScoreService.weightedScore(scores, weights), 1e-9);
    }
}
