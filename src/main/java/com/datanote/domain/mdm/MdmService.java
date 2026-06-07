package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmDomainMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmDomain;
import com.datanote.domain.mdm.model.DnMdmEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 主数据管理（MDM）服务 — 域/实体/属性建模层的统计、级联与计数维护。
 */
@Service
@RequiredArgsConstructor
public class MdmService {

    private final DnMdmDomainMapper domainMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;

    /** 主数据总览统计：域/实体/属性数量、按类别分布、各域实体数。 */
    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<>();
        List<DnMdmDomain> domains = domainMapper.selectList(null);
        long entityTotal = entityMapper.selectCount(null);
        long attrTotal = attributeMapper.selectCount(null);

        data.put("domainCount", domains.size());
        long enabledDomains = domains.stream().filter(d -> d.getStatus() == null || d.getStatus() == 1).count();
        data.put("enabledDomainCount", enabledDomains);
        data.put("entityCount", entityTotal);
        data.put("attributeCount", attrTotal);

        // 黄金记录统计
        long goldenTotal = goldenMapper.selectCount(null);
        QueryWrapper<com.datanote.domain.mdm.model.DnMdmGoldenRecord> gqw = new QueryWrapper<>();
        gqw.eq("status", "active");
        long goldenActive = goldenMapper.selectCount(gqw);
        data.put("goldenCount", goldenTotal);
        data.put("goldenActiveCount", goldenActive);

        // 按业务类别分布
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (DnMdmDomain d : domains) {
            String c = (d.getCategory() == null || d.getCategory().isEmpty()) ? "未分类" : d.getCategory();
            byCategory.merge(c, 1, Integer::sum);
        }
        data.put("byCategory", byCategory);

        // 各域实体数 Top（建模规模视图）
        List<DnMdmEntity> entities = entityMapper.selectList(null);
        Map<Long, Integer> entityCntByDomain = new HashMap<>();
        for (DnMdmEntity e : entities) {
            entityCntByDomain.merge(e.getDomainId(), 1, Integer::sum);
        }
        List<Map<String, Object>> domainScale = new ArrayList<>();
        for (DnMdmDomain d : domains) {
            Map<String, Object> m = new HashMap<>();
            m.put("domainName", d.getDomainName());
            m.put("category", d.getCategory());
            m.put("entityCount", entityCntByDomain.getOrDefault(d.getId(), 0));
            domainScale.add(m);
        }
        domainScale.sort((a, b) -> ((Integer) b.get("entityCount")) - ((Integer) a.get("entityCount")));
        data.put("domainScale", domainScale);
        return data;
    }

    /** 域列表（附带实体数）。 */
    public List<DnMdmDomain> listDomains() {
        QueryWrapper<DnMdmDomain> qw = new QueryWrapper<>();
        qw.orderByDesc("updated_at");
        List<DnMdmDomain> domains = domainMapper.selectList(qw);
        List<DnMdmEntity> entities = entityMapper.selectList(null);
        Map<Long, Integer> cnt = new HashMap<>();
        for (DnMdmEntity e : entities) cnt.merge(e.getDomainId(), 1, Integer::sum);
        for (DnMdmDomain d : domains) d.setEntityCount(cnt.getOrDefault(d.getId(), 0));
        return domains;
    }

    /** 删除域：级联删除其下实体与属性。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDomain(Long domainId) {
        QueryWrapper<DnMdmEntity> eqw = new QueryWrapper<>();
        eqw.eq("domain_id", domainId);
        List<DnMdmEntity> entities = entityMapper.selectList(eqw);
        for (DnMdmEntity e : entities) {
            deleteEntityCascade(e.getId());
        }
        domainMapper.deleteById(domainId);
    }

    /** 删除实体：级联删除其下属性。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteEntityCascade(Long entityId) {
        QueryWrapper<DnMdmAttribute> aqw = new QueryWrapper<>();
        aqw.eq("entity_id", entityId);
        attributeMapper.delete(aqw);
        entityMapper.deleteById(entityId);
    }

    /** 同步实体的冗余属性计数。 */
    public void syncAttrCount(Long entityId) {
        if (entityId == null) return;
        QueryWrapper<DnMdmAttribute> aqw = new QueryWrapper<>();
        aqw.eq("entity_id", entityId);
        long n = attributeMapper.selectCount(aqw);
        DnMdmEntity e = entityMapper.selectById(entityId);
        if (e != null) {
            e.setAttrCount((int) n);
            entityMapper.updateById(e);
        }
    }
}
