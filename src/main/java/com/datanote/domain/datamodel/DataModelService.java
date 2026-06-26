package com.datanote.domain.datamodel;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.mapper.*;
import com.datanote.domain.datamodel.model.*;
import com.datanote.platform.iam.CurrentUserUtil;
import com.datanote.platform.iam.DataAclService;
import com.datanote.platform.notify.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据模型服务 — 三层模型(业务/逻辑/物理) CRUD、实体/属性/关系维护、
 * 申请审批流转、逻辑→物理模型生成、DDL 生成、建模规范校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataModelService {

    private final DnModelMapper modelMapper;
    private final DnModelEntityMapper entityMapper;
    private final DnModelAttributeMapper attrMapper;
    private final DnModelRelationMapper relMapper;
    private final DnModelChangeMapper changeMapper;
    private final NotificationService notificationService;
    private final com.datanote.domain.metadata.mapper.DnTableMetaMapper tableMetaMapper;
    private final com.datanote.domain.metadata.mapper.DnColumnMetaMapper columnMetaMapper;
    private final com.datanote.platform.collab.EditLockService editLockService;   // 并发编辑防护: 模型级编辑锁
    private final DataAclService dataAclService;

    /** 模型级编辑锁断言: 他人持锁则拒(服务端兜底, 防绕过前端)。 */
    private void assertModelHeld(Long modelId) {
        if (modelId != null) editLockService.assertHeld("MODEL", String.valueOf(modelId));
    }

    private void requireModelAccess(Long modelId) {
        if (modelId != null && !dataAclService.canAccess("MODEL", String.valueOf(modelId))) {
            throw new BusinessException("无权访问该模型");
        }
    }

    private boolean canAccessModel(Long modelId) {
        return modelId == null || dataAclService.canAccess("MODEL", String.valueOf(modelId));
    }

    private void requireTableAccess(com.datanote.domain.metadata.model.DnTableMeta table) {
        if (table == null) return;
        String db = table.getDatabaseName() == null ? "" : table.getDatabaseName().trim();
        String name = table.getTableName() == null ? "" : table.getTableName().trim();
        if (!dataAclService.canAccess("TABLE", db + "." + name)) {
            throw new BusinessException("无权访问该表");
        }
    }
    private final DnModelVersionMapper versionMapper;
    private final com.datanote.domain.governance.mapper.DnDataElementMapper dataElementMapper;
    private final com.datanote.domain.governance.mapper.DnWordRootMapper wordRootMapper;
    private final com.datanote.domain.governance.mapper.DnQualityRuleMapper qualityRuleMapper;

    // ---------------- 模型 ----------------

    /** 模型列表(可按类型/主题域/状态过滤)，附实体数。 */
    public List<DnModel> listModels(String modelType, Long subjectId, String status) {
        QueryWrapper<DnModel> qw = new QueryWrapper<>();
        if (modelType != null && !modelType.isEmpty()) qw.eq("model_type", modelType);
        if (subjectId != null) qw.eq("subject_id", subjectId);
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("updated_at");
        List<DnModel> list = modelMapper.selectList(qw);
        if (list == null) return new ArrayList<>();
        java.util.Set<String> denied = dataAclService.deniedIds("MODEL");
        if (denied != null && !denied.isEmpty()) {
            list = new ArrayList<>(list);
            list.removeIf(m -> m == null || (m.getId() != null && denied.contains(String.valueOf(m.getId()))));
        }
        if (list.isEmpty()) return list;
        List<Long> ids = list.stream().map(DnModel::getId).collect(Collectors.toList());
        // 批量实体数: 一条 group by 聚合替代逐模型 selectCount(消除 N+1)
        Map<Long, Integer> countMap = new HashMap<>();
        for (Map<String, Object> row : entityMapper.selectMaps(new QueryWrapper<DnModelEntity>()
                .select("model_id", "count(*) cnt").in("model_id", ids).groupBy("model_id"))) {
            Object mid = row.get("model_id"), cnt = row.get("cnt");
            if (mid != null) countMap.put(((Number) mid).longValue(), cnt == null ? 0 : ((Number) cnt).intValue());
        }
        // 批量溯源模型名: 一次 selectBatchIds 替代逐模型 selectById
        List<Long> srcIds = list.stream().map(DnModel::getSourceModelId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, String> srcNameMap = new HashMap<>();
        if (!srcIds.isEmpty()) {
            List<DnModel> _srcs = modelMapper.selectBatchIds(srcIds);
            if (_srcs != null) for (DnModel src : _srcs) srcNameMap.put(src.getId(), src.getModelName()); // selectList 理论可返回 null
        }
        for (DnModel m : list) {
            m.setEntityCount(countMap.getOrDefault(m.getId(), 0));
            if (m.getSourceModelId() != null) m.setSourceModelName(srcNameMap.get(m.getSourceModelId()));
        }
        return list;
    }

    /** 模型详情: 含实体(每实体含属性)与关系。 */
    public DnModel getModelDetail(Long id) {
        requireModelAccess(id);
        DnModel m = modelMapper.selectById(id);
        if (m == null) throw new BusinessException("模型不存在");
        List<DnModelEntity> entities = entityMapper.selectList(
                new QueryWrapper<DnModelEntity>().eq("model_id", id).orderByAsc("sort_order", "id"));
        for (DnModelEntity e : entities) {
            e.setAttributes(attrMapper.selectList(
                    new QueryWrapper<DnModelAttribute>().eq("entity_id", e.getId()).orderByAsc("sort_order", "id")));
        }
        m.setEntities(entities);
        m.setRelations(relMapper.selectList(new QueryWrapper<DnModelRelation>().eq("model_id", id)));
        return m;
    }

    /** 新建/更新模型。编码唯一校验、命名规范校验。 */
    public DnModel saveModel(DnModel model) {
        if (model != null && model.getId() != null) requireModelAccess(model.getId());
        validateCode(model.getModelCode(), "模型编码");
        if (model.getModelName() == null || model.getModelName().trim().isEmpty()) {
            throw new BusinessException("模型名称不能为空");
        }
        if (model.getModelType() == null || model.getModelType().trim().isEmpty()) {
            throw new BusinessException("模型类型(业务/逻辑/物理)不能为空");
        }
        // 编码唯一(排除自身)
        QueryWrapper<DnModel> dup = new QueryWrapper<DnModel>().eq("model_code", model.getModelCode());
        if (model.getId() != null) dup.ne("id", model.getId());
        if (modelMapper.selectCount(dup) > 0) throw new BusinessException("模型编码已存在: " + model.getModelCode());

        if (model.getId() == null) {
            model.setStatus("DRAFT");
            model.setVersion(1);
            model.setCreatedBy(CurrentUserUtil.currentUser());
            if (model.getOwner() == null || model.getOwner().isEmpty()) model.setOwner(CurrentUserUtil.currentUser());
            modelMapper.insert(model);
        } else {
            DnModel exist = modelMapper.selectById(model.getId());
            if (exist == null) throw new BusinessException("模型不存在");
            if ("PENDING".equals(exist.getStatus())) {
                throw new BusinessException("模型审批中, 不可修改, 请先等待审批完成或驳回后再改");
            }
            // 并发编辑防护: 他人持锁拒 + 乐观版本校验
            assertModelHeld(model.getId());
            if (model.getBaseUpdatedAt() != null && exist.getUpdatedAt() != null
                    && !model.getBaseUpdatedAt().equals(exist.getUpdatedAt())) {
                throw new BusinessException("该模型已被他人修改, 请刷新后重试以免覆盖对方改动");
            }
            // 状态/版本由流转管控, 此处不允许直改
            model.setStatus(null);
            model.setVersion(null);
            model.setCreatedBy(null);
            model.setCreatedAt(null);
            modelMapper.updateById(model);
        }
        return modelMapper.selectById(model.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long id) {
        requireModelAccess(id);
        DnModel m = modelMapper.selectById(id);
        if (m == null) return;
        // 级联删实体/属性/关系/变更工单
        List<DnModelEntity> entities = entityMapper.selectList(new QueryWrapper<DnModelEntity>().eq("model_id", id));
        for (DnModelEntity e : entities) {
            attrMapper.delete(new QueryWrapper<DnModelAttribute>().eq("entity_id", e.getId()));
        }
        entityMapper.delete(new QueryWrapper<DnModelEntity>().eq("model_id", id));
        relMapper.delete(new QueryWrapper<DnModelRelation>().eq("model_id", id));
        changeMapper.delete(new QueryWrapper<DnModelChange>().eq("model_id", id));
        versionMapper.delete(new QueryWrapper<DnModelVersion>().eq("model_id", id));
        modelMapper.deleteById(id);
    }

    // ---------------- 实体 / 属性 / 关系 ----------------

    /** 模型可编辑校验: 审批中(PENDING)的模型禁止任何增改删, 防审批闭环被旁路。 */
    private void assertModelEditable(Long modelId) {
        if (modelId == null) return;
        requireModelAccess(modelId);
        assertModelHeld(modelId);   // 并发编辑防护: 他人持模型编辑锁则拒(覆盖实体/属性/关系所有子改动)
        DnModel m = modelMapper.selectById(modelId);
        if (m != null && "PENDING".equals(m.getStatus())) {
            throw new BusinessException("模型审批中, 不可修改, 请先等待审批完成或驳回后再改");
        }
    }

    public DnModelEntity saveEntity(DnModelEntity entity) {
        if (entity.getModelId() == null) throw new BusinessException("实体须归属模型");
        assertModelEditable(entity.getModelId());
        validateCode(entity.getEntityCode(), "实体编码");
        if (entity.getId() == null) {
            entityMapper.insert(entity);
        } else {
            entityMapper.updateById(entity);
        }
        return entityMapper.selectById(entity.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteEntity(Long id) {
        DnModelEntity e = entityMapper.selectById(id);
        if (e != null) assertModelEditable(e.getModelId());
        attrMapper.delete(new QueryWrapper<DnModelAttribute>().eq("entity_id", id));
        entityMapper.deleteById(id);
    }

    /** 覆盖式保存某实体的全部属性(先删后插)。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveAttributes(Long entityId, List<DnModelAttribute> attrs) {
        if (entityId == null) throw new BusinessException("属性须归属实体");
        DnModelEntity e = entityMapper.selectById(entityId);
        if (e != null) assertModelEditable(e.getModelId());
        attrMapper.delete(new QueryWrapper<DnModelAttribute>().eq("entity_id", entityId));
        if (attrs == null) return;
        int order = 0;
        for (DnModelAttribute a : attrs) {
            if (a.getAttrCode() == null || a.getAttrCode().trim().isEmpty()) continue;
            a.setId(null);
            a.setEntityId(entityId);
            a.setSortOrder(order++);
            attrMapper.insert(a);
        }
    }

    public DnModelRelation saveRelation(DnModelRelation rel) {
        if (rel.getModelId() == null || rel.getSourceEntityId() == null || rel.getTargetEntityId() == null) {
            throw new BusinessException("关系须指定模型与源/目标实体");
        }
        assertModelEditable(rel.getModelId());
        if (rel.getId() == null) relMapper.insert(rel); else relMapper.updateById(rel);
        return relMapper.selectById(rel.getId());
    }

    public void deleteRelation(Long id) {
        DnModelRelation rel = relMapper.selectById(id);
        if (rel != null) assertModelEditable(rel.getModelId());
        relMapper.deleteById(id);
    }

    // ---------------- 申请 / 审批 流转 ----------------

    /**
     * 建模规范校验。errors 阻断提交, warnings 仅提示。
     * 规则: 至少1实体; 每实体至少1属性; 逻辑/物理实体须有主键; 属性须有类型;
     *      逻辑模型属性建议绑数据标准(warning)。
     */
    public java.util.Map<String, Object> validateModel(Long modelId) {
        requireModelAccess(modelId);
        DnModel m = modelMapper.selectById(modelId);
        if (m == null) throw new BusinessException("模型不存在");
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<DnModelEntity> entities = entityMapper.selectList(
                new QueryWrapper<DnModelEntity>().eq("model_id", modelId).orderByAsc("sort_order", "id"));
        if (entities == null) entities = new ArrayList<>();
        if (entities.isEmpty()) {
            errors.add("模型须至少包含 1 个实体");
        }
        boolean needPk = !"BIZ".equals(m.getModelType());   // 业务模型(概念)不强制主键
        for (DnModelEntity e : entities) {
            String en = e.getEntityName() == null ? e.getEntityCode() : e.getEntityName();
            List<DnModelAttribute> attrs = attrMapper.selectList(
                    new QueryWrapper<DnModelAttribute>().eq("entity_id", e.getId()));
            if (attrs.isEmpty()) { errors.add("实体「" + en + "」无任何属性"); continue; }
            boolean hasPk = false;
            for (DnModelAttribute a : attrs) {
                if (a.getIsPk() != null && a.getIsPk() == 1) hasPk = true;
                if (a.getDataType() == null || a.getDataType().trim().isEmpty()) {
                    errors.add("属性「" + en + "." + a.getAttrCode() + "」缺少数据类型");
                }
                if ("LOGIC".equals(m.getModelType()) && (a.getElementCode() == null || a.getElementCode().trim().isEmpty())) {
                    warnings.add("逻辑属性「" + en + "." + a.getAttrCode() + "」未绑定数据标准(建议绑定以统一口径)");
                }
                // 绑标准: 类型一致性强校验(不一致→error 阻断, 防"绑了标准却写错类型")
                if (a.getElementCode() != null && !a.getElementCode().trim().isEmpty()) {
                    com.datanote.domain.governance.model.DnDataElement de = dataElementMapper.selectOne(
                            new QueryWrapper<com.datanote.domain.governance.model.DnDataElement>().eq("element_code", a.getElementCode().trim()).last("limit 1"));
                    if (de != null && de.getDataType() != null && a.getDataType() != null
                            && !de.getDataType().trim().equalsIgnoreCase(a.getDataType().trim())) {
                        errors.add("属性「" + en + "." + a.getAttrCode() + "」绑定标准[" + a.getElementCode() + "]要求类型 " + de.getDataType() + ", 实际填 " + a.getDataType() + ", 不一致");
                    }
                }
                // 命名词根校验
                String nm = checkNaming(a.getAttrCode());
                if (nm != null) warnings.add("属性「" + en + "." + a.getAttrCode() + "」" + nm);
            }
            if (needPk && !hasPk) errors.add("实体「" + en + "」缺少主键(至少一个主键属性)");
        }
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("valid", errors.isEmpty());
        r.put("errors", errors);
        r.put("warnings", warnings);
        return r;
    }

    /** 提交模型审批: DRAFT/REJECTED → PENDING, 建变更工单(快照)。提交前强校验规范。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModelChange submitForApproval(Long modelId, String reason) {
        requireModelAccess(modelId);
        DnModel m = modelMapper.selectById(modelId);
        if (m == null) throw new BusinessException("模型不存在");
        if ("PENDING".equals(m.getStatus())) throw new BusinessException("模型已在审批中, 请勿重复提交");
        // 已发布模型允许变更后重新提交(迭代新版本), 不阻断
        // 规范强校验: 有 error 阻断提交
        java.util.Map<String, Object> v = validateModel(modelId);
        @SuppressWarnings("unchecked")
        List<String> errs = (List<String>) v.get("errors");
        if (errs != null && !errs.isEmpty()) {
            throw new BusinessException("模型未通过规范校验, 请先修正: " + String.join("; ", errs));
        }

        String user = CurrentUserUtil.currentUser();
        DnModelChange c = new DnModelChange();
        c.setModelId(modelId);
        c.setChangeType(m.getVersion() != null && m.getVersion() > 1 ? "UPDATE" : "CREATE");
        c.setPayloadJson(JSON.toJSONString(getModelDetail(modelId)));
        c.setReason(reason);
        c.setStatus("pending");
        c.setRequestedBy(user);
        c.setCreatedAt(LocalDateTime.now());
        changeMapper.insert(c);

        m.setStatus("PENDING");
        modelMapper.updateById(m);
        return c;
    }

    /** 审批(approved/rejected)。禁自批(admin 例外), 通过→PUBLISHED+版本+1, 驳回→REJECTED。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModelChange review(Long changeId, String target, String comment) {
        DnModelChange c = changeMapper.selectById(changeId);
        if (c == null) throw new BusinessException("变更工单不存在");
        if (!"pending".equals(c.getStatus())) throw new BusinessException("该工单已审批, 不能重复操作");

        requireModelAccess(c.getModelId());
        String reviewer = CurrentUserUtil.currentUser();
        // 允许自审自批: 有审批权限即可审批自己提交的申请(权限由 PermInterceptor 把关)。

        boolean approved = "approved".equals(target);
        // 驳回须有原因(便于申请人修正, 与前端必填一致)
        if (!approved && (comment == null || comment.trim().isEmpty())) {
            throw new BusinessException("驳回必须填写原因");
        }
        // 原子化抢占工单: 仅当工单仍为 pending 才更新, 防并发审批重复发布/双增版本号
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnModelChange> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnModelChange>()
                        .eq("id", changeId).eq("status", "pending")
                        .set("status", approved ? "approved" : "rejected")
                        .set("reviewer", reviewer)
                        .set("review_comment", comment)
                        .set("decided_at", LocalDateTime.now());
        if (changeMapper.update(null, uw) == 0) {
            throw new BusinessException("该工单已审批, 不能重复操作");
        }
        c.setStatus(approved ? "approved" : "rejected");
        c.setReviewer(reviewer);
        c.setReviewComment(comment);
        c.setDecidedAt(LocalDateTime.now());

        DnModel m = modelMapper.selectById(c.getModelId());
        if (m != null) {
            if (approved) {
                m.setStatus("PUBLISHED");
                m.setVersion((m.getVersion() == null ? 1 : m.getVersion()) + 1);
            } else {
                m.setStatus("REJECTED");
            }
            modelMapper.updateById(m);
            // 发布即存版本快照(可追溯/对比)
            if (approved) {
                DnModelVersion ver = new DnModelVersion();
                ver.setModelId(m.getId());
                ver.setVersion(m.getVersion());
                // 发布快照取审批工单提交时冻结的 payloadJson(审批所见即所发), 而非实时DB状态
                String snapshot = (c.getPayloadJson() != null && !c.getPayloadJson().trim().isEmpty())
                        ? c.getPayloadJson() : JSON.toJSONString(getModelDetail(m.getId()));
                ver.setSnapshotJson(snapshot);
                ver.setChangeSummary(c.getReason());
                ver.setPublishedBy(reviewer);
                ver.setPublishedAt(LocalDateTime.now());
                versionMapper.insert(ver);
            }
        }
        // 通知申请人
        try {
            if (c.getRequestedBy() != null && !c.getRequestedBy().trim().isEmpty()) {
                String verdict = approved ? "已发布" : "已驳回";
                notificationService.notify(c.getRequestedBy().trim(), "MODEL_REVIEW",
                        "数据模型审批" + verdict + ": " + (m != null ? m.getModelName() : "#" + c.getModelId())
                                + " (审批人 " + reviewer + ")", "datamodel", c.getModelId(), null);
            }
        } catch (Exception e) {
            log.warn("模型审批通知失败", e);
        }
        return c;
    }

    /** 变更工单列表(可按状态过滤)。 */
    public List<DnModelChange> listChanges(String status) {
        QueryWrapper<DnModelChange> qw = new QueryWrapper<>();
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("created_at");
        List<DnModelChange> list = changeMapper.selectList(qw);
        if (list == null) return new ArrayList<>();
        java.util.Set<String> denied = dataAclService.deniedIds("MODEL");
        if (denied != null && !denied.isEmpty() && list != null) {
            list = new ArrayList<>(list);
            list.removeIf(c -> c == null || (c.getModelId() != null && denied.contains(String.valueOf(c.getModelId()))));
        }
        if (list.isEmpty()) return list;
        // 批量模型名/编码: 一次 selectBatchIds 替代逐工单 selectById(消除 N+1)
        List<Long> mids = list.stream().map(DnModelChange::getModelId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, DnModel> modelMap = new HashMap<>();
        if (!mids.isEmpty()) {
            List<DnModel> _ms = modelMapper.selectBatchIds(mids);
            if (_ms != null) for (DnModel m : _ms) modelMap.put(m.getId(), m); // selectList 理论可返回 null
        }
        for (DnModelChange c : list) {
            DnModel m = modelMap.get(c.getModelId());
            if (m != null) { c.setModelName(m.getModelName()); c.setModelCode(m.getModelCode()); }
        }
        return list;
    }

    // ---------------- 模型生成 ----------------

    /** 由逻辑模型生成物理模型(实体→表、属性→字段、类型映射, 溯源回填)。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModel generatePhysical(Long logicalModelId) {
        requireModelAccess(logicalModelId);
        DnModel logical = modelMapper.selectById(logicalModelId);
        if (logical == null) throw new BusinessException("源逻辑模型不存在");
        if (!"LOGIC".equals(logical.getModelType())) throw new BusinessException("仅逻辑模型可生成物理模型");

        DnModel phys = new DnModel();
        phys.setModelCode(uniqueCode(logical.getModelCode() + "_PHY"));
        phys.setModelName(logical.getModelName() + " (物理)");
        phys.setModelType("PHYS");
        phys.setSubjectId(logical.getSubjectId());
        phys.setSourceModelId(logical.getId());
        phys.setDwLayer(logical.getDwLayer());
        phys.setVersion(1);
        phys.setStatus("DRAFT");
        phys.setOwner(CurrentUserUtil.currentUser());
        phys.setCreatedBy(CurrentUserUtil.currentUser());
        phys.setDescription("由逻辑模型「" + logical.getModelName() + "」自动生成");
        modelMapper.insert(phys);

        List<DnModelEntity> logicEntities = entityMapper.selectList(
                new QueryWrapper<DnModelEntity>().eq("model_id", logicalModelId).orderByAsc("sort_order", "id"));
        for (DnModelEntity le : logicEntities) {
            DnModelEntity pe = new DnModelEntity();
            pe.setModelId(phys.getId());
            pe.setEntityCode(le.getEntityCode());
            pe.setEntityName(le.getEntityName());
            pe.setLevel(le.getLevel());
            pe.setSourceEntityId(le.getId());
            pe.setPhysicalTable(toSnake(le.getEntityCode()));
            pe.setBizDefinition(le.getBizDefinition());
            pe.setSortOrder(le.getSortOrder());
            entityMapper.insert(pe);
            // 属性→字段
            List<DnModelAttribute> attrs = attrMapper.selectList(
                    new QueryWrapper<DnModelAttribute>().eq("entity_id", le.getId()).orderByAsc("sort_order", "id"));
            for (DnModelAttribute la : attrs) {
                DnModelAttribute pa = new DnModelAttribute();
                pa.setEntityId(pe.getId());
                pa.setAttrCode(la.getAttrCode());
                pa.setAttrName(la.getAttrName());
                pa.setDataType(la.getDataType());
                pa.setDataLength(la.getDataLength());
                pa.setIsPk(la.getIsPk());
                pa.setIsNullable(la.getIsNullable());
                pa.setDefaultValue(la.getDefaultValue());
                pa.setElementCode(la.getElementCode());
                pa.setDictCode(la.getDictCode());
                pa.setPhysicalColumn(toSnake(la.getAttrCode()));
                pa.setBizDefinition(la.getBizDefinition());
                pa.setSortOrder(la.getSortOrder());
                attrMapper.insert(pa);
            }
        }
        return phys;
    }

    /** 由业务模型生成逻辑模型(业务对象 L3→逻辑实体 L4, 复制属性, 溯源回填)。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModel generateLogical(Long bizModelId) {
        requireModelAccess(bizModelId);
        DnModel biz = modelMapper.selectById(bizModelId);
        if (biz == null) throw new BusinessException("源业务模型不存在");
        if (!"BIZ".equals(biz.getModelType())) throw new BusinessException("仅业务模型可生成逻辑模型");

        DnModel logic = new DnModel();
        logic.setModelCode(uniqueCode(biz.getModelCode() + "_LM"));
        logic.setModelName(biz.getModelName() + " (逻辑)");
        logic.setModelType("LOGIC");
        logic.setSubjectId(biz.getSubjectId());
        logic.setSourceModelId(biz.getId());
        logic.setDwLayer(biz.getDwLayer());
        logic.setVersion(1);
        logic.setStatus("DRAFT");
        logic.setOwner(CurrentUserUtil.currentUser());
        logic.setCreatedBy(CurrentUserUtil.currentUser());
        logic.setDescription("由业务模型「" + biz.getModelName() + "」自动生成");
        modelMapper.insert(logic);

        List<DnModelEntity> bizEntities = entityMapper.selectList(
                new QueryWrapper<DnModelEntity>().eq("model_id", bizModelId).orderByAsc("sort_order", "id"));
        for (DnModelEntity be : bizEntities) {
            DnModelEntity le = new DnModelEntity();
            le.setModelId(logic.getId());
            le.setEntityCode(be.getEntityCode());
            le.setEntityName(be.getEntityName());
            le.setLevel(4);   // 业务对象 L3 → 逻辑实体 L4
            le.setSourceEntityId(be.getId());
            le.setBizDefinition(be.getBizDefinition());
            le.setSortOrder(be.getSortOrder());
            entityMapper.insert(le);
            List<DnModelAttribute> attrs = attrMapper.selectList(
                    new QueryWrapper<DnModelAttribute>().eq("entity_id", be.getId()).orderByAsc("sort_order", "id"));
            for (DnModelAttribute ba : attrs) {
                DnModelAttribute la = new DnModelAttribute();
                la.setEntityId(le.getId());
                la.setAttrCode(ba.getAttrCode());
                la.setAttrName(ba.getAttrName());
                la.setDataType(ba.getDataType());
                la.setDataLength(ba.getDataLength());
                la.setIsPk(ba.getIsPk());
                la.setIsNullable(ba.getIsNullable());
                la.setDefaultValue(ba.getDefaultValue());
                la.setElementCode(ba.getElementCode());
                la.setDictCode(ba.getDictCode());
                la.setBizDefinition(ba.getBizDefinition());
                la.setSortOrder(ba.getSortOrder());
                attrMapper.insert(la);
            }
        }
        return logic;
    }

    /** 从已采集的物理表元数据逆向生成物理模型(表→实体, 列→属性, 类型反归一化, 主键/可空识别)。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModel reverseFromTable(Long tableMetaId, Long subjectId) {
        com.datanote.domain.metadata.model.DnTableMeta tbl = tableMetaMapper.selectById(tableMetaId);
        requireTableAccess(tbl);
        if (tbl == null) throw new BusinessException("物理表元数据不存在, 请先在数据地图采集该表");

        DnModel phys = new DnModel();
        phys.setModelCode(uniqueCode(toModelCode("REV_" + tbl.getTableName())));
        phys.setModelName((tbl.getTableComment() != null && !tbl.getTableComment().isEmpty()) ? tbl.getTableComment() : tbl.getTableName());
        phys.setModelType("PHYS");
        phys.setSubjectId(subjectId != null ? subjectId : tbl.getSubjectId());
        phys.setVersion(1);
        phys.setStatus("DRAFT");
        phys.setOwner(CurrentUserUtil.currentUser());
        phys.setCreatedBy(CurrentUserUtil.currentUser());
        phys.setDescription("逆向自物理表 " + tbl.getDatabaseName() + "." + tbl.getTableName());
        modelMapper.insert(phys);

        DnModelEntity e = new DnModelEntity();
        e.setModelId(phys.getId());
        e.setEntityCode(toCamel(tbl.getTableName()));
        e.setEntityName(phys.getModelName());
        e.setLevel(4);
        e.setPhysicalTable(tbl.getTableName());
        e.setBizDefinition(tbl.getTableComment());
        entityMapper.insert(e);

        List<com.datanote.domain.metadata.model.DnColumnMeta> cols = columnMetaMapper.selectList(
                new QueryWrapper<com.datanote.domain.metadata.model.DnColumnMeta>().eq("table_meta_id", tableMetaId).orderByAsc("ordinal", "id"));
        int order = 0;
        for (com.datanote.domain.metadata.model.DnColumnMeta c : cols) {
            DnModelAttribute a = new DnModelAttribute();
            a.setEntityId(e.getId());
            a.setAttrCode(c.getColumnName());
            a.setAttrName((c.getBusinessName() != null && !c.getBusinessName().isEmpty()) ? c.getBusinessName() : c.getColumnName());
            a.setDataType(normalizeType(c.getDataType()));
            a.setPhysicalColumn(c.getColumnName());
            a.setIsPk(c.getColumnKey() != null && c.getColumnKey().toUpperCase().contains("PRI") ? 1 : 0);
            a.setIsNullable("NO".equalsIgnoreCase(c.getIsNullable()) ? 0 : 1);
            a.setBizDefinition(c.getBusinessDesc());
            a.setSortOrder(order++);
            attrMapper.insert(a);
        }
        return phys;
    }

    /** 物理模型生成建表 DDL(每实体一张表)。 */
    public String generateDdl(Long physicalModelId) {
        requireModelAccess(physicalModelId);
        DnModel m = modelMapper.selectById(physicalModelId);
        if (m == null) throw new BusinessException("模型不存在");
        if (!"PHYS".equals(m.getModelType())) throw new BusinessException("仅物理模型可生成 DDL, 请先由逻辑模型生成物理模型");
        List<DnModelEntity> entities = entityMapper.selectList(
                new QueryWrapper<DnModelEntity>().eq("model_id", physicalModelId).orderByAsc("sort_order", "id"));
        StringBuilder sb = new StringBuilder();
        for (DnModelEntity e : entities) {
            String table = (e.getPhysicalTable() != null && !e.getPhysicalTable().isEmpty())
                    ? e.getPhysicalTable() : toSnake(e.getEntityCode());
            List<DnModelAttribute> attrs = attrMapper.selectList(
                    new QueryWrapper<DnModelAttribute>().eq("entity_id", e.getId()).orderByAsc("sort_order", "id"));
            sb.append("-- ").append(e.getEntityName() == null ? table : e.getEntityName()).append("\n");
            sb.append("CREATE TABLE ").append(table).append(" (\n");
            List<String> cols = new ArrayList<>();
            List<String> pks = new ArrayList<>();
            for (DnModelAttribute a : attrs) {
                String col = (a.getPhysicalColumn() != null && !a.getPhysicalColumn().isEmpty())
                        ? a.getPhysicalColumn() : toSnake(a.getAttrCode());
                StringBuilder line = new StringBuilder("  ").append(col).append(" ").append(sqlType(a.getDataType(), a.getDataLength()));
                if (a.getIsNullable() != null && a.getIsNullable() == 0) line.append(" NOT NULL");
                if (a.getDefaultValue() != null && !a.getDefaultValue().isEmpty()) line.append(" DEFAULT '").append(sqlEscape(a.getDefaultValue())).append("'");
                if (a.getAttrName() != null && !a.getAttrName().isEmpty()) line.append(" COMMENT '").append(sqlEscape(a.getAttrName())).append("'");
                cols.add(line.toString());
                if (a.getIsPk() != null && a.getIsPk() == 1) pks.add(col);
            }
            sb.append(String.join(",\n", cols));
            if (!pks.isEmpty()) sb.append(",\n  PRIMARY KEY (").append(String.join(", ", pks)).append(")");
            sb.append("\n)");
            if (e.getEntityName() != null) sb.append(" COMMENT='").append(sqlEscape(e.getEntityName())).append("'");
            sb.append(";\n\n");
        }
        return sb.toString();
    }

    // ---------------- 版本历史 ----------------

    public List<DnModelVersion> listVersions(Long modelId) {
        requireModelAccess(modelId);
        return versionMapper.selectList(
                new QueryWrapper<DnModelVersion>().eq("model_id", modelId).orderByDesc("version", "id"));
    }

    /** 建模覆盖度看板: 模型规模/类型状态分布/落地率/标准覆盖率/待审, 量化建模成熟度。 */
    public java.util.Map<String, Object> buildDashboard() {
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        List<DnModel> models = modelMapper.selectList(null);
        if (models == null) models = new ArrayList<>();
        java.util.Set<String> denied = dataAclService.deniedIds("MODEL");
        if (denied != null && !denied.isEmpty()) {
            models = new ArrayList<>(models);
            models.removeIf(m -> m == null || (m.getId() != null && denied.contains(String.valueOf(m.getId()))));
        }
        r.put("totalModels", models.size());
        java.util.Map<String, Integer> byType = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> byStatus = new java.util.LinkedHashMap<>();
        for (String t : new String[]{"BIZ", "LOGIC", "PHYS"}) byType.put(t, 0);
        for (String s : new String[]{"DRAFT", "PENDING", "PUBLISHED", "REJECTED", "ARCHIVED"}) byStatus.put(s, 0);
        java.util.Set<Long> subjWithModel = new java.util.HashSet<>();
        for (DnModel m : models) {
            byType.merge(m.getModelType() == null ? "?" : m.getModelType(), 1, Integer::sum);
            byStatus.merge(m.getStatus() == null ? "?" : m.getStatus(), 1, Integer::sum);
            if (m.getSubjectId() != null) subjWithModel.add(m.getSubjectId());
        }
        r.put("byType", byType);
        r.put("byStatus", byStatus);
        r.put("published", byStatus.getOrDefault("PUBLISHED", 0));
        r.put("landedTables", nz(tableMetaMapper.selectCount(new QueryWrapper<com.datanote.domain.metadata.model.DnTableMeta>().likeRight("database_name", "model_"))));
        long ta = nz(attrMapper.selectCount(null));
        long ba = nz(attrMapper.selectCount(new QueryWrapper<DnModelAttribute>().isNotNull("element_code").ne("element_code", "")));
        r.put("totalAttributes", ta);
        r.put("boundAttributes", ba);
        r.put("standardCoverage", ta == 0 ? 0 : Math.round(ba * 1000.0 / ta) / 10.0);
        r.put("pendingChanges", nz(changeMapper.selectCount(new QueryWrapper<DnModelChange>().eq("status", "pending"))));
        r.put("entities", nz(entityMapper.selectCount(null)));
        r.put("relations", nz(relMapper.selectCount(null)));
        r.put("subjectsWithModels", subjWithModel.size());
        return r;
    }
    private long nz(Long v) { return v == null ? 0 : v; }

    /** 数据标准影响分析: 反查引用某数据元(element_code)的所有模型属性, 治理专员改标准前看影响面。 */
    public List<java.util.Map<String, Object>> standardImpact(String elementCode) {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        if (elementCode == null || elementCode.trim().isEmpty()) return out;
        List<DnModelAttribute> attrs = attrMapper.selectList(new QueryWrapper<DnModelAttribute>().eq("element_code", elementCode.trim()));
        if (attrs.isEmpty()) return out;
        // 批量取实体与模型, 替代逐属性 selectById(消除 2N 次往返)
        List<Long> entIds = attrs.stream().map(DnModelAttribute::getEntityId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, DnModelEntity> entMap = new HashMap<>();
        if (!entIds.isEmpty()) { List<DnModelEntity> _ents = entityMapper.selectBatchIds(entIds); if (_ents != null) for (DnModelEntity e : _ents) entMap.put(e.getId(), e); } // selectList 理论可返回 null
        List<Long> modIds = entMap.values().stream().map(DnModelEntity::getModelId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, DnModel> modMap = new HashMap<>();
        if (!modIds.isEmpty()) { List<DnModel> _mods = modelMapper.selectBatchIds(modIds); if (_mods != null) for (DnModel m : _mods) modMap.put(m.getId(), m); } // selectList 理论可返回 null
        for (DnModelAttribute a : attrs) {
            DnModelEntity e = entMap.get(a.getEntityId());
            if (e == null) continue;
            DnModel m = modMap.get(e.getModelId());
            if (m == null) continue;
            if (!canAccessModel(m.getId())) continue;
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("modelCode", m.getModelCode());
            row.put("modelName", m.getModelName());
            row.put("modelType", m.getModelType());
            row.put("status", m.getStatus());
            row.put("entityName", e.getEntityName());
            row.put("attrCode", a.getAttrCode());
            row.put("attrName", a.getAttrName());
            row.put("dataType", a.getDataType());
            out.add(row);
        }
        return out;
    }

    public DnModelVersion getVersion(Long versionId) {
        DnModelVersion version = versionMapper.selectById(versionId);
        if (version != null) requireModelAccess(version.getModelId());
        return version;
    }

    /** 两版本字段级差异对比(实体增删 + 属性增删改)。 */
    public java.util.Map<String, Object> compareVersions(Long fromVid, Long toVid) {
        DnModelVersion fromV = versionMapper.selectById(fromVid);
        DnModelVersion toV = versionMapper.selectById(toVid);
        if (fromV == null || toV == null) throw new BusinessException("版本不存在");
        requireModelAccess(fromV.getModelId());
        requireModelAccess(toV.getModelId());
        DnModel fm = JSON.parseObject(fromV.getSnapshotJson(), DnModel.class);
        DnModel tm = JSON.parseObject(toV.getSnapshotJson(), DnModel.class);
        java.util.Map<String, DnModelEntity> fe = indexEntities(fm), te = indexEntities(tm);

        List<String> addedEntities = new ArrayList<>(), removedEntities = new ArrayList<>();
        for (String code : te.keySet()) if (!fe.containsKey(code)) addedEntities.add(te.get(code).getEntityName());
        for (String code : fe.keySet()) if (!te.containsKey(code)) removedEntities.add(fe.get(code).getEntityName());

        List<java.util.Map<String, Object>> entityDiffs = new ArrayList<>();
        for (String code : te.keySet()) {
            if (!fe.containsKey(code)) continue;   // 新增实体单列, 不进 diff
            DnModelEntity fEnt = fe.get(code), tEnt = te.get(code);
            java.util.Map<String, DnModelAttribute> fa = indexAttrs(fEnt), ta = indexAttrs(tEnt);
            List<String> addedAttrs = new ArrayList<>(), removedAttrs = new ArrayList<>();
            List<java.util.Map<String, Object>> changedAttrs = new ArrayList<>();
            for (String ac : ta.keySet()) if (!fa.containsKey(ac)) addedAttrs.add(ac);
            for (String ac : fa.keySet()) if (!ta.containsKey(ac)) removedAttrs.add(ac);
            for (String ac : ta.keySet()) {
                if (!fa.containsKey(ac)) continue;
                DnModelAttribute fAttr = fa.get(ac), tAttr = ta.get(ac);
                String ft = typeStr(fAttr), tt = typeStr(tAttr);
                boolean fPk = fAttr.getIsPk() != null && fAttr.getIsPk() == 1, tPk = tAttr.getIsPk() != null && tAttr.getIsPk() == 1;
                if (!ft.equals(tt) || fPk != tPk) {
                    java.util.Map<String, Object> ch = new java.util.HashMap<>();
                    ch.put("attr", ac); ch.put("from", ft + (fPk ? " PK" : "")); ch.put("to", tt + (tPk ? " PK" : ""));
                    changedAttrs.add(ch);
                }
            }
            if (!addedAttrs.isEmpty() || !removedAttrs.isEmpty() || !changedAttrs.isEmpty()) {
                java.util.Map<String, Object> d = new java.util.HashMap<>();
                d.put("entity", tEnt.getEntityName());
                d.put("addedAttrs", addedAttrs); d.put("removedAttrs", removedAttrs); d.put("changedAttrs", changedAttrs);
                entityDiffs.add(d);
            }
        }
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("fromVersion", fromV.getVersion()); r.put("toVersion", toV.getVersion());
        r.put("addedEntities", addedEntities); r.put("removedEntities", removedEntities);
        r.put("entityDiffs", entityDiffs);
        r.put("identical", addedEntities.isEmpty() && removedEntities.isEmpty() && entityDiffs.isEmpty());
        return r;
    }

    private java.util.Map<String, DnModelEntity> indexEntities(DnModel m) {
        java.util.Map<String, DnModelEntity> map = new java.util.LinkedHashMap<>();
        if (m != null && m.getEntities() != null) for (DnModelEntity e : m.getEntities()) map.put(e.getEntityCode(), e);
        return map;
    }
    private java.util.Map<String, DnModelAttribute> indexAttrs(DnModelEntity e) {
        java.util.Map<String, DnModelAttribute> map = new java.util.LinkedHashMap<>();
        if (e != null && e.getAttributes() != null) for (DnModelAttribute a : e.getAttributes()) map.put(a.getAttrCode(), a);
        return map;
    }
    private String typeStr(DnModelAttribute a) {
        String t = a.getDataType() == null ? "" : a.getDataType();
        return (a.getDataLength() != null && !a.getDataLength().isEmpty()) ? t + "(" + a.getDataLength() + ")" : t;
    }

    // ---------------- 资产落地(物理模型 → 数据地图元数据) ----------------

    /** 已发布物理模型落地为数据资产: 实体→表元数据, 属性→字段元数据, 注册到数据地图(可被治理/血缘消费)。 */
    @Transactional(rollbackFor = Exception.class)
    public java.util.Map<String, Object> publishToAsset(Long physModelId) {
        requireModelAccess(physModelId);
        DnModel m = modelMapper.selectById(physModelId);
        if (m == null) throw new BusinessException("模型不存在");
        if (!"PHYS".equals(m.getModelType())) throw new BusinessException("仅物理模型可落地为数据资产");
        if (!"PUBLISHED".equals(m.getStatus())) throw new BusinessException("请先提交审批并发布该模型后再落地资产");

        String db = "model_" + m.getModelCode();
        LocalDateTime now = LocalDateTime.now();
        List<DnModelEntity> entities = entityMapper.selectList(
                new QueryWrapper<DnModelEntity>().eq("model_id", physModelId).orderByAsc("sort_order", "id"));
        int tables = 0, cols = 0, graded = 0, qrules = 0;
        for (DnModelEntity e : entities) {
            String tableName = (e.getPhysicalTable() != null && !e.getPhysicalTable().isEmpty())
                    ? e.getPhysicalTable() : toSnake(e.getEntityCode());
            com.datanote.domain.metadata.model.DnTableMeta tbl = tableMetaMapper.selectOne(
                    new QueryWrapper<com.datanote.domain.metadata.model.DnTableMeta>()
                            .eq("database_name", db).eq("table_name", tableName).last("limit 1"));
            boolean isNew = (tbl == null);
            if (isNew) {
                tbl = new com.datanote.domain.metadata.model.DnTableMeta();
                tbl.setDatasourceId(0L);
                tbl.setDatabaseName(db);
                tbl.setTableName(tableName);
                tbl.setCreatedAt(now);
            }
            tbl.setTableComment(e.getEntityName());
            tbl.setSubjectId(m.getSubjectId());
            tbl.setOwner(m.getOwner());
            tbl.setDbType("MODEL");
            tbl.setTableType("MODEL");
            tbl.setUpdatedAt(now);
            if (isNew) tableMetaMapper.insert(tbl); else tableMetaMapper.updateById(tbl);
            tables++;
            columnMetaMapper.delete(new QueryWrapper<com.datanote.domain.metadata.model.DnColumnMeta>().eq("table_meta_id", tbl.getId()));
            List<DnModelAttribute> attrs = attrMapper.selectList(
                    new QueryWrapper<DnModelAttribute>().eq("entity_id", e.getId()).orderByAsc("sort_order", "id"));
            int ord = 0;
            for (DnModelAttribute a : attrs) {
                String physCol = (a.getPhysicalColumn() != null && !a.getPhysicalColumn().isEmpty()) ? a.getPhysicalColumn() : toSnake(a.getAttrCode());
                com.datanote.domain.metadata.model.DnColumnMeta col = new com.datanote.domain.metadata.model.DnColumnMeta();
                col.setTableMetaId(tbl.getId());
                col.setColumnName(physCol);
                col.setBusinessName(a.getAttrName());
                col.setBusinessDesc(a.getBizDefinition());
                col.setDataType(sqlType(a.getDataType(), a.getDataLength()));
                col.setColumnKey(a.getIsPk() != null && a.getIsPk() == 1 ? "PRI" : "");
                col.setIsNullable(a.getIsNullable() != null && a.getIsNullable() == 0 ? "NO" : "YES");
                col.setOrdinal(ord++);
                // 分级回填: 绑数据标准的属性带出密级/敏感类型 → 分级脱敏模块消费(否则这两列空着)
                if (a.getElementCode() != null && !a.getElementCode().trim().isEmpty()) {
                    com.datanote.domain.governance.model.DnDataElement de = dataElementMapper.selectOne(
                            new QueryWrapper<com.datanote.domain.governance.model.DnDataElement>().eq("element_code", a.getElementCode().trim()).last("limit 1"));
                    if (de != null && (de.getSecurityLevel() != null || de.getSensitiveType() != null)) {
                        col.setSecurityLevel(de.getSecurityLevel());
                        col.setSensitiveType(de.getSensitiveType());
                        graded++;
                    }
                }
                col.setCreatedAt(now);
                col.setUpdatedAt(now);
                columnMetaMapper.insert(col);
                cols++;
                // 质量规则建议(停用待治理专员审核): 建模约束→质量规则, 免手工填表名列名
                qrules += deriveQualityRules(db, tableName, physCol, a);
            }
        }
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("database", db);
        r.put("tables", tables);
        r.put("columns", cols);
        r.put("gradedColumns", graded);
        r.put("qualityRules", qrules);
        return r;
    }

    /** 由建模约束派生质量规则建议(停用态)。返回新建条数。 */
    private int deriveQualityRules(String db, String table, String column, DnModelAttribute a) {
        String user = CurrentUserUtil.currentUser();
        int n = 0;
        if (a.getIsNullable() != null && a.getIsNullable() == 0) n += insertSuggestRule(db, table, column, "null_check", "非空·" + column, user);
        if (a.getIsPk() != null && a.getIsPk() == 1) n += insertSuggestRule(db, table, column, "unique_check", "唯一·" + column, user);
        return n;
    }
    private int insertSuggestRule(String db, String table, String column, String ruleType, String ruleName, String user) {
        Long exist = qualityRuleMapper.selectCount(new QueryWrapper<com.datanote.domain.governance.model.DnQualityRule>()
                .eq("database_name", db).eq("table_name", table).eq("column_name", column).eq("rule_type", ruleType));
        if (exist != null && exist > 0) return 0;   // 幂等
        com.datanote.domain.governance.model.DnQualityRule r = new com.datanote.domain.governance.model.DnQualityRule();
        r.setRuleName(ruleName);
        r.setRuleType(ruleType);
        r.setDatasourceId(0L);
        r.setDatabaseName(db);
        r.setTableName(table);
        r.setColumnName(column);
        r.setSeverity("medium");
        r.setStatus(1);   // 启用: 派生发生在 publishToAsset(物理表已落地)时, 直接启用使 建模→质量 闭环(否则用户感受不到价值)
        r.setPassThreshold(new java.math.BigDecimal("1.00"));
        r.setDimension("completeness");
        r.setCreatedBy(user);
        r.setCreatedAt(LocalDateTime.now());
        qualityRuleMapper.insert(r);
        return 1;
    }

    // ---------------- 规范 / 工具 ----------------

    /** DDL 字符串字面量转义(展示用 DDL, 防含单引号的默认值/名称生成语法错误)。 */
    private String sqlEscape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /** 逻辑数据类型 → SQL 类型映射。 */
    private String sqlType(String dataType, String len) {
        String t = dataType == null ? "STRING" : dataType.trim().toUpperCase();
        boolean hasLen = len != null && !len.trim().isEmpty();
        switch (t) {
            case "STRING": case "VARCHAR": case "CHAR": return "VARCHAR(" + (hasLen ? len : "255") + ")";
            case "TEXT": case "CLOB": return "TEXT";
            case "INT": case "INTEGER": return "INT";
            case "BIGINT": case "LONG": return "BIGINT";
            case "SMALLINT": return "SMALLINT";
            case "DECIMAL": case "NUMERIC": return "DECIMAL(" + (hasLen ? len : "18,2") + ")";
            case "DOUBLE": return "DOUBLE";
            case "FLOAT": return "FLOAT";
            case "DATE": return "DATE";
            case "DATETIME": case "TIMESTAMP": return "DATETIME";
            case "TIME": return "TIME";
            case "BOOLEAN": case "BOOL": return "TINYINT(1)";
            default: return "VARCHAR(" + (hasLen ? len : "255") + ")";
        }
    }

    /** 驼峰/混合 → snake_case(物理命名规范)。 */
    private String toSnake(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[\\s-]+", "_").toLowerCase();
    }

    /** snake_case → CamelCase(逆向实体编码)。 */
    private String toCamel(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] parts = s.split("[_\\s-]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (p.isEmpty()) continue; sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase()); }
        if (sb.length() > 0) return sb.toString();
        String fb = toModelCode(s);
        return (fb == null || fb.isEmpty()) ? "T" : fb;
    }

    /** 物理类型 → 逻辑类型(逆向归一化)。 */
    private String normalizeType(String physType) {
        if (physType == null) return "STRING";
        String t = physType.trim().toUpperCase();
        if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.startsWith("NVARCHAR")) return "STRING";
        if (t.startsWith("TEXT") || t.startsWith("CLOB") || t.contains("LONGTEXT")) return "TEXT";
        if (t.startsWith("BIGINT")) return "BIGINT";
        if (t.startsWith("INT") || t.startsWith("TINYINT") || t.startsWith("SMALLINT") || t.startsWith("MEDIUMINT")) return "INT";
        if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC") || t.startsWith("NUMBER")) return "DECIMAL";
        if (t.startsWith("DOUBLE")) return "DOUBLE";
        if (t.startsWith("FLOAT")) return "FLOAT";
        if (t.startsWith("DATETIME") || t.startsWith("TIMESTAMP")) return "DATETIME";
        if (t.startsWith("DATE")) return "DATE";
        if (t.startsWith("TIME")) return "TIME";
        if (t.startsWith("BOOL") || t.equals("BIT")) return "BOOLEAN";
        return "STRING";
    }

    /** 规范化为合法模型编码(大写, 非字母数字下划线→下划线, 字母开头)。 */
    private String toModelCode(String s) {
        if (s == null || s.isEmpty()) return "MODEL";
        String c = s.toUpperCase().replaceAll("[^A-Za-z0-9_]", "_");
        if (!c.matches("[A-Za-z].*")) c = "M_" + c;
        return c;
    }

    /** 编码唯一化(冲突时追加短时间戳后缀)。 */
    private String uniqueCode(String base) {
        Long cnt = modelMapper.selectCount(new QueryWrapper<DnModel>().eq("model_code", base));
        if (cnt == null || cnt == 0) return base;
        return base + "_" + (System.currentTimeMillis() % 100000);
    }

    /** 命名词根校验: attr_code 拆词比对 dn_word_root(word_en/abbr)。词根库为空则跳过(不产噪音)。 */
    private String checkNaming(String code) {
        if (code == null || code.isEmpty()) return null;
        Long totalRoots = wordRootMapper.selectCount(null);
        if (totalRoots == null || totalRoots == 0) return null;
        String[] segs = code.toLowerCase().split("[_\\s]+");
        int hit = 0, total = 0;
        for (String s : segs) {
            if (s.isEmpty()) continue;
            total++;
            Long c = wordRootMapper.selectCount(new QueryWrapper<com.datanote.domain.governance.model.DnWordRoot>()
                    .apply("LOWER(word_en)={0} OR LOWER(abbr)={0}", s));
            if (c != null && c > 0) hit++;
        }
        if (total == 0 || hit == total) return null;
        return "命名含 " + (total - hit) + "/" + total + " 段未匹配标准词根(建议参照词根库规范化)";
    }

    /** 编码规范: 非空 + 仅字母数字下划线 + 字母开头。 */
    private void validateCode(String code, String label) {
        if (code == null || code.trim().isEmpty()) throw new BusinessException(label + "不能为空");
        if (!code.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new BusinessException(label + "格式不规范(须字母开头, 仅含字母/数字/下划线): " + code);
        }
    }
}
