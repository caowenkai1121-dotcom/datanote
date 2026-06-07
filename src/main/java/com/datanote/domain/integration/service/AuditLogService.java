package com.datanote.domain.integration.service;

import com.datanote.domain.integration.mapper.DnSyncJobAuditMapper;
import com.datanote.domain.integration.model.DnSyncJobAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final DnSyncJobAuditMapper auditMapper;

    public void record(Long jobId, String jobName, String op, String detail) {
        try {
            DnSyncJobAudit a = new DnSyncJobAudit();
            a.setJobId(jobId);
            a.setJobName(jobName);
            a.setOperationType(op);
            a.setChangeDetail(detail == null ? null : (detail.length() > 60000 ? detail.substring(0, 60000) : detail));
            auditMapper.insert(a);
        } catch (Exception e) {
            log.warn("审计记录失败 jobId={} op={}", jobId, op, e);
        }
    }
}
