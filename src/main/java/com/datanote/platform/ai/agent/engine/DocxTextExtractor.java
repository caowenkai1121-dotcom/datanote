package com.datanote.platform.ai.agent.engine;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 零依赖 docx → 纯文本提取(JDK ZipFile + SAX, 不引 POI, 守"零新依赖"红线; 与 XlsxTextExtractor 同思路)。
 * docx 本质是 zip: 读 word/document.xml, 取 <w:t> 文本, <w:p> 段落→换行, <w:tab>→制表, <w:br>→换行。
 * 限字符数(供 RAG/概览, 非完整还原)。老式 .doc(OLE2 二进制)不支持。
 */
public final class DocxTextExtractor {

    private DocxTextExtractor() {}

    public static String extract(Path docx, int maxChars) throws Exception {
        try (ZipFile zf = new ZipFile(docx.toFile())) {
            ZipEntry doc = zf.getEntry("word/document.xml");
            if (doc == null) return "(空文档)";
            DocHandler h = new DocHandler(maxChars);
            try (InputStream in = zf.getInputStream(doc)) {
                newParser().parse(new InputSource(in), h);
            }
            String out = h.sb.toString().trim();
            if (h.truncated) out += "\n…(已截断, 完整请下载文件)";
            return out.isEmpty() ? "(无文本内容)" : out;
        }
    }

    private static SAXParser newParser() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        // 关闭外部实体, 防 XXE
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return f.newSAXParser();
    }

    /** WordprocessingML SAX: <w:t> 内容捕获, <w:p> 末换行, <w:tab>/<w:br> 还原。 */
    private static final class DocHandler extends DefaultHandler {
        final int maxChars;
        final StringBuilder sb = new StringBuilder();
        boolean inT = false;
        boolean truncated = false;

        DocHandler(int maxChars) { this.maxChars = maxChars; }

        public void startElement(String u, String ln, String qn, Attributes a) {
            if (truncated) return;
            if ("w:t".equals(qn)) inT = true;
            else if ("w:tab".equals(qn)) sb.append('\t');
            else if ("w:br".equals(qn) || "w:cr".equals(qn)) sb.append('\n');
        }

        public void characters(char[] ch, int st, int len) {
            if (!inT || truncated) return;
            sb.append(ch, st, len);
            if (sb.length() >= maxChars) truncated = true;
        }

        public void endElement(String u, String ln, String qn) {
            if ("w:t".equals(qn)) inT = false;
            else if ("w:p".equals(qn) && !truncated) sb.append('\n'); // 段落结束换行
        }
    }
}
