package com.datanote.domain.develop;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnScriptChangeMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.develop.model.DnScriptChange;
import com.datanote.domain.orchestration.ScheduleLifecycleService;
import com.datanote.domain.orchestration.ScheduleTargetType;
import com.datanote.platform.notify.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 脚本上线审批服务单测 —— 验证治理护栏: 提交校验、禁自批(SoD)、状态机、批准时执行上线/下线。
 * 单测无 Security 上下文, CurrentUserUtil.currentUser() 恒为 "anonymous"。
 */
@ExtendWith(MockitoExtension.class)
class ScriptApprovalServiceTest {

    @Mock private DnScriptChangeMapper changeMapper;
    @Mock private DnScriptMapper scriptMapper;
    @Mock private ScheduleLifecycleService scheduleLifecycleService;
    @Mock private NotificationService notificationService;

    private ScriptApprovalService svc() {
        return new ScriptApprovalService(changeMapper, scriptMapper, scheduleLifecycleService, notificationService);
    }

    private DnScript script(String status, String content) {
        DnScript s = new DnScript();
        s.setId(1L);
        s.setScriptName("etl_x");
        s.setScheduleStatus(status);
        s.setContent(content);
        return s;
    }

    @Test
    void submit_scriptNotFound_throws() {
        when(scriptMapper.selectById(9L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> svc().submit(9L, "ONLINE", null));
    }

    @Test
    void submit_online_emptyContent_throws() {
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "   "));
        assertThrows(BusinessException.class, () -> svc().submit(1L, "ONLINE", "r"));
    }

    @Test
    void submit_online_alreadyOnline_throws() {
        when(scriptMapper.selectById(1L)).thenReturn(script("online", "select 1"));
        assertThrows(BusinessException.class, () -> svc().submit(1L, "ONLINE", "r"));
    }

    @Test
    void submit_offline_whenNotOnline_throws() {
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 1"));
        assertThrows(BusinessException.class, () -> svc().submit(1L, "OFFLINE", "r"));
    }

    @Test
    void submit_duplicatePending_throws() {
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 1"));
        when(changeMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> svc().submit(1L, "ONLINE", "r"));
        verify(changeMapper, never()).insert(any());
    }

    @Test
    void submit_ok_insertsPendingChangeWithSnapshot() {
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 1"));
        when(changeMapper.selectCount(any())).thenReturn(0L);
        DnScriptChange c = svc().submit(1L, "ONLINE", "上线理由");
        assertEquals("pending", c.getStatus());
        assertEquals("ONLINE", c.getChangeType());
        assertEquals("select 1", c.getPayloadJson());   // 提交时内容快照
        assertEquals("anonymous", c.getRequestedBy());
        verify(changeMapper).insert(any(DnScriptChange.class));
    }

    @Test
    void review_notFound_throws() {
        when(changeMapper.selectById(5L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> svc().review(5L, "approved", null));
    }

    @Test
    void review_alreadyDecided_throws() {
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("approved");
        when(changeMapper.selectById(5L)).thenReturn(c);
        assertThrows(BusinessException.class, () -> svc().review(5L, "approved", null));
    }

    @Test
    void review_selfApproval_isAllowed() {
        // 允许自审自批: 自己提交的可由自己审批通过(有权限即可)
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setChangeType("ONLINE");
        c.setScriptId(1L);
        c.setRequestedBy("anonymous");   // 与单测中的 currentUser 相同 → 自审, 现放行
        c.setPayloadJson("select 1");    // 快照与现内容一致 → 无漂移
        when(changeMapper.selectById(5L)).thenReturn(c);
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 1"));
        DnScriptChange out = svc().review(5L, "approved", "self ok"); // 不抛异常
        assertEquals("approved", out.getStatus());
    }

    @Test
    void review_approveOnline_executesOnline() {
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setChangeType("ONLINE");
        c.setScriptId(1L);
        c.setRequestedBy("alice");       // 非本人 → 可审批
        c.setPayloadJson("select 1");    // 快照与现内容一致 → 无漂移
        when(changeMapper.selectById(5L)).thenReturn(c);
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 1"));
        DnScriptChange out = svc().review(5L, "approved", "通过");
        assertEquals("approved", out.getStatus());
        assertEquals("anonymous", out.getReviewer());
        verify(scheduleLifecycleService).onlineLocalAfterApproval(eq(1L), eq(ScheduleTargetType.SCRIPT));
    }

    @Test
    void review_approveOnline_contentDrift_throws() {
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setChangeType("ONLINE");
        c.setScriptId(1L);
        c.setRequestedBy("alice");
        c.setPayloadJson("select 1");    // 提交时快照
        when(changeMapper.selectById(5L)).thenReturn(c);
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 2"));  // 现内容已变
        assertThrows(BusinessException.class, () -> svc().review(5L, "approved", "通过"));
        verify(scheduleLifecycleService, never()).onlineLocalAfterApproval(any(), any());
    }

    @Test
    void review_approveOffline_ignoresContentDrift() {
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setChangeType("OFFLINE");
        c.setScriptId(1L);
        c.setRequestedBy("alice");
        c.setPayloadJson("old");         // 下线不比对内容漂移
        when(changeMapper.selectById(5L)).thenReturn(c);
        when(scriptMapper.selectById(1L)).thenReturn(script("online", "new"));
        DnScriptChange out = svc().review(5L, "approved", "通过");
        assertEquals("approved", out.getStatus());
        verify(scheduleLifecycleService).offlineLocal(eq(1L), eq(ScheduleTargetType.SCRIPT));
    }

    @Test
    void withdraw_notPending_throws() {
        DnScriptChange c = new DnScriptChange();
        c.setId(7L);
        c.setStatus("approved");
        when(changeMapper.selectById(7L)).thenReturn(c);
        assertThrows(BusinessException.class, () -> svc().withdraw(7L));
        verify(changeMapper, never()).updateById(any());
    }

    @Test
    void withdraw_notOwner_throws() {
        DnScriptChange c = new DnScriptChange();
        c.setId(7L);
        c.setStatus("pending");
        c.setRequestedBy("alice");      // 非本人(anonymous)且非 admin → 拒绝
        when(changeMapper.selectById(7L)).thenReturn(c);
        assertThrows(BusinessException.class, () -> svc().withdraw(7L));
        verify(changeMapper, never()).updateById(any());
    }

    @Test
    void withdraw_ownPending_setsWithdrawn() {
        DnScriptChange c = new DnScriptChange();
        c.setId(7L);
        c.setStatus("pending");
        c.setRequestedBy("anonymous");  // 与单测 currentUser 相同 → 可撤回
        when(changeMapper.selectById(7L)).thenReturn(c);
        DnScriptChange out = svc().withdraw(7L);
        assertEquals("withdrawn", out.getStatus());
        assertNotNull(out.getDecidedAt());
        verify(changeMapper).updateById(any(DnScriptChange.class));
    }

    @Test
    void review_rejectWithoutReason_throws() {
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setRequestedBy("alice");       // 非本人 → 越过禁自批, 命中驳回原因校验
        when(changeMapper.selectById(5L)).thenReturn(c);
        assertThrows(BusinessException.class, () -> svc().review(5L, "rejected", "   "));
        verify(changeMapper, never()).updateById(any());
    }

    @Test
    void review_reject_doesNotExecute() {
        DnScriptChange c = new DnScriptChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setChangeType("ONLINE");
        c.setScriptId(1L);
        c.setRequestedBy("alice");
        when(changeMapper.selectById(5L)).thenReturn(c);
        when(scriptMapper.selectById(1L)).thenReturn(script("draft", "select 1"));
        DnScriptChange out = svc().review(5L, "rejected", "驳回");
        assertEquals("rejected", out.getStatus());
        verify(scheduleLifecycleService, never()).onlineLocalAfterApproval(any(), any());
        verify(scheduleLifecycleService, never()).offlineLocal(any(), any());
    }
}
