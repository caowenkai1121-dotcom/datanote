package com.datanote.domain.develop;

import com.datanote.common.model.R;
import com.datanote.domain.develop.model.DnSqlSnippet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SQL 片段库 Controller — 数据开发常用 SQL 片段沉淀与一键插入。
 */
@RestController
@RequestMapping("/api/snippet")
@RequiredArgsConstructor
@Tag(name = "SQL 片段库", description = "常用 SQL 片段保存与编辑器一键插入")
public class SqlSnippetController {

    private final SqlSnippetService snippetService;

    @Operation(summary = "片段列表(当前用户)")
    @GetMapping("/list")
    public R<List<DnSqlSnippet>> list(@RequestParam(required = false) String keyword) {
        return R.ok(snippetService.list(keyword));
    }

    @Operation(summary = "保存片段(新建/更新)")
    @PostMapping("/save")
    public R<DnSqlSnippet> save(@RequestBody DnSqlSnippet snippet) {
        return R.ok(snippetService.save(snippet));
    }

    @Operation(summary = "删除片段")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        snippetService.delete(id);
        return R.ok("已删除");
    }

    @Operation(summary = "插入计数+1")
    @PostMapping("/{id}/use")
    public R<String> use(@PathVariable Long id) {
        snippetService.incrementUse(id);
        return R.ok("ok");
    }
}
