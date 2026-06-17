package com.datanote.platform.ai.agent.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** DocxTextExtractor 零依赖 docx 提取单测: 段落换行 + 同段多 run 拼接 + 中文 + 制表/换行。 */
class DocxTextExtractorTest {

    private static final String NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private Path buildDocx(Path dir, String documentXml) throws Exception {
        Path f = dir.resolve("t.docx");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(f))) {
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return f;
    }

    @Test
    void extractsParagraphsAndRuns(@TempDir Path dir) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"" + NS + "\"><w:body>"
                + "<w:p><w:r><w:t>数据治理白皮书</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>本文介绍 </w:t><w:t>DataNote </w:t><w:t>平台。</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        String out = DocxTextExtractor.extract(buildDocx(dir, xml), 100000);
        assertTrue(out.contains("数据治理白皮书"), out);
        assertTrue(out.contains("本文介绍 DataNote 平台。"), out); // 同段多 run 拼接
        // 段落间换行
        assertTrue(out.indexOf("数据治理白皮书") < out.indexOf("本文介绍"));
        assertTrue(out.contains("\n"));
    }

    @Test
    void tabAndBreakRestored(@TempDir Path dir) throws Exception {
        String xml = "<w:document xmlns:w=\"" + NS + "\"><w:body>"
                + "<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        String out = DocxTextExtractor.extract(buildDocx(dir, xml), 100000);
        assertTrue(out.contains("A\tB"), out);
        assertTrue(out.contains("B\nC"), out);
    }

    @Test
    void emptyDocument(@TempDir Path dir) throws Exception {
        String xml = "<w:document xmlns:w=\"" + NS + "\"><w:body></w:body></w:document>";
        String out = DocxTextExtractor.extract(buildDocx(dir, xml), 100000);
        assertEquals("(无文本内容)", out);
    }
}
