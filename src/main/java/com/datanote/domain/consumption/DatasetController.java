package com.datanote.domain.consumption;

import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.consumption.model.DnDataset;
import com.datanote.platform.iam.CurrentUserUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据集/数据产品 Controller —— 注册精选 SQL 为可复用、受治理(脱敏+审计)的查询。
 */
@RestController
@RequestMapping("/api/consumption/dataset")
@Tag(name = "数据集/数据产品", description = "精选SQL注册为可复用受治理查询")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    @Operation(summary = "数据集列表")
    @GetMapping("/list")
    public R<List<DnDataset>> list(@RequestParam(required = false) String keyword) {
        return R.ok(datasetService.list(keyword));
    }

    @Operation(summary = "数据集详情")
    @GetMapping("/{id}")
    public R<DnDataset> get(@PathVariable Long id) {
        return R.ok(datasetService.get(id));
    }

    @Operation(summary = "保存数据集")
    @PostMapping("/save")
    public R<DnDataset> save(@RequestBody DnDataset ds) {
        try {
            return R.ok(datasetService.save(ds));
        } catch (BusinessException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除数据集")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        datasetService.delete(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "执行数据集查询(脱敏+审计)")
    @PostMapping("/{id}/query")
    public R<Map<String, Object>> query(@PathVariable Long id, @RequestParam(required = false) String consumer) {
        try {
            return R.ok(datasetService.query(id, CurrentUserUtil.currentUser()));
        } catch (BusinessException e) {
            return R.fail(e.getMessage());
        }
    }
}
