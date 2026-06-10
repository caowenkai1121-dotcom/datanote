package com.datanote.platform.ai.agent.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次 agent 运行的迭代预算(借鉴 hermes-agent iteration_budget.py)。
 * 核心思想: 只有【生产性】迭代(成功执行工具 / 给出终答)消耗预算;
 * 可纠正的废步(模型格式错: 解析失败/未知工具/坏参)通过 {@link #refund()} 退还,
 * 不蚕食真正可用的步数。配合外层硬顶(总迭代上限)防失控刷屏。
 *
 * 线程安全(AtomicInteger): 父与各子代理各持独立预算实例; 加锁防并发委派下的计数错乱(Wave3)。
 */
public class IterationBudget {

    private final int maxTotal;
    private final AtomicInteger used = new AtomicInteger(0);

    public IterationBudget(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    /** 占用一个生产步; 仍有余额返 true(CAS 防并发超占)。 */
    public boolean consume() {
        for (;;) {
            int cur = used.get();
            if (cur >= maxTotal) return false;
            if (used.compareAndSet(cur, cur + 1)) return true;
        }
    }

    /** 退还一个生产步(用于可纠正的废步, 使其不计入预算)。 */
    public void refund() {
        for (;;) {
            int cur = used.get();
            if (cur <= 0) return;
            if (used.compareAndSet(cur, cur - 1)) return;
        }
    }

    public int used() {
        return used.get();
    }

    public int remaining() {
        return Math.max(0, maxTotal - used.get());
    }
}
