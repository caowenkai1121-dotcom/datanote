package com.datanote.sync.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * DS-M7：schema 漂移分级（源 vs 源历史，同方言对比可靠）。
 * 新增列=安全（目标自动建表/加列覆盖）；删列/改类型/改主键=危险（告警+暂停该表）。
 */
public final class SchemaDriftClassifier {
    private SchemaDriftClassifier() {}

    public static final class Result {
        public final List<String> added;
        public final List<String> dropped;
        public final List<String> typeChanged;
        public final boolean pkChanged;

        Result(List<String> added, List<String> dropped, List<String> typeChanged, boolean pkChanged) {
            this.added = added;
            this.dropped = dropped;
            this.typeChanged = typeChanged;
            this.pkChanged = pkChanged;
        }

        /** 删列 / 改类型 / 改主键 任一为真即危险。 */
        public boolean dangerous() {
            return !dropped.isEmpty() || !typeChanged.isEmpty() || pkChanged;
        }
    }

    public static Result classify(Map<String, String> prev, List<String> prevPk,
                                  Map<String, String> cur, List<String> curPk) {
        List<String> added = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> typeChanged = new ArrayList<>();
        for (String k : cur.keySet()) {
            if (!prev.containsKey(k)) added.add(k);
            else if (!norm(prev.get(k)).equals(norm(cur.get(k)))) typeChanged.add(k);
        }
        for (String k : prev.keySet()) {
            if (!cur.containsKey(k)) dropped.add(k);
        }
        boolean pkChanged = !new TreeSet<>(prevPk == null ? new ArrayList<>() : prevPk)
                .equals(new TreeSet<>(curPk == null ? new ArrayList<>() : curPk));
        return new Result(added, dropped, typeChanged, pkChanged);
    }

    static String norm(String t) {
        return t == null ? "" : t.trim().toLowerCase();
    }
}
