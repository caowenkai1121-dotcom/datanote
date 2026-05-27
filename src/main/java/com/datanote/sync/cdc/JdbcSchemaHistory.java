package com.datanote.sync.cdc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnCdcSchemaHistoryMapper;
import com.datanote.model.DnCdcSchemaHistory;
import io.debezium.config.Configuration;
import io.debezium.document.Document;
import io.debezium.document.DocumentReader;
import io.debezium.document.DocumentWriter;
import io.debezium.relational.history.AbstractDatabaseHistory;
import io.debezium.relational.history.DatabaseHistoryException;
import io.debezium.relational.history.DatabaseHistoryListener;
import io.debezium.relational.history.HistoryRecord;
import io.debezium.relational.history.HistoryRecordComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 基于 MySQL（dn_cdc_schema_history 表）的 Debezium schema 历史存储。
 *
 * <p>Debezium 解析 binlog 中的 DDL 必须依赖 schema 历史还原历史表结构。本类通过配置项
 * {@code database.history} 指定全限定名，由框架反射创建，因此 mapper 不能注入，
 * 只能从 {@link CdcStoreHolder#historyMapper} 静态获取。
 *
 * <p>实现方式：继承 {@link AbstractDatabaseHistory}（其只剩 {@code storeRecord} /
 * {@code recoverRecords} 两个抽象方法，外加接口要求的 {@code exists}/{@code storageExists}）。
 * 结构参考 Debezium 自带 {@code FileDatabaseHistory}：每条 {@link HistoryRecord} 用
 * {@link DocumentWriter} 序列化为 JSON 字符串存一行，恢复时按 jobId 读出用
 * {@link DocumentReader} 还原。按 {@code datanote.cdc.job.id} 隔离不同同步任务。
 */
public class JdbcSchemaHistory extends AbstractDatabaseHistory {

    /** 与 Debezium 配置约定的 jobId 配置键。 */
    public static final String JOB_ID_CONFIG = "datanote.cdc.job.id";

    private static final Logger log = LoggerFactory.getLogger(JdbcSchemaHistory.class);

    private final DocumentWriter writer = DocumentWriter.defaultWriter();
    private final DocumentReader reader = DocumentReader.defaultReader();
    private final AtomicBoolean running = new AtomicBoolean();

    private Long jobId;

    @Override
    public void configure(Configuration config, HistoryRecordComparator comparator,
                          DatabaseHistoryListener listener, boolean useCatalogBeforeSchema) {
        super.configure(config, comparator, listener, useCatalogBeforeSchema);
        String raw = config.getString(JOB_ID_CONFIG);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("CDC schema 历史缺少配置项 " + JOB_ID_CONFIG);
        }
        this.jobId = Long.valueOf(raw.trim());
    }

    @Override
    public void start() {
        super.start();
        running.set(true);
    }

    @Override
    protected void storeRecord(HistoryRecord record) throws DatabaseHistoryException {
        if (record == null) {
            return;
        }
        if (!running.get()) {
            throw new IllegalStateException("schema 历史已停止，不再接收记录");
        }
        DnCdcSchemaHistoryMapper mapper = CdcStoreHolder.historyMapper;
        if (mapper == null) {
            throw new IllegalStateException("CdcStoreHolder.historyMapper 未初始化");
        }
        try {
            String json = writer.write(record.document());
            DnCdcSchemaHistory row = new DnCdcSchemaHistory();
            row.setJobId(jobId);
            row.setHistoryData(json);
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (IOException e) {
            throw new DatabaseHistoryException("序列化 schema 历史记录失败 jobId=" + jobId, e);
        }
    }

    @Override
    protected void recoverRecords(Consumer<HistoryRecord> records) {
        DnCdcSchemaHistoryMapper mapper = CdcStoreHolder.historyMapper;
        if (mapper == null) {
            throw new IllegalStateException("CdcStoreHolder.historyMapper 未初始化");
        }
        List<DnCdcSchemaHistory> rows = mapper.selectList(
                new LambdaQueryWrapper<DnCdcSchemaHistory>()
                        .eq(DnCdcSchemaHistory::getJobId, jobId)
                        .orderByAsc(DnCdcSchemaHistory::getId));
        for (DnCdcSchemaHistory row : rows) {
            String json = row.getHistoryData();
            if (json == null || json.isEmpty()) {
                continue;
            }
            try {
                Document doc = reader.read(json);
                records.accept(new HistoryRecord(doc));
            } catch (IOException e) {
                throw new DatabaseHistoryException("还原 schema 历史记录失败 jobId=" + jobId, e);
            }
        }
        log.info("CDC schema 历史恢复完成 jobId={} 条数={}", jobId, rows.size());
    }

    @Override
    public void stop() {
        running.set(false);
        super.stop();
    }

    @Override
    public boolean exists() {
        return storageExists();
    }

    @Override
    public boolean storageExists() {
        DnCdcSchemaHistoryMapper mapper = CdcStoreHolder.historyMapper;
        if (mapper == null || jobId == null) {
            return false;
        }
        Long count = mapper.selectCount(
                new LambdaQueryWrapper<DnCdcSchemaHistory>().eq(DnCdcSchemaHistory::getJobId, jobId));
        return count != null && count > 0;
    }
}
