package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.exception.BusinessException;
import com.datanote.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmEntityMapper;
import com.datanote.mapper.DnMdmSurvivorshipRuleMapper;
import com.datanote.model.DnMdmSurvivorshipRule;
import com.datanote.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 存活性规则（Survivorship）Controller —— 定义实体各属性在黄金记录合并时的存活策略。
 */
@RestController
@RequestMapping("/api/mdm/survivorship")
@Tag(name = "主数据-存活性规则", description = "黄金记录合并时各属性的存活策略配置")
@RequiredArgsConstructor
public class MdmSurvivorshipController {

    private final DnMdmSurvivorshipRuleMapper ruleMapper;
    private final DnMdmEntityMapper entityMapper;

    /** 可选存活策略枚举（与前端下拉一致） */
    private static final List<String> STRATEGIES = Arrays.asList("latest", "most_complete", "source_priority");

    @Operation(summary = "存活策略枚举说明")
    @GetMapping("/strategies")
    public R<List<Map<String, String>>> strategies() {
        return R.ok(Arrays.asList(
                Map.of("value", "latest", "label", "最新值", "desc", "取更新时间最新的来源值"),
                Map.of("value", "most_complete", "label", "最完整", "desc", "取非空且长度最长（信息最完整）的值"),
                Map.of("value", "source_priority", "label", "源系统优先", "desc", "按指定源系统优先级清单依次取值")
        ));
    }

    @Operation(summary = "存活性规则列表（按实体）")
    @GetMapping("/list")
    public R<List<DnMdmSurvivorshipRule>> list(@RequestParam Long entityId) {
        QueryWrapper<DnMdmSurvivorshipRule> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("priority").orderByAsc("id");
        return R.ok(ruleMapper.selectList(qw));
    }

    @Operation(summary = "保存存活性规则")
    @PostMapping("/save")
    public R<DnMdmSurvivorshipRule> save(@RequestBody DnMdmSurvivorshipRule rule) {
        if (rule.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        if (rule.getAttrCode() == null || rule.getAttrCode().trim().isEmpty()) {
            throw new BusinessException("属性编码不能为空");
        }
        if (entityMapper.selectById(rule.getEntityId()) == null) {
            throw new ResourceNotFoundException("所属实体");
        }
        if (rule.getStrategy() == null || !STRATEGIES.contains(rule.getStrategy())) {
            throw new BusinessException("存活策略无效，应为 latest / most_complete / source_priority");
        }
        // 源优先策略必须提供源系统优先级清单
        if ("source_priority".equals(rule.getStrategy())
                && (rule.getSourcePriority() == null || rule.getSourcePriority().trim().isEmpty())) {
            throw new BusinessException("「源系统优先」策略需填写源系统优先级清单");
        }
        // 同实体内属性唯一（UNIQUE entity_id + attr_code），排除自身
        QueryWrapper<DnMdmSurvivorshipRule> qw = new QueryWrapper<>();
        qw.eq("entity_id", rule.getEntityId()).eq("attr_code", rule.getAttrCode().trim());
        if (rule.getId() != null) qw.ne("id", rule.getId());
        if (ruleMapper.selectCount(qw) > 0) {
            throw new BusinessException("该实体下属性已配置存活规则：" + rule.getAttrCode());
        }
        rule.setAttrCode(rule.getAttrCode().trim());
        if (rule.getSourcePriority() != null) rule.setSourcePriority(rule.getSourcePriority().trim());
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getId() != null) {
            rule.setUpdatedAt(LocalDateTime.now());
            ruleMapper.updateById(rule);
        } else {
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
            ruleMapper.insert(rule);
        }
        return R.ok(rule);
    }

    @Operation(summary = "删除存活性规则")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        ruleMapper.deleteById(id);
        return R.ok("删除成功");
    }
}
