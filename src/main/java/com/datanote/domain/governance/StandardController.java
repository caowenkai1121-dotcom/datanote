package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnCodeDictItemMapper;
import com.datanote.mapper.DnCodeDictMapper;
import com.datanote.mapper.DnDataElementMapper;
import com.datanote.mapper.DnStandardCheckRunMapper;
import com.datanote.mapper.DnWordRootMapper;
import com.datanote.platform.config.model.DnCodeDict;
import com.datanote.platform.config.model.DnCodeDictItem;
import com.datanote.domain.governance.model.DnDataElement;
import com.datanote.domain.governance.model.DnStandardCheckRun;
import com.datanote.domain.governance.model.DnWordRoot;
import com.datanote.common.model.R;
import com.datanote.domain.governance.StandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据标准 Controller — 数据元 / 命名词根 / 码表 CRUD + 落标稽核。
 */
@RestController
@RequestMapping("/api/gov/standard")
@Tag(name = "数据标准", description = "数据元、命名词根、码表与落标稽核")
@RequiredArgsConstructor
public class StandardController {

    private final DnDataElementMapper elementMapper;
    private final DnWordRootMapper wordRootMapper;
    private final DnCodeDictMapper codeDictMapper;
    private final DnCodeDictItemMapper codeDictItemMapper;
    private final DnStandardCheckRunMapper checkRunMapper;
    private final StandardService standardService;

    // ========== 数据元 ==========

    @Operation(summary = "数据元列表")
    @GetMapping("/elements")
    public R<List<DnDataElement>> elements(@RequestParam(required = false) String keyword) {
        QueryWrapper<DnDataElement> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("element_code", keyword).or().like("name_cn", keyword));
        }
        qw.orderByDesc("id");
        return R.ok(elementMapper.selectList(qw));
    }

    @Operation(summary = "保存数据元")
    @PostMapping("/element/save")
    public R<DnDataElement> saveElement(@RequestBody DnDataElement e) {
        if (e.getId() != null) {
            elementMapper.updateById(e);
        } else {
            e.setCreatedAt(LocalDateTime.now());
            elementMapper.insert(e);
        }
        return R.ok(e);
    }

    @Operation(summary = "删除数据元")
    @DeleteMapping("/element/{id}")
    public R<String> deleteElement(@PathVariable Long id) {
        elementMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ========== 命名词根 ==========

    @Operation(summary = "词根列表")
    @GetMapping("/roots")
    public R<List<DnWordRoot>> roots(@RequestParam(required = false) String keyword) {
        QueryWrapper<DnWordRoot> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("word_cn", keyword).or().like("word_en", keyword).or().like("abbr", keyword));
        }
        qw.orderByDesc("id");
        return R.ok(wordRootMapper.selectList(qw));
    }

    @Operation(summary = "保存词根")
    @PostMapping("/root/save")
    public R<DnWordRoot> saveRoot(@RequestBody DnWordRoot r) {
        if (r.getId() != null) {
            wordRootMapper.updateById(r);
        } else {
            wordRootMapper.insert(r);
        }
        return R.ok(r);
    }

    @Operation(summary = "删除词根")
    @DeleteMapping("/root/{id}")
    public R<String> deleteRoot(@PathVariable Long id) {
        wordRootMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ========== 码表 ==========

    @Operation(summary = "码表列表")
    @GetMapping("/dicts")
    public R<List<DnCodeDict>> dicts(@RequestParam(required = false) String keyword) {
        QueryWrapper<DnCodeDict> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("dict_code", keyword).or().like("dict_name", keyword));
        }
        qw.orderByDesc("id");
        return R.ok(codeDictMapper.selectList(qw));
    }

    @Operation(summary = "码表详情(含明细项)")
    @GetMapping("/dict/{id}")
    public R<Map<String, Object>> dictDetail(@PathVariable Long id) {
        DnCodeDict dict = codeDictMapper.selectById(id);
        if (dict == null) {
            return R.fail("码表不存在");
        }
        QueryWrapper<DnCodeDictItem> qw = new QueryWrapper<>();
        qw.eq("dict_id", id).orderByAsc("sort").orderByAsc("id");
        Map<String, Object> result = new HashMap<>();
        result.put("dict", dict);
        result.put("items", codeDictItemMapper.selectList(qw));
        return R.ok(result);
    }

    @Operation(summary = "保存码表")
    @PostMapping("/dict/save")
    public R<DnCodeDict> saveDict(@RequestBody DnCodeDict d) {
        if (d.getId() != null) {
            codeDictMapper.updateById(d);
        } else {
            codeDictMapper.insert(d);
        }
        return R.ok(d);
    }

    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除码表(含明细项)")
    @DeleteMapping("/dict/{id}")
    public R<String> deleteDict(@PathVariable Long id) {
        codeDictMapper.deleteById(id);
        QueryWrapper<DnCodeDictItem> qw = new QueryWrapper<>();
        qw.eq("dict_id", id);
        codeDictItemMapper.delete(qw);
        return R.ok("删除成功");
    }

    @Operation(summary = "保存码表明细项")
    @PostMapping("/dict/item/save")
    public R<DnCodeDictItem> saveDictItem(@RequestBody DnCodeDictItem item) {
        if (item.getId() != null) {
            codeDictItemMapper.updateById(item);
        } else {
            codeDictItemMapper.insert(item);
        }
        return R.ok(item);
    }

    @Operation(summary = "删除码表明细项")
    @DeleteMapping("/dict/item/{id}")
    public R<String> deleteDictItem(@PathVariable Long id) {
        codeDictItemMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ========== 落标稽核 ==========

    @Operation(summary = "执行落标稽核")
    @PostMapping("/check/run")
    public R<DnStandardCheckRun> runCheck(@RequestParam(required = false) String scope) {
        return R.ok(standardService.runCheck(scope));
    }

    @Operation(summary = "稽核历史")
    @GetMapping("/check/runs")
    public R<List<DnStandardCheckRun>> checkRuns() {
        return R.ok(standardService.recentRuns(20));
    }

    @Operation(summary = "规范违规Top库表(最近一次稽核)")
    @GetMapping("/top-violations")
    public R<List<java.util.Map<String, Object>>> topViolations(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(standardService.topViolations(limit));
    }

    @Operation(summary = "稽核详情(含不合规清单)")
    @GetMapping("/check/run/{id}")
    public R<DnStandardCheckRun> checkRun(@PathVariable Long id) {
        DnStandardCheckRun run = checkRunMapper.selectById(id);
        if (run == null) {
            return R.fail("稽核记录不存在");
        }
        return R.ok(run);
    }
}
