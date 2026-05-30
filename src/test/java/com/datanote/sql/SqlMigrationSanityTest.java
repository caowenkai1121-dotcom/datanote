package com.datanote.sql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 迁移卫生检查：防止 agent 写文件残留的杂散标签(如 &lt;/content&gt;)混入 .sql。
 * 这类残留在生产 mysql(无 continueOnError) 会令整个迁移在该行中止，曾导致 36/39 迁移失败。
 */
class SqlMigrationSanityTest {

    private static final String[] STRAY = {"</content>", "<content>", "</parameter>", "<parameter>", "<antml", "```"};

    @Test
    void noStrayTagsInSqlFiles() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("sql"))) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".sql"))::iterator) {
                String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                for (String tag : STRAY) {
                    if (content.contains(tag)) {
                        offenders.add(p + " 含残留标签 " + tag);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "SQL 文件存在杂散标签残留: " + offenders);
    }
}
