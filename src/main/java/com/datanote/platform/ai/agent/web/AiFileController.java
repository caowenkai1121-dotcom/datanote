package com.datanote.platform.ai.agent.web;

import com.datanote.common.model.R;
import com.datanote.platform.ai.agent.engine.AiFileService;
import com.datanote.platform.ai.agent.engine.DocIngestService;
import com.datanote.platform.ai.agent.model.DnAiFile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AI 数据中心文件: 用户自由上传/下载/列表/删除。 */
@RestController
@RequestMapping("/api/ai/agent/files")
@RequiredArgsConstructor
public class AiFileController {

    private final AiFileService fileService;
    private final DocIngestService docIngestService;

    /** 上传(支持多文件)。 */
    @PostMapping("/upload")
    public R<List<Map<String, Object>>> upload(@RequestParam("files") MultipartFile[] files,
                                               @RequestParam(value = "sessionId", required = false) String sessionId,
                                               HttpServletRequest req) {
        if (files == null || files.length == 0) return R.fail("未选择文件");
        String owner = currentUser();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            try {
                DnAiFile m = fileService.save(f, owner, sessionId, "user");
                docIngestService.ingestAsync(m); // 文档类(pdf/docx/txt/md)异步入向量库; 非文档类内部跳过
                out.add(meta(m));
            } catch (IllegalArgumentException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("fileName", f.getOriginalFilename());
                err.put("error", e.getMessage());
                out.add(err);
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("fileName", f.getOriginalFilename());
                err.put("error", "上传失败: " + e.getMessage());
                out.add(err);
            }
        }
        return R.ok(out);
    }

    /** 文件列表(owner 作用域)。 */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (DnAiFile m : fileService.list(currentUser())) out.add(meta(m));
        return R.ok(out);
    }

    /** 下载。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id) {
        Object[] r = fileService.resolve(id, currentUser());
        if (r == null) return ResponseEntity.notFound().build();
        DnAiFile m = (DnAiFile) r[0];
        Path p = (Path) r[1];
        String ct = (m.getContentType() == null || m.getContentType().isEmpty())
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : m.getContentType();
        String fn = m.getFileName() == null ? ("file-" + id) : m.getFileName();
        String encoded;
        try { encoded = URLEncoder.encode(fn, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException e) { encoded = "file-" + id; }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ct))
                // RFC5987 filename* 兼容中文名
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .body(new FileSystemResource(p.toFile()));
    }

    /** 删除(owner 作用域)。 */
    @PostMapping("/{id}/remove")
    public R<Void> remove(@PathVariable("id") Long id) {
        boolean ok = fileService.delete(id, currentUser());
        if (ok) docIngestService.deletePoints(id); // 级联清文档向量块
        return ok ? R.ok() : R.fail("文件不存在或无权");
    }

    private Map<String, Object> meta(DnAiFile m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", m.getId());
        x.put("fileName", m.getFileName());
        x.put("size", m.getSizeBytes());
        x.put("source", m.getSource());
        x.put("createdAt", m.getCreatedAt());
        x.put("indexStatus", m.getIndexStatus());
        x.put("chunkCount", m.getChunkCount());
        return x;
    }

    private String currentUser() {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.getName() != null && !"anonymousUser".equals(a.getName())) return a.getName();
        } catch (Exception ignore) {
        }
        return "anonymous";
    }
}
