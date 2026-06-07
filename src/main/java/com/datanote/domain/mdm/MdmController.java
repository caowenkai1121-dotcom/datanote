package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmDomainMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmDomain;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.MdmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 主数据管理（MDM）Controller —— 主数据域 / 实体 / 属性建模层 CRUD 与总览。
 */
@RestController
@RequestMapping("/api/mdm")
@Tag(name = "主数据管理", description = "主数据域、实体、属性建模与总览")
@RequiredArgsConstructor
public class MdmController {

    private final DnMdmDomainMapper domainMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final MdmService mdmService;

    // ===================== 总览 =====================
    @Operation(summary = "主数据总览统计")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(mdmService.overview());
    }

    // ===================== 域 =====================
    @Operation(summary = "主数据域列表")
    @GetMapping("/domains")
    public R<List<DnMdmDomain>> listDomains() {
        return R.ok(mdmService.listDomains());
    }

    @Operation(summary = "保存主数据域")
    @PostMapping("/domain/save")
    public R<DnMdmDomain> saveDomain(@RequestBody DnMdmDomain domain) {
        if (domain.getDomainCode() == null || domain.getDomainCode().trim().isEmpty()) {
            throw new BusinessException("域编码不能为空");
        }
        if (domain.getDomainName() == null || domain.getDomainName().trim().isEmpty()) {
            throw new BusinessException("域名称不能为空");
        }
        // 编码唯一校验（排除自身）
        QueryWrapper<DnMdmDomain> qw = new QueryWrapper<>();
        qw.eq("domain_code", domain.getDomainCode().trim());
        if (domain.getId() != null) qw.ne("id", domain.getId());
        if (domainMapper.selectCount(qw) > 0) {
            throw new BusinessException("域编码已存在：" + domain.getDomainCode());
        }
        domain.setDomainCode(domain.getDomainCode().trim());
        if (domain.getStatus() == null) domain.setStatus(1);
        if (domain.getId() != null) {
            domain.setUpdatedAt(LocalDateTime.now());
            domainMapper.updateById(domain);
        } else {
            domain.setCreatedAt(LocalDateTime.now());
            domain.setUpdatedAt(LocalDateTime.now());
            domainMapper.insert(domain);
        }
        return R.ok(domain);
    }

    @Operation(summary = "删除主数据域（级联删除实体与属性）")
    @DeleteMapping("/domain/{id}")
    public R<String> deleteDomain(@PathVariable Long id) {
        mdmService.deleteDomain(id);
        return R.ok("删除成功");
    }

    // ===================== 实体 =====================
    @Operation(summary = "实体列表（按域筛选）")
    @GetMapping("/entities")
    public R<List<DnMdmEntity>> listEntities(@RequestParam(required = false) Long domainId) {
        QueryWrapper<DnMdmEntity> qw = new QueryWrapper<>();
        if (domainId != null) qw.eq("domain_id", domainId);
        qw.orderByDesc("updated_at");
        List<DnMdmEntity> entities = entityMapper.selectList(qw);
        // 填充域名称
        for (DnMdmEntity e : entities) {
            DnMdmDomain d = domainMapper.selectById(e.getDomainId());
            if (d != null) e.setDomainName(d.getDomainName());
        }
        return R.ok(entities);
    }

    @Operation(summary = "保存实体")
    @PostMapping("/entity/save")
    public R<DnMdmEntity> saveEntity(@RequestBody DnMdmEntity entity) {
        if (entity.getDomainId() == null) throw new BusinessException("请先选择所属域");
        if (entity.getEntityCode() == null || entity.getEntityCode().trim().isEmpty()) {
            throw new BusinessException("实体编码不能为空");
        }
        if (entity.getEntityName() == null || entity.getEntityName().trim().isEmpty()) {
            throw new BusinessException("实体名称不能为空");
        }
        if (domainMapper.selectById(entity.getDomainId()) == null) {
            throw new ResourceNotFoundException("所属域");
        }
        // 同域内编码唯一
        QueryWrapper<DnMdmEntity> qw = new QueryWrapper<>();
        qw.eq("domain_id", entity.getDomainId()).eq("entity_code", entity.getEntityCode().trim());
        if (entity.getId() != null) qw.ne("id", entity.getId());
        if (entityMapper.selectCount(qw) > 0) {
            throw new BusinessException("该域下实体编码已存在：" + entity.getEntityCode());
        }
        entity.setEntityCode(entity.getEntityCode().trim());
        if (entity.getStatus() == null) entity.setStatus(1);
        if (entity.getId() != null) {
            entity.setUpdatedAt(LocalDateTime.now());
            entityMapper.updateById(entity);
        } else {
            entity.setAttrCount(0);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            entityMapper.insert(entity);
        }
        return R.ok(entity);
    }

    @Operation(summary = "删除实体（级联删除属性）")
    @DeleteMapping("/entity/{id}")
    public R<String> deleteEntity(@PathVariable Long id) {
        mdmService.deleteEntityCascade(id);
        return R.ok("删除成功");
    }

    // ===================== 属性 =====================
    @Operation(summary = "属性列表（按实体）")
    @GetMapping("/attributes")
    public R<List<DnMdmAttribute>> listAttributes(@RequestParam Long entityId) {
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("sort_order").orderByAsc("id");
        return R.ok(attributeMapper.selectList(qw));
    }

    @Operation(summary = "保存属性")
    @PostMapping("/attribute/save")
    public R<DnMdmAttribute> saveAttribute(@RequestBody DnMdmAttribute attr) {
        if (attr.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        if (attr.getAttrCode() == null || attr.getAttrCode().trim().isEmpty()) {
            throw new BusinessException("属性编码不能为空");
        }
        if (attr.getAttrName() == null || attr.getAttrName().trim().isEmpty()) {
            throw new BusinessException("属性名称不能为空");
        }
        if (entityMapper.selectById(attr.getEntityId()) == null) {
            throw new ResourceNotFoundException("所属实体");
        }
        // 同实体内属性编码唯一
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", attr.getEntityId()).eq("attr_code", attr.getAttrCode().trim());
        if (attr.getId() != null) qw.ne("id", attr.getId());
        if (attributeMapper.selectCount(qw) > 0) {
            throw new BusinessException("该实体下属性编码已存在：" + attr.getAttrCode());
        }
        attr.setAttrCode(attr.getAttrCode().trim());
        if (attr.getDataType() == null || attr.getDataType().trim().isEmpty()) attr.setDataType("STRING");
        if (attr.getId() != null) {
            attr.setUpdatedAt(LocalDateTime.now());
            attributeMapper.updateById(attr);
        } else {
            attr.setCreatedAt(LocalDateTime.now());
            attr.setUpdatedAt(LocalDateTime.now());
            attributeMapper.insert(attr);
        }
        mdmService.syncAttrCount(attr.getEntityId());
        return R.ok(attr);
    }

    @Operation(summary = "删除属性")
    @DeleteMapping("/attribute/{id}")
    public R<String> deleteAttribute(@PathVariable Long id) {
        DnMdmAttribute attr = attributeMapper.selectById(id);
        attributeMapper.deleteById(id);
        if (attr != null) mdmService.syncAttrCount(attr.getEntityId());
        return R.ok("删除成功");
    }
}
