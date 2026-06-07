package com.datanote.domain.integration.util;

import java.util.concurrent.atomic.AtomicLong;

/** 优先级任务包装：priority 大者先执行，同优先级按入队序 FIFO。 */
public final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
    private static final AtomicLong SEQ = new AtomicLong();
    private final int priority;
    private final Long jobId;
    private final Runnable delegate;
    private final long seq;

    public PrioritizedTask(int priority, Long jobId, Runnable delegate) {
        this.priority = priority;
        this.jobId = jobId;
        this.delegate = delegate;
        this.seq = SEQ.incrementAndGet();
    }

    public int getPriority() {
        return priority;
    }

    public Long getJobId() {
        return jobId;
    }

    @Override
    public void run() {
        delegate.run();
    }

    @Override
    public int compareTo(PrioritizedTask o) {
        int c = Integer.compare(o.priority, this.priority); // 大优先级在前
        return c != 0 ? c : Long.compare(this.seq, o.seq);   // 同级 FIFO
    }
}
