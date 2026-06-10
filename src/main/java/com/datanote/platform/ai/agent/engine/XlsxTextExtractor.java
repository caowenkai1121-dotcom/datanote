package com.datanote.platform.ai.agent.engine;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 零依赖 xlsx → CSV 文本提取(JDK ZipFile + SAX, 不引 POI, 守"零新依赖"红线)。
 * xlsx 本质是 zip: 读 xl/sharedStrings.xml(共享串表) + 首个 worksheet, 还原单元格→CSV 行。
 * 仅取第一个工作表, 限行数/字符数(供 agent 概览分析, 非完整导出)。老式 .xls(OLE2 二进制)不支持。
 */
public final class XlsxTextExtractor {

    private XlsxTextExtractor() {}

    public static String extract(Path xlsx, int maxRows, int maxChars) throws Exception {
        try (ZipFile zf = new ZipFile(xlsx.toFile())) {
            List<String> shared = readShared(zf);
            ZipEntry sheet = firstSheet(zf);
            if (sheet == null) return "(空工作簿)";
            SheetHandler h = new SheetHandler(shared, maxRows, maxChars);
            try (InputStream in = zf.getInputStream(sheet)) {
                newParser().parse(new InputSource(in), h);
            }
            String out = h.sb.toString().trim();
            if (h.truncated) out += "\n…(已截断, 完整请下载文件)";
            return out.isEmpty() ? "(无数据)" : out;
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

    /** 共享串表: 每个 <si> 内 <t> 文本(富文本多 <t> 拼接)。 */
    private static List<String> readShared(ZipFile zf) throws Exception {
        List<String> list = new ArrayList<>();
        ZipEntry e = zf.getEntry("xl/sharedStrings.xml");
        if (e == null) return list;
        DefaultHandler h = new DefaultHandler() {
            StringBuilder cur = null;
            boolean inT = false;
            public void startElement(String u, String ln, String qn, Attributes a) {
                if ("si".equals(qn)) cur = new StringBuilder();
                else if ("t".equals(qn)) inT = true;
            }
            public void characters(char[] ch, int st, int len) { if (inT && cur != null) cur.append(ch, st, len); }
            public void endElement(String u, String ln, String qn) {
                if ("t".equals(qn)) inT = false;
                else if ("si".equals(qn)) { list.add(cur == null ? "" : cur.toString()); cur = null; }
            }
        };
        try (InputStream in = zf.getInputStream(e)) { newParser().parse(new InputSource(in), h); }
        return list;
    }

    /** 取第一个 worksheet 条目(sheet1.xml 优先, 否则任意 worksheets/*.xml)。 */
    private static ZipEntry firstSheet(ZipFile zf) {
        ZipEntry s1 = zf.getEntry("xl/worksheets/sheet1.xml");
        if (s1 != null) return s1;
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.getName().startsWith("xl/worksheets/") && e.getName().endsWith(".xml")) return e;
        }
        return null;
    }

    /** 工作表 SAX: 逐行还原单元格(t="s" 查共享串), 输出 CSV。 */
    private static final class SheetHandler extends DefaultHandler {
        final List<String> shared;
        final int maxRows, maxChars;
        final StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int rowCount = 0;

        TreeMap<Integer, String> row;   // 列序 → 值
        String cellRef, cellType;
        StringBuilder val;
        boolean capturing = false;       // 在 <v>(数字/共享索引) 或 <t>(内联串) 内

        SheetHandler(List<String> shared, int maxRows, int maxChars) {
            this.shared = shared; this.maxRows = maxRows; this.maxChars = maxChars;
        }

        public void startElement(String u, String ln, String qn, Attributes a) {
            if (truncated) return;
            if ("row".equals(qn)) { row = new TreeMap<>(); }
            else if ("c".equals(qn)) { cellRef = a.getValue("r"); cellType = a.getValue("t"); val = new StringBuilder(); }
            else if ("v".equals(qn) || "t".equals(qn)) { capturing = true; } // 兼容共享串/数字(<v>)与内联串(<is><t>)
        }

        public void characters(char[] ch, int st, int len) { if (capturing && val != null) val.append(ch, st, len); }

        public void endElement(String u, String ln, String qn) {
            if (truncated) return;
            if ("v".equals(qn) || "t".equals(qn)) {
                capturing = false;
            } else if ("c".equals(qn)) {
                if (row != null && cellRef != null && val != null) {
                    String text;
                    if ("s".equals(cellType)) { // 共享串: <v> 是索引
                        int idx = parseInt(val.toString());
                        text = (idx >= 0 && idx < shared.size()) ? shared.get(idx) : "";
                    } else { // inlineStr(<t>) / 数字(<v>) / 其它: 直接取捕获文本
                        text = val.toString();
                    }
                    row.put(colIndex(cellRef), text);
                }
            } else if ("row".equals(qn)) {
                if (row != null) emitRow(row);
                row = null;
            }
        }

        private void emitRow(TreeMap<Integer, String> r) {
            if (rowCount >= maxRows || sb.length() >= maxChars) { truncated = true; return; }
            StringBuilder line = new StringBuilder();
            int max = r.isEmpty() ? -1 : r.lastKey();
            for (int i = 0; i <= max; i++) {
                if (i > 0) line.append(',');
                line.append(csv(r.get(i)));
            }
            sb.append(line).append('\n');
            rowCount++;
            if (sb.length() >= maxChars) truncated = true;
        }

        private static String csv(String v) {
            if (v == null || v.isEmpty()) return "";
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
                return "\"" + v.replace("\"", "\"\"") + "\"";
            }
            return v;
        }
    }

    /** 单元格引用("AB12")→ 0 基列序。 */
    private static int colIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') col = col * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') col = col * 26 + (c - 'a' + 1);
            else break;
        }
        return col - 1;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
}
