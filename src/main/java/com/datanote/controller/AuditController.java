package com.datanote.controller;

import com.datanote.model.R;
import com.datanote.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 全局审计中心 Controller（M12）—— 检索（分页+过滤）/ CSV 导出 / 按类型/人统计 / 登录留痕。
 * 注：水印（用户/时间/IP）与下载二次校验本期不做，留待后续。
 */
@Slf4j
@RestController
@RequestMapping("/api/gov/audit")
@RequiredArgsConstructor
@Tag(name = "全局审计", description = "审计检索、导出、统计")
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "审计检索(分页+过滤:时间/类型/操作人/路径)")
    @GetMapping("/search")
    public R<Map<String, Object>> search(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String actionType,
                                         @RequestParam(required = false) String userName,
                                         @RequestParam(required = false) String path,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(auditService.search(from, to, actionType, userName, path, page, size));
    }

    @Operation(summary = "导出 CSV(同检索过滤条件)")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       @RequestParam(required = false) String actionType,
                       @RequestParam(required = false) String userName,
                       @RequestParam(required = false) String path,
                       HttpServletResponse response) throws IOException {
        String csv = auditService.exportCsv(from, to, actionType, userName, path);
        String fileName = URLEncoder.encode("audit_log.csv", "UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
        try (OutputStream os = response.getOutputStream()) {
            // 写 UTF-8 BOM，Excel 打开中文不乱码
            os.write(0xEF);
            os.write(0xBB);
            os.write(0xBF);
            os.write(csv.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    @Operation(summary = "按动作类型统计")
    @GetMapping("/stat/type")
    public R<List<Map<String, Object>>> statByType() {
        return R.ok(auditService.statByType());
    }

    @Operation(summary = "按操作人统计(Top50)")
    @GetMapping("/stat/user")
    public R<List<Map<String, Object>>> statByUser() {
        return R.ok(auditService.statByUser());
    }

    @Operation(summary = "按访问路径统计(Top20)")
    @GetMapping("/stat/path")
    public R<List<Map<String, Object>>> statByPath() {
        return R.ok(auditService.statByPath());
    }

    @Operation(summary = "近N天审计量时序")
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        return R.ok(auditService.trend(days));
    }

    @Operation(summary = "登录留痕(登录成功后由前端显式调用)")
    @PostMapping("/login-record")
    public R<String> loginRecord(HttpServletRequest request) {
        String user = "anonymous";
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                user = auth.getName();
            }
        } catch (Exception ignore) {
            // 取不到身份按匿名处理
        }
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isEmpty())
                ? (xff.indexOf(',') > 0 ? xff.substring(0, xff.indexOf(',')).trim() : xff.trim())
                : request.getRemoteAddr();
        auditService.record(user, "LOGIN", "POST", "/api/auth/login", ip, 200, "登录成功");
        return R.ok("ok");
    }
}
