package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.consumption.mapper.DnConsumptionLogMapper;
import com.datanote.domain.consumption.mapper.DnDatasetMapper;
import com.datanote.domain.consumption.model.DnConsumptionLog;
import com.datanote.domain.consumption.model.DnDataset;
import com.datanote.domain.governance.MaskingService;
import com.datanote.domain.governance.SqlMaskRewriter;
import com.datanote.domain.integration.HiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据集/数据产品服务 —— 把精选 SQL 注册为可复用查询；执行时**复用治理**：脱敏改写(fail-closed)+消费审计，
 * 不另造查询通道、不绕脱敏。只执行 status=1 启用数据集的只读 SELECT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DnDatasetMapper datasetMapper;
    private final DnConsumptionLogMapper logMapper;
    private final MaskingService maskingService;
    private final HiveService hiveService;

    public List<DnDataset> list(String keyword) {
        QueryWrapper<DnDataset> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            qw.and(w -> w.like("dataset_name", keyword.trim()).or().like("dataset_code", keyword.trim()));
        }
        qw.orderByDesc("updated_at");
        return datasetMapper.selectList(qw);
    }

    public DnDataset get(Long id) { return datasetMapper.selectById(id); }

    public DnDataset save(DnDataset ds) {
        if (ds.getDatasetCode() == null || ds.getDatasetCode().trim().isEmpty()) throw new BusinessException("数据集编码不能为空");
        if (ds.getQuerySql() == null || ds.getQuerySql().trim().isEmpty()) throw new BusinessException("查询SQL不能为空");
        validateReadOnly(ds.getQuerySql());
        if (ds.getStatus() == null) ds.setStatus(1);
        LocalDateTime now = LocalDateTime.now();
        ds.setUpdatedAt(now);
        if (ds.getId() == null) { ds.setCreatedAt(now); datasetMapper.insert(ds); }
        else datasetMapper.updateById(ds);
        return ds;
    }

    public void delete(Long id) { datasetMapper.deleteById(id); }

    /** 执行数据集查询：脱敏改写(fail-closed) + 审计。返回 {columns, rows, rowCount}。 */
    public Map<String, Object> query(Long id, String consumer) {
        DnDataset ds = datasetMapper.selectById(id);
        if (ds == null) throw new BusinessException("数据集不存在: " + id);
        if (ds.getStatus() == null || ds.getStatus() != 1) throw new BusinessException("数据集已下线，不可消费: " + ds.getDatasetCode());
        validateReadOnly(ds.getQuerySql());

        long start = System.currentTimeMillis();
        String masked;
        try {
            List<SqlMaskRewriter.ColumnMask> masks = maskingService.resolveColumnMasks();
            List<SqlMaskRewriter.RowFilter> filters = maskingService.resolveRowFilters(consumer == null ? "default" : consumer);
            masked = SqlMaskRewriter.rewrite(ds.getQuerySql(), ds.getDefaultDb(), masks, filters);
        } catch (SqlMaskRewriter.MaskRewriteException e) {
            audit(consumer, ds.getDatasetCode(), 0L, System.currentTimeMillis() - start, false, "脱敏改写失败,拒绝执行");
            throw new BusinessException("脱敏改写失败，已拒绝执行(fail-closed): " + e.getMessage());
        }
        try {
            Map<String, Object> r = hiveService.executeSQL(masked);
            Object rc = r.get("rowCount");
            long rows = rc instanceof Number ? ((Number) rc).longValue() : 0L;
            audit(consumer, ds.getDatasetCode(), rows, System.currentTimeMillis() - start, true, "查询成功");
            return r;
        } catch (Exception e) {
            audit(consumer, ds.getDatasetCode(), 0L, System.currentTimeMillis() - start, false, e.getMessage());
            throw new BusinessException("数据集查询失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void audit(String consumer, String code, Long rows, Long durMs, boolean ok, String detail) {
        try {
            DnConsumptionLog l = new DnConsumptionLog();
            l.setConsumer(consumer == null ? "default" : consumer);
            l.setTargetType("DATASET"); l.setTargetCode(code); l.setAction("QUERY");
            l.setRowCount(rows); l.setDurationMs(durMs); l.setSuccess(ok ? 1 : 0);
            l.setDetail(detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail));
            l.setCreatedAt(LocalDateTime.now());
            logMapper.insert(l);
        } catch (Exception e) { log.warn("数据集消费审计写入失败: {}", e.getMessage()); }
    }

    // ---- 纯函数(可单测) ----

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 只读校验：去注释后必须以 SELECT/WITH 开头，否则抛 BusinessException(防写操作)。 */
    static void validateReadOnly(String sql) {
        String c = sql == null ? "" : sql;
        c = LINE_COMMENT.matcher(c).replaceAll(" ");
        c = BLOCK_COMMENT.matcher(c).replaceAll(" ").trim();
        if (c.isEmpty()) throw new BusinessException("查询SQL不能为空");
        String u = c.toUpperCase();
        if (!(u.startsWith("SELECT") || u.startsWith("WITH"))) {
            throw new BusinessException("数据集仅允许只读 SELECT/WITH 查询");
        }
    }
}
