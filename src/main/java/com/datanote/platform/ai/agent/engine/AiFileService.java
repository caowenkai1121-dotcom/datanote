package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiFileMapper;
import com.datanote.platform.ai.agent.model.DnAiFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * AI 数据中心文件服务: 用户上传/下载/列表/删除。
 * 安全: 扩展名白名单 + 大小上限 + 磁盘存 UUID(不用用户文件名作路径, 防穿越) + 下载路径校验在存储根内 + owner 作用域。
 * 文件仅存储与回传, 不执行, 无执行风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFileService {

    private final DnAiFileMapper fileMapper;

    @Value("${datanote.ai.file-dir:ai-files}")
    private String fileDir;

    /** 允许的扩展名(无可执行类型) */
    private static final Set<String> ALLOWED = new HashSet<>(java.util.Arrays.asList(
            "xlsx", "xls", "csv", "pdf", "txt", "json", "docx", "doc", "md", "png", "jpg", "jpeg", "gif"));
    private static final long MAX_BYTES = 20L * 1024 * 1024; // 20MB

    private Path storageRoot() throws IOException {
        Path root = Paths.get(fileDir).toAbsolutePath().normalize();
        if (!Files.exists(root)) Files.createDirectories(root);
        return root;
    }

    /** 保存上传文件; 返回元数据。校验失败抛 IllegalArgumentException。 */
    public DnAiFile save(MultipartFile file, String owner, String sessionId, String source) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件超过 20MB 上限");
        String original = sanitizeName(file.getOriginalFilename());
        String ext = ext(original);
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + (ext.isEmpty() ? "(无扩展名)" : ext) + " (允许 " + String.join("/", ALLOWED) + ")");
        }
        Path root = storageRoot();
        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法路径"); // 双保险
        Files.write(target, file.getBytes());

        DnAiFile m = new DnAiFile();
        m.setFileName(original);
        m.setStoredName(stored);
        m.setContentType(file.getContentType());
        m.setSizeBytes(file.getSize());
        m.setOwner(owner);
        m.setSource(source == null ? "user" : source);
        m.setSessionId(sessionId);
        m.setCreatedAt(LocalDateTime.now());
        fileMapper.insert(m);
        log.info("[file] 上传 id={} name={} size={} owner={}", m.getId(), original, file.getSize(), owner);
        return m;
    }

    /** 保存 agent 生成的文本内容为可下载文件(export_file 用)。校验失败抛 IllegalArgumentException。 */
    public DnAiFile saveContent(String fileName, byte[] bytes, String contentType, String owner, String sessionId, String source) throws IOException {
        if (bytes == null) bytes = new byte[0];
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("内容超过 20MB 上限");
        String original = sanitizeName(fileName);
        String ext = ext(original);
        if (ext.isEmpty()) { original = original + ".txt"; ext = "txt"; }
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException("不支持的导出类型: " + ext + " (允许 " + String.join("/", ALLOWED) + ")");
        Path root = storageRoot();
        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法路径");
        Files.write(target, bytes);

        DnAiFile m = new DnAiFile();
        m.setFileName(original);
        m.setStoredName(stored);
        m.setContentType(contentType);
        m.setSizeBytes((long) bytes.length);
        m.setOwner(owner);
        m.setSource(source == null ? "agent" : source);
        m.setSessionId(sessionId);
        m.setCreatedAt(LocalDateTime.now());
        fileMapper.insert(m);
        log.info("[file] agent导出 id={} name={} size={} owner={}", m.getId(), original, bytes.length, owner);
        return m;
    }

    /** 列表(owner 作用域, 匿名看全部)。 */
    public List<DnAiFile> list(String owner) {
        QueryWrapper<DnAiFile> qw = new QueryWrapper<>();
        if (owner != null && !"anonymous".equals(owner)) qw.and(w -> w.eq("owner", owner).or().isNull("owner"));
        qw.orderByDesc("id").last("LIMIT 200");
        return fileMapper.selectList(qw);
    }

    /** 取文件元 + 磁盘 Path(owner 校验, 路径在根内); 不存在/越权返 null。 */
    public Object[] resolve(Long id, String owner) {
        DnAiFile m = fileMapper.selectById(id);
        if (m == null) return null;
        if (ownerDenied(owner, m.getOwner())) return null; // 越权
        try {
            Path root = storageRoot();
            Path p = root.resolve(m.getStoredName()).normalize();
            if (!p.startsWith(root) || !Files.exists(p)) return null;
            return new Object[]{m, p};
        } catch (IOException e) {
            return null;
        }
    }

    private static final Set<String> TEXT_EXT = new HashSet<>(java.util.Arrays.asList("csv", "txt", "json", "md", "xml", "log"));

    /** 读文本文件内容(file_read 工具用, owner 校验); 二进制返提示。返回 [fileName, contentOrNote, isText]。null=不存在/越权。 */
    public Object[] readText(Long id, String owner, int maxChars) {
        Object[] r = resolve(id, owner);
        if (r == null) return null;
        DnAiFile m = (DnAiFile) r[0];
        Path p = (Path) r[1];
        String ext = ext(m.getFileName());
        if ("xlsx".equals(ext)) {
            // 零依赖提取 xlsx 首表为 CSV(供分析)
            try {
                String csv = XlsxTextExtractor.extract(p, 200, maxChars);
                return new Object[]{m.getFileName(), "(Excel首表, CSV化)\n" + csv, true};
            } catch (Exception e) {
                return new Object[]{m.getFileName(), "(xlsx 解析失败: " + e.getMessage() + ")", false};
            }
        }
        if ("pdf".equals(ext)) {
            try {
                String t = PdfTextExtractor.extract(p, 100, maxChars);
                return new Object[]{m.getFileName(), "(PDF 文本)\n" + t, true};
            } catch (Exception e) {
                return new Object[]{m.getFileName(), "(pdf 解析失败: " + e.getMessage() + ")", false};
            }
        }
        if ("docx".equals(ext)) {
            try {
                String t = DocxTextExtractor.extract(p, maxChars);
                return new Object[]{m.getFileName(), "(Word 文本)\n" + t, true};
            } catch (Exception e) {
                return new Object[]{m.getFileName(), "(docx 解析失败: " + e.getMessage() + ")", false};
            }
        }
        if (!TEXT_EXT.contains(ext)) {
            return new Object[]{m.getFileName(), "(二进制文件 " + ext + ", 暂不支持直接读取内容; 可读 csv/txt/json/md/xml/log/xlsx/pdf/docx)", false};
        }
        try {
            byte[] b = Files.readAllBytes(p);
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            if (s.length() > maxChars) s = s.substring(0, maxChars) + "…(已截断, 完整请下载)";
            return new Object[]{m.getFileName(), s, true};
        } catch (IOException e) {
            return new Object[]{m.getFileName(), "(读取失败: " + e.getMessage() + ")", false};
        }
    }

    /** 删除(owner 校验)。 */
    public boolean delete(Long id, String owner) {
        Object[] r = resolve(id, owner);
        if (r == null) {
            // 元数据在但文件已丢: 仍允许删元数据(owner 校验)
            DnAiFile m = fileMapper.selectById(id);
            if (m == null) return false;
            if (ownerDenied(owner, m.getOwner())) return false;
            fileMapper.deleteById(id);
            return true;
        }
        DnAiFile m = (DnAiFile) r[0];
        Path p = (Path) r[1];
        try { Files.deleteIfExists(p); } catch (IOException ignore) {}
        fileMapper.deleteById(m.getId());
        return true;
    }

    /** owner 访问许可(供文档索引/检索复用同一文件 ACL 语义, 单一事实来源): !ownerDenied。 */
    public static boolean ownerCanAccess(String caller, String fileOwner) {
        return !ownerDenied(caller, fileOwner);
    }

    /** owner 越权判定: 实名用户访问他人文件即拒。超管放行; 调用者匿名 或 文件无主/开放态(anonymous)放行。 */
    private static boolean ownerDenied(String owner, String fileOwner) {
        if (owner == null || "anonymous".equals(owner) || "admin".equals(owner)) return false;
        if (fileOwner == null || "anonymous".equals(fileOwner)) return false;
        return !owner.equals(fileOwner);
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** 清洗显示文件名: 去路径分隔与控制字符, 限长。 */
    private static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "未命名";
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\x00-\\x1f]", "").trim();
        if (n.isEmpty()) n = "未命名";
        return n.length() > 480 ? n.substring(0, 480) : n;
    }
}
