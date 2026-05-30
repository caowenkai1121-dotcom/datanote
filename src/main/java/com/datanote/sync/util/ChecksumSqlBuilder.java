package com.datanote.sync.util;
import java.util.List;
import java.util.stream.Collectors;
/** 逐行 hash SQL:选主键列(供应用端分桶)+ 全列 MD5(CONCAT_WS(IFNULL...)) 行指纹。MySQL/Doris 通用,无 BIT_XOR/CRC32。 */
public final class ChecksumSqlBuilder {
    private ChecksumSqlBuilder() {}
    public static String buildRowHashSql(String db, String table, List<String> syncColumns, List<String> pkColumns) {
        String pkSelect = pkColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String rowConcat = syncColumns.stream().map(c -> "IFNULL(" + SqlIdentifiers.quote(c) + ",'\\0')").collect(Collectors.joining(", "));
        String full = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        return "SELECT " + pkSelect + ", MD5(CONCAT_WS('#', " + rowConcat + ")) AS __h FROM " + full;
    }
}
