package com.datanote.domain.develop;

import com.datanote.domain.develop.mapper.DnScriptVersionMapper;
import com.datanote.domain.develop.model.DnScriptVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * listVersions 单测 —— 验证上线版本(永久保留)即便被较新的保存版本挤出"最近10条"也始终可见、可回滚。
 */
@ExtendWith(MockitoExtension.class)
class ScriptServiceVersionTest {

    @Mock private DnScriptVersionMapper scriptVersionMapper;

    private ScriptService svc() {
        return new ScriptService(null, null, null, scriptVersionMapper, null, null, null);
    }

    private DnScriptVersion v(long id, String type, LocalDateTime at) {
        DnScriptVersion x = new DnScriptVersion();
        x.setId(id);
        x.setScriptId(1L);
        x.setVersionType(type);
        x.setCommittedAt(at);
        x.setContent("c" + id);
        return x;
    }

    @Test
    void listVersions_onlineVersionAlwaysIncluded_evenWhenPushedPastRecent10() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        // 最近10条全是保存版本(committed_at 更新)
        List<DnScriptVersion> recent = new ArrayList<>();
        for (int i = 0; i < 10; i++) recent.add(v(100 + i, "save", base.plusDays(10 - i)));
        // 上线版本更老, 已被挤出最近10
        List<DnScriptVersion> online = new ArrayList<>();
        online.add(v(1, "online", base));

        // listVersions 内先查 recent 再查 online
        when(scriptVersionMapper.selectList(any())).thenReturn(recent, online);

        List<DnScriptVersion> all = svc().listVersions(1L);
        assertEquals(11, all.size(), "应含10条最近 + 1条上线");
        assertTrue(all.stream().anyMatch(x -> x.getId() == 1L && "online".equals(x.getVersionType())),
                "上线版本必须出现");
        // 按 committed_at 降序: 最老的上线版本应排在最后
        assertEquals(1L, all.get(all.size() - 1).getId());
    }

    @Test
    void listVersions_noDuplicateWhenOnlineAlsoInRecent() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<DnScriptVersion> recent = new ArrayList<>();
        recent.add(v(5, "online", base.plusDays(2)));
        recent.add(v(4, "save", base.plusDays(1)));
        List<DnScriptVersion> online = new ArrayList<>();
        online.add(v(5, "online", base.plusDays(2)));   // 与 recent 中同一条

        when(scriptVersionMapper.selectList(any())).thenReturn(recent, online);

        List<DnScriptVersion> all = svc().listVersions(1L);
        assertEquals(2, all.size(), "同 id 不应重复");
        assertEquals(5L, all.get(0).getId(), "最新的上线版本排首位");
    }

    @Test
    void listVersions_nullId_returnsEmpty() {
        assertTrue(svc().listVersions(null).isEmpty());
    }
}
