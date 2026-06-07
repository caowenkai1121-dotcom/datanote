package com.datanote.domain.integration.util;

import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PrioritizedTask 排序单测：高 priority 先出队；同级按入队序 FIFO。
 */
public class PrioritizedTaskTest {

    @Test
    void higherPriorityFirst() {
        PriorityQueue<PrioritizedTask> q = new PriorityQueue<>();
        q.add(new PrioritizedTask(1, 1L, () -> {}));
        q.add(new PrioritizedTask(9, 2L, () -> {}));
        q.add(new PrioritizedTask(5, 3L, () -> {}));
        assertEquals(9, q.poll().getPriority());
        assertEquals(5, q.poll().getPriority());
        assertEquals(1, q.poll().getPriority());
    }

    @Test
    void fifoWhenEqualPriority() {
        PriorityQueue<PrioritizedTask> q = new PriorityQueue<>();
        q.add(new PrioritizedTask(5, 100L, () -> {}));
        q.add(new PrioritizedTask(5, 101L, () -> {}));
        assertEquals(100L, q.poll().getJobId().longValue());
    }
}
