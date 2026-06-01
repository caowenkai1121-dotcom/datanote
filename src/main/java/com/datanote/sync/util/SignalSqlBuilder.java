package com.datanote.sync.util;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Debezium 增量快照 signal 表写入 SQL 与 execute-snapshot 信号构造（纯逻辑，可单测）。
 *
 * <p>向源库 {@code dn_cdc_signal} 表 INSERT 一行 type=execute-snapshot 的信号，
 * data 列为 INCREMENTAL 类型的 JSON（含待补数表清单），即可触发无锁分块增量快照。
 */
public final class SignalSqlBuilder {

    private SignalSqlBuilder() {
    }

    /** 源库 signal 表的参数化 INSERT（库名经 SqlIdentifiers 校验+反引号）。 */
    public static String insertSql(String signalDb) {
        return "INSERT INTO " + SqlIdentifiers.quote(signalDb) + "." + SqlIdentifiers.quote("dn_cdc_signal")
                + " (" + SqlIdentifiers.quote("id") + "," + SqlIdentifiers.quote("type") + "," + SqlIdentifiers.quote("data") + ") VALUES (?,?,?)";
    }

    /** execute-snapshot 信号的 data 列 JSON（INCREMENTAL 类型，含 data-collections 表清单）。 */
    public static String executeSnapshotData(List<String> dataCollections) {
        // 转义反斜杠与双引号,避免表名含特殊字符破坏 JSON 结构
        String list = dataCollections.stream()
                .map(c -> "\"" + c.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(","));
        return "{\"data-collections\": [" + list + "], \"type\": \"INCREMENTAL\"}";
    }
}
