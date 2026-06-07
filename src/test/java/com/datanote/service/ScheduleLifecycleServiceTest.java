package com.datanote.service;

import com.datanote.domain.orchestration.DolphinService;
import com.datanote.domain.orchestration.ScheduleLifecycleService;
import com.datanote.domain.orchestration.ScheduleTargetType;
import com.datanote.domain.orchestration.TaskDependencyService;
import com.datanote.exception.BusinessException;
import com.datanote.mapper.DnScriptMapper;
import com.datanote.mapper.DnScriptVersionMapper;
import com.datanote.mapper.DnSyncTaskMapper;
import com.datanote.model.DnScript;
import com.datanote.model.DnScriptVersion;
import com.datanote.model.DnSyncTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 调度生命周期服务行为保持单测（R5 结构重构）：验证重构后上下线副作用与重构前一致——
 * DS 字段回写、ONLINE/OFFLINE 置位、脚本本地上线建版本快照+刷依赖、同步不建快照、本地下线不刷依赖、未上线下线报错。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleLifecycleServiceTest {

    @Mock private DolphinService dolphinService;
    @Mock private DnScriptMapper scriptMapper;
    @Mock private DnSyncTaskMapper syncTaskMapper;
    @Mock private DnScriptVersionMapper scriptVersionMapper;
    @Mock private TaskDependencyService taskDependencyService;

    @InjectMocks private ScheduleLifecycleService service;

    @Test
    void onlineRemoteScript_writesDsResultAndOnline() throws Exception {
        DnScript s = new DnScript();
        s.setId(5L); s.setContent("select 1"); s.setScheduleCron("0 0 * * * ?");
        s.setScriptName("t"); s.setScriptType("sql");
        when(scriptMapper.selectById(5L)).thenReturn(s);
        Map<String, Object> ds = new HashMap<>();
        ds.put("dsProjectCode", 1L); ds.put("dsWorkflowCode", 2L);
        ds.put("dsTaskCode", 3L); ds.put("dsScheduleId", 4);
        when(dolphinService.onlineScript(anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), anyInt(), anyInt(), anyInt(), any())).thenReturn(ds);

        Map<String, Object> r = service.onlineRemote(5L, ScheduleTargetType.SCRIPT);
        assertSame(ds, r);

        ArgumentCaptor<DnScript> cap = ArgumentCaptor.forClass(DnScript.class);
        verify(scriptMapper).updateById(cap.capture());
        DnScript u = cap.getValue();
        assertEquals(Long.valueOf(1L), u.getDsProjectCode());
        assertEquals(Long.valueOf(2L), u.getDsWorkflowCode());
        assertEquals(Long.valueOf(3L), u.getDsTaskCode());
        assertEquals(Integer.valueOf(4), u.getDsScheduleId());
        assertEquals("online", u.getScheduleStatus());
    }

    @Test
    void offlineRemoteScript_notOnline_throwsBusiness() {
        DnScript s = new DnScript(); s.setId(5L); // dsWorkflowCode 为 null
        when(scriptMapper.selectById(5L)).thenReturn(s);
        assertThrows(BusinessException.class,
                () -> service.offlineRemote(5L, ScheduleTargetType.SCRIPT));
    }

    @Test
    void onlineLocalScript_createsVersionSnapshotAndRefreshes() {
        DnScript s = new DnScript(); s.setId(5L); s.setContent("select 1");
        when(scriptMapper.selectById(5L)).thenReturn(s);
        when(scriptVersionMapper.selectOne(any())).thenReturn(null);

        service.onlineLocal(5L, ScheduleTargetType.SCRIPT);

        verify(scriptVersionMapper).insert(any(DnScriptVersion.class));
        verify(taskDependencyService).refreshAllDependencies();
        ArgumentCaptor<DnScript> cap = ArgumentCaptor.forClass(DnScript.class);
        verify(scriptMapper).updateById(cap.capture());
        assertEquals("online", cap.getValue().getScheduleStatus());
    }

    @Test
    void onlineLocalSync_setsOnlineRefreshes_noVersionSnapshot() {
        DnSyncTask t = new DnSyncTask(); t.setId(9L);
        when(syncTaskMapper.selectById(9L)).thenReturn(t);

        service.onlineLocal(9L, ScheduleTargetType.SYNC);

        ArgumentCaptor<DnSyncTask> cap = ArgumentCaptor.forClass(DnSyncTask.class);
        verify(syncTaskMapper).updateById(cap.capture());
        assertEquals("online", cap.getValue().getScheduleStatus());
        verify(taskDependencyService).refreshAllDependencies();
        verifyNoInteractions(scriptVersionMapper); // 同步本地上线不建版本快照(与重构前一致)
    }

    @Test
    void offlineLocalScript_setsOffline_noRefresh() {
        service.offlineLocal(5L, ScheduleTargetType.SCRIPT);
        ArgumentCaptor<DnScript> cap = ArgumentCaptor.forClass(DnScript.class);
        verify(scriptMapper).updateById(cap.capture());
        assertEquals("offline", cap.getValue().getScheduleStatus());
        verify(taskDependencyService, never()).refreshAllDependencies(); // 本地下线不刷依赖
    }
}
