package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmDomainMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenHistoryMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmDomain;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenHistory;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 主数据管理（MDM）服务 — 域/实体/属性建模层的统计、级联与计数维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdmService {

    private final DnMdmDomainMapper domainMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmGoldenHistoryMapper goldenHistoryMapper;
    private final ObjectMapper objectMapper;

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

    /** R128: 黄金记录变更后快照(写 dn_mdm_golden_history)。黄金记录直改与审批应用两条链路共用; 失败不影响主流程。 */
    public void snapshotGolden(DnMdmGoldenRecord rec, String changeType) {
        try {
            DnMdmGoldenHistory h = new DnMdmGoldenHistory();
            h.setGoldenId(rec.getId());
            h.setEntityId(rec.getEntityId());
            h.setVersion(rec.getVersion());
            h.setBizKey(rec.getBizKey());
            h.setStatus(rec.getStatus());
            h.setDataJson(rec.getDataJson());
            h.setChangeType(changeType);
            h.setCreatedAt(java.time.LocalDateTime.now());
            goldenHistoryMapper.insert(h);
        } catch (Exception e) {
            // 快照失败不影响主流程, 但须留痕便于排查(原静默 ignore 致快照丢失无任何痕迹)
            log.warn("黄金记录快照写入失败 goldenId={} changeType={}: {}", rec == null ? null : rec.getId(), changeType, e.getMessage());
        }
    }

    /** #18: 黄金记录属性值统一校验(JSON 合法性 + 必填项)并计算业务主键。
     *  golden/save 直存与审批 applyChange 两条落库通道共用同一道门禁, 杜绝审批通道绕过校验落脏数据。
     *  校验失败抛 BusinessException(消息含缺失属性名); 通过返回业务主键(优先关键/唯一属性首个非空值, 可能为 null 由调用方兜底)。 */
    @SuppressWarnings("unchecked")
    public String validateGoldenData(Long entityId, String dataJson) {
        Map<String, Object> values = new HashMap<>();
        if (dataJson != null && !dataJson.trim().isEmpty()) {
            try {
                values = objectMapper.readValue(dataJson, Map.class);
            } catch (Exception e) {
                throw new BusinessException("属性值格式错误（非合法 JSON）");
            }
        }
        QueryWrapper<DnMdmAttribute> aqw = new QueryWrapper<>();
        aqw.eq("entity_id", entityId).orderByAsc("sort_order").orderByAsc("id");
        List<DnMdmAttribute> attrs = attributeMapper.selectList(aqw);
        String bizKey = null;
        for (DnMdmAttribute a : attrs) {
            Object v = values.get(a.getAttrCode());
            boolean empty = (v == null || String.valueOf(v).trim().isEmpty());
            if (a.getRequired() != null && a.getRequired() == 1 && empty) {
                throw new BusinessException("必填属性未填写：" + a.getAttrName());
            }
            // 业务主键优先取关键字段，其次唯一字段的首个非空值
            if (bizKey == null && !empty && ((a.getIsKey() != null && a.getIsKey() == 1)
                    || (a.getIsUnique() != null && a.getIsUnique() == 1))) {
                bizKey = String.valueOf(v);
            }
        }
        if (bizKey == null) {
            // 退化：取第一个非空属性值
            for (DnMdmAttribute a : attrs) {
                Object v = values.get(a.getAttrCode());
                if (v != null && !String.valueOf(v).trim().isEmpty()) { bizKey = String.valueOf(v); break; }
            }
        }
        return bizKey;
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
