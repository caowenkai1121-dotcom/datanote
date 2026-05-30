package com.datanote.sync.util;
import java.util.List;
import java.util.stream.Collectors;
/** 分桶 checksum SQL:按主键 CRC32 取模分桶,每桶计数 + BIT_XOR(CRC32(行))。 */
public final class ChecksumSqlBuilder {
    private ChecksumSqlBuilder() {}
    public static String build(String db, String table, List<String> syncColumns, List<String> pkColumns, int bucketCount) {
        String pkConcat = pkColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String rowConcat = syncColumns.stream().map(c -> "IFNULL(" + SqlIdentifiers.quote(c) + ",'\\0')").collect(Collectors.joining(", "));
        String full = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        return "SELECT MOD(CRC32(CONCAT_WS('#', " + pkConcat + ")), " + bucketCount + ") AS bk, COUNT(*) cnt, "
            + "BIT_XOR(CRC32(CONCAT_WS('#', " + rowConcat + "))) chk "
            + "FROM " + full + " GROUP BY bk";
    }
}
