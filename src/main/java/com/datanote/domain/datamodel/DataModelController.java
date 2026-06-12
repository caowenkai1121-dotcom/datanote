package com.datanote.domain.datamodel;

import com.datanote.common.model.R;
import com.datanote.domain.datamodel.model.DnModel;
import com.datanote.domain.datamodel.model.DnModelAttribute;
import com.datanote.domain.datamodel.model.DnModelChange;
import com.datanote.domain.datamodel.model.DnModelEntity;
import com.datanote.domain.datamodel.model.DnModelRelation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据模型控制器 — 三层模型/实体/属性/关系 CRUD、申请审批流转、模型生成、DDL。
 */
@Tag(name = "数据模型")
@RestController
@RequestMapping("/api/datamodel")
@RequiredArgsConstructor
public class DataModelController {

    private final DataModelService service;

    // -------- 模型 --------

    @Operation(summary = "模型列表")
    @GetMapping("/models")
    public R<List<DnModel>> listModels(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) Long subjectId,
                                       @RequestParam(required = false) String status) {
        return R.ok(service.listModels(type, subjectId, status));
    }

    @Operation(summary = "模型详情(含实体/属性/关系)")
    @GetMapping("/model/{id}")
    public R<DnModel> getModel(@PathVariable Long id) {
        return R.ok(service.getModelDetail(id));
    }

    @Operation(summary = "新建/更新模型")
    @PostMapping("/model")
    public R<DnModel> saveModel(@RequestBody DnModel model) {
        return R.ok(service.saveModel(model));
    }

    @Operation(summary = "删除模型")
    @DeleteMapping("/model/{id}")
    public R<String> deleteModel(@PathVariable Long id) {
        service.deleteModel(id);
        return R.ok("已删除");
    }

    // -------- 实体 / 属性 / 关系 --------

    @Operation(summary = "新建/更新实体")
    @PostMapping("/entity")
    public R<DnModelEntity> saveEntity(@RequestBody DnModelEntity entity) {
        return R.ok(service.saveEntity(entity));
    }

    @Operation(summary = "删除实体")
    @DeleteMapping("/entity/{id}")
    public R<String> deleteEntity(@PathVariable Long id) {
        service.deleteEntity(id);
        return R.ok("已删除");
    }

    @Operation(summary = "覆盖式保存实体属性")
    @PostMapping("/entity/{id}/attributes")
    public R<String> saveAttributes(@PathVariable Long id, @RequestBody List<DnModelAttribute> attrs) {
        service.saveAttributes(id, attrs);
        return R.ok("已保存");
    }

    @Operation(summary = "新建/更新关系")
    @PostMapping("/relation")
    public R<DnModelRelation> saveRelation(@RequestBody DnModelRelation rel) {
        return R.ok(service.saveRelation(rel));
    }

    @Operation(summary = "删除关系")
    @DeleteMapping("/relation/{id}")
    public R<String> deleteRelation(@PathVariable Long id) {
        service.deleteRelation(id);
        return R.ok("已删除");
    }

    // -------- 流转(申请/审批) --------

    @Operation(summary = "建模规范校验(提交前预检)")
    @GetMapping("/model/{id}/validate")
    public R<Map<String, Object>> validate(@PathVariable Long id) {
        return R.ok(service.validateModel(id));
    }

    @Operation(summary = "提交模型审批")
    @PostMapping("/model/{id}/submit")
    public R<DnModelChange> submit(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return R.ok(service.submitForApproval(id, reason));
    }

    @Operation(summary = "变更工单列表")
    @GetMapping("/changes")
    public R<List<DnModelChange>> changes(@RequestParam(required = false) String status) {
        return R.ok(service.listChanges(status));
    }

    @Operation(summary = "审批通过")
    @PostMapping("/change/{id}/approve")
    public R<DnModelChange> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.review(id, "approved", body == null ? null : body.get("comment")));
    }

    @Operation(summary = "审批驳回")
    @PostMapping("/change/{id}/reject")
    public R<DnModelChange> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.review(id, "rejected", body == null ? null : body.get("comment")));
    }

    // -------- 生成 --------

    @Operation(summary = "由业务模型生成逻辑模型")
    @PostMapping("/model/{id}/generate-logical")
    public R<DnModel> generateLogical(@PathVariable Long id) {
        return R.ok(service.generateLogical(id));
    }

    @Operation(summary = "由逻辑模型生成物理模型")
    @PostMapping("/model/{id}/generate-physical")
    public R<DnModel> generatePhysical(@PathVariable Long id) {
        return R.ok(service.generatePhysical(id));
    }

    @Operation(summary = "从物理表元数据逆向生成物理模型")
    @PostMapping("/reverse")
    public R<DnModel> reverse(@RequestBody Map<String, Object> body) {
        Long tableMetaId = body.get("tableMetaId") == null ? null : Long.valueOf(String.valueOf(body.get("tableMetaId")));
        Long subjectId = body.get("subjectId") == null ? null : Long.valueOf(String.valueOf(body.get("subjectId")));
        if (tableMetaId == null) return R.fail(R.CODE_BAD_REQUEST, "请指定物理表");
        return R.ok(service.reverseFromTable(tableMetaId, subjectId));
    }

    @Operation(summary = "物理模型生成建表 DDL")
    @GetMapping("/model/{id}/ddl")
    public R<String> ddl(@PathVariable Long id) {
        return R.ok(service.generateDdl(id));
    }

    // -------- 版本历史 / 资产落地 --------

    @Operation(summary = "模型版本历史")
    @GetMapping("/model/{id}/versions")
    public R<List<com.datanote.domain.datamodel.model.DnModelVersion>> versions(@PathVariable Long id) {
        return R.ok(service.listVersions(id));
    }

    @Operation(summary = "查看某版本快照")
    @GetMapping("/version/{vid}")
    public R<com.datanote.domain.datamodel.model.DnModelVersion> version(@PathVariable Long vid) {
        return R.ok(service.getVersion(vid));
    }

    @Operation(summary = "两版本字段级差异对比")
    @GetMapping("/compare")
    public R<Map<String, Object>> compare(@RequestParam Long from, @RequestParam Long to) {
        return R.ok(service.compareVersions(from, to));
    }

    @Operation(summary = "物理模型落地为数据资产")
    @PostMapping("/model/{id}/publish-asset")
    public R<Map<String, Object>> publishAsset(@PathVariable Long id) {
        return R.ok(service.publishToAsset(id));
    }
}
