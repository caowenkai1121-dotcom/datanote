package com.datanote.sync.cdc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnCdcOffsetMapper;
import com.datanote.model.DnCdcOffset;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.storage.MemoryOffsetBackingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 基于 MySQL（dn_cdc_offset 表）的 Kafka-Connect offset 存储。
 *
 * <p>Debezium 嵌入式通过配置项 {@code offset.storage} 指定本类全限定名，由框架反射创建，
 * 因此 mapper 不能注入，只能从 {@link CdcStoreHolder#offsetMapper} 静态获取。
 *
 * <p>实现方式：继承 {@link MemoryOffsetBackingStore}（其 {@code data} 内存 map 与 {@code save()}
 * 均为 protected）。get/set 走父类内存逻辑；{@code start()} 时把库里该 jobId 的位点装载进内存，
 * {@code save()} 时把内存位点逐条原子 upsert 回写库（依赖唯一键 uk_job_key）。
 *
 * <p>ByteBuffer 与库里 varchar 之间用 Base64 编解码（位点是二进制序列化串，不可直接当字符串存）。
 * 按 {@code datanote.cdc.job.id} 隔离不同同步任务。
 */
public class JdbcOffsetBackingStore extends MemoryOffsetBackingStore {

    /** 与 Debezium 配置约定的 jobId 配置键。 */
    public static final String JOB_ID_CONFIG = "datanote.cdc.job.id";

    private static final Logger log = LoggerFactory.getLogger(JdbcOffsetBackingStore.class);

    private Long jobId;

    @Override
    public void configure(WorkerConfig config) {
        super.configure(config);
        Object raw = config.originals().get(JOB_ID_CONFIG);
        if (raw == null) {
            throw new IllegalStateException("CDC offset 存储缺少配置项 " + JOB_ID_CONFIG);
        }
        this.jobId = Long.valueOf(raw.toString());
    }

    @Override
    public synchronized void start() {
        super.start();
        DnCdcOffsetMapper mapper = CdcStoreHolder.offsetMapper;
        if (mapper == null) {
            throw new IllegalStateException("CdcStoreHolder.offsetMapper 未初始化");
        }
        data.clear();
        List<DnCdcOffset> rows = mapper.selectList(
                new LambdaQueryWrapper<DnCdcOffset>().eq(DnCdcOffset::getJobId, jobId));
        for (DnCdcOffset row : rows) {
            ByteBuffer key = decode(row.getOffsetKey());
            ByteBuffer value = decode(row.getOffsetValue());
            data.put(key, value);
        }
        log.info("CDC offset 装载完成 jobId={} 条数={}", jobId, rows.size());
    }

    /**
     * 父类 set() 在更新内存 data 后调用本方法，这里把内存位点回写库。
     * 逐条按 (job_id, offset_key) 原子 upsert（依赖唯一键 uk_job_key），每条独立原子，
     * 避免"先删全量再批量插入"两步无事务中途崩溃丢该 job 全部 offset。
     */
    @Override
    protected void save() {
        DnCdcOffsetMapper mapper = CdcStoreHolder.offsetMapper;
        if (mapper == null) {
            throw new IllegalStateException("CdcStoreHolder.offsetMapper 未初始化");
        }
        for (Map.Entry<ByteBuffer, ByteBuffer> entry : data.entrySet()) {
            mapper.upsert(jobId, encode(entry.getKey()), encode(entry.getValue()));
        }
        log.debug("CDC offset 回写完成 jobId={} 条数={}", jobId, data.size());
    }

    /** ByteBuffer -> Base64 字符串；null 编码为 null。 */
    private static String encode(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        // duplicate 避免移动原 buffer 的 position（父类内存 map 仍持有该 buffer）
        buffer.duplicate().get(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Base64 字符串 -> ByteBuffer；null/空 解码为 null。 */
    private static ByteBuffer decode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return ByteBuffer.wrap(Base64.getDecoder().decode(text));
    }
}
