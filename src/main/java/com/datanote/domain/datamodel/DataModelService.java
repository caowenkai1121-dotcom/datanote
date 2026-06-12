package com.datanote.domain.datamodel;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.mapper.*;
import com.datanote.domain.datamodel.model.*;
import com.datanote.platform.iam.CurrentUserUtil;
import com.datanote.platform.notify.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    // ---------------- 模型 ----------------

    /** 模型列表(可按类型/主题域/状态过滤)，附实体数。 */
    public List<DnModel> listModels(String modelType, Long subjectId, String status) {
        QueryWrapper<DnModel> qw = new QueryWrapper<>();
        if (modelType != null && !modelType.isEmpty()) qw.eq("model_type", modelType);
        if (subjectId != null) qw.eq("subject_id", subjectId);
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("updated_at");
        List<DnModel> list = modelMapper.selectList(qw);
        for (DnModel m : list) {
            Long ec = entityMapper.selectCount(new QueryWrapper<DnModelEntity>().eq("model_id", m.getId()));
            m.setEntityCount(ec == null ? 0 : ec.intValue());
            if (m.getSourceModelId() != null) {
                DnModel src = modelMapper.selectById(m.getSourceModelId());
                if (src != null) m.setSourceModelName(src.getModelName());
            }
        }
        return list;
    }

    /** 模型详情: 含实体(每实体含属性)与关系。 */
    public DnModel getModelDetail(Long id) {
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
        modelMapper.deleteById(id);
    }

    // ---------------- 实体 / 属性 / 关系 ----------------

    public DnModelEntity saveEntity(DnModelEntity entity) {
        if (entity.getModelId() == null) throw new BusinessException("实体须归属模型");
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
        attrMapper.delete(new QueryWrapper<DnModelAttribute>().eq("entity_id", id));
        entityMapper.deleteById(id);
    }

    /** 覆盖式保存某实体的全部属性(先删后插)。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveAttributes(Long entityId, List<DnModelAttribute> attrs) {
        if (entityId == null) throw new BusinessException("属性须归属实体");
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
        if (rel.getId() == null) relMapper.insert(rel); else relMapper.updateById(rel);
        return relMapper.selectById(rel.getId());
    }

    public void deleteRelation(Long id) {
        relMapper.deleteById(id);
    }

    // ---------------- 申请 / 审批 流转 ----------------

    /** 提交模型审批: DRAFT/REJECTED → PENDING, 建变更工单(快照)。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModelChange submitForApproval(Long modelId, String reason) {
        DnModel m = modelMapper.selectById(modelId);
        if (m == null) throw new BusinessException("模型不存在");
        if ("PENDING".equals(m.getStatus())) throw new BusinessException("模型已在审批中, 请勿重复提交");
        if ("PUBLISHED".equals(m.getStatus())) throw new BusinessException("模型已发布, 如需变更请直接修改后再提交");

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

        String reviewer = CurrentUserUtil.currentUser();
        // 禁自批: 自己提交的申请不能自己审批(admin 例外, 防单管理员环境锁死)
        if (reviewer != null && reviewer.equals(c.getRequestedBy()) && !"admin".equals(reviewer)) {
            throw new BusinessException("不能审批自己提交的申请");
        }

        boolean approved = "approved".equals(target);
        c.setStatus(approved ? "approved" : "rejected");
        c.setReviewer(reviewer);
        c.setReviewComment(comment);
        c.setDecidedAt(LocalDateTime.now());
        changeMapper.updateById(c);

        DnModel m = modelMapper.selectById(c.getModelId());
        if (m != null) {
            if (approved) {
                m.setStatus("PUBLISHED");
                m.setVersion((m.getVersion() == null ? 1 : m.getVersion()) + 1);
            } else {
                m.setStatus("REJECTED");
            }
            modelMapper.updateById(m);
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
        for (DnModelChange c : list) {
            DnModel m = modelMapper.selectById(c.getModelId());
            if (m != null) { c.setModelName(m.getModelName()); c.setModelCode(m.getModelCode()); }
        }
        return list;
    }

    // ---------------- 模型生成 ----------------

    /** 由逻辑模型生成物理模型(实体→表、属性→字段、类型映射, 溯源回填)。 */
    @Transactional(rollbackFor = Exception.class)
    public DnModel generatePhysical(Long logicalModelId) {
        DnModel logical = modelMapper.selectById(logicalModelId);
        if (logical == null) throw new BusinessException("源逻辑模型不存在");
        if (!"LOGIC".equals(logical.getModelType())) throw new BusinessException("仅逻辑模型可生成物理模型");

        DnModel phys = new DnModel();
        phys.setModelCode(logical.getModelCode() + "_PHY");
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
        // 编码冲突时追加时间戳后缀
        if (modelMapper.selectCount(new QueryWrapper<DnModel>().eq("model_code", phys.getModelCode())) > 0) {
            phys.setModelCode(phys.getModelCode() + "_" + System.currentTimeMillis() % 100000);
        }
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

    /** 物理模型生成建表 DDL(每实体一张表)。 */
    public String generateDdl(Long physicalModelId) {
        DnModel m = modelMapper.selectById(physicalModelId);
        if (m == null) throw new BusinessException("模型不存在");
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
                if (a.getDefaultValue() != null && !a.getDefaultValue().isEmpty()) line.append(" DEFAULT '").append(a.getDefaultValue()).append("'");
                if (a.getAttrName() != null && !a.getAttrName().isEmpty()) line.append(" COMMENT '").append(a.getAttrName()).append("'");
                cols.add(line.toString());
                if (a.getIsPk() != null && a.getIsPk() == 1) pks.add(col);
            }
            sb.append(String.join(",\n", cols));
            if (!pks.isEmpty()) sb.append(",\n  PRIMARY KEY (").append(String.join(", ", pks)).append(")");
            sb.append("\n)");
            if (e.getEntityName() != null) sb.append(" COMMENT='").append(e.getEntityName()).append("'");
            sb.append(";\n\n");
        }
        return sb.toString();
    }

    // ---------------- 规范 / 工具 ----------------

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

    /** 编码规范: 非空 + 仅字母数字下划线 + 字母开头。 */
    private void validateCode(String code, String label) {
        if (code == null || code.trim().isEmpty()) throw new BusinessException(label + "不能为空");
        if (!code.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new BusinessException(label + "格式不规范(须字母开头, 仅含字母/数字/下划线): " + code);
        }
    }
}
