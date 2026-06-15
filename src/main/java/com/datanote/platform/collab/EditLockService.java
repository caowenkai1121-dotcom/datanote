package com.datanote.platform.collab;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.iam.CurrentUserUtil;
import com.datanote.platform.iam.mapper.DnEditLockMapper;
import com.datanote.platform.iam.model.DnEditLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用资源独占编辑锁服务(悲观锁 + 心跳超时自动释放)。
 * acquire: 资源空闲/锁已超时/锁是自己 → 抢到; 否则返回当前持有者。
 * heartbeat: 续锁(防超时)。release: 仅持有者可释放。
 * TTL 内无心跳即视为已释放(防用户关页/掉线锁死)。
 */
@Service
@RequiredArgsConstructor
public class EditLockService {

    private final DnEditLockMapper lockMapper;

    /** 心跳超时(秒): 超过则锁失效, 他人可抢。前端心跳间隔应明显小于此值(建议 30s)。 */
    public static final long TTL_SECONDS = 90;

    // 单实例内按资源键串行化 acquire, 避免同实例并发抢锁竞态(跨实例由 DB 唯一键兜底)
    private final Map<String, Object> stripes = new ConcurrentHashMap<>();

    private Object stripe(String key) { return stripes.computeIfAbsent(key, k -> new Object()); }

    private boolean expired(DnEditLock l, LocalDateTime now) {
        return l.getHeartbeatAt() == null || l.getHeartbeatAt().isBefore(now.minusSeconds(TTL_SECONDS));
    }

    /** 尝试获取编辑锁。返回 {ok, holder, since, self}。 */
    public Map<String, Object> acquire(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        Map<String, Object> r = new HashMap<>();
        synchronized (stripe(type + ":" + id)) {
            LocalDateTime now = LocalDateTime.now();
            DnEditLock cur = lockMapper.selectOne(new QueryWrapper<DnEditLock>()
                    .eq("resource_type", type).eq("resource_id", id).last("LIMIT 1"));
            boolean mine = cur != null && me != null && me.equals(cur.getHolder());
            if (cur != null && !mine && !expired(cur, now)) {
                r.put("ok", false); r.put("holder", cur.getHolder()); r.put("since", cur.getAcquiredAt());
                return r;
            }
            if (cur == null) {
                DnEditLock l = new DnEditLock();
                l.setResourceType(type); l.setResourceId(id); l.setHolder(me);
                l.setAcquiredAt(now); l.setHeartbeatAt(now);
                try { lockMapper.insert(l); } catch (Exception e) {
                    // 跨实例唯一键竞态: 重读, 若被他人抢则失败
                    DnEditLock again = lockMapper.selectOne(new QueryWrapper<DnEditLock>()
                            .eq("resource_type", type).eq("resource_id", id).last("LIMIT 1"));
                    if (again != null && me != null && !me.equals(again.getHolder()) && !expired(again, now)) {
                        r.put("ok", false); r.put("holder", again.getHolder()); r.put("since", again.getAcquiredAt());
                        return r;
                    }
                }
            } else {
                // 空闲(超时)或本人 → 接管/续约
                cur.setHolder(me);
                if (!mine) cur.setAcquiredAt(now);   // 换人才重置获锁时间
                cur.setHeartbeatAt(now);
                lockMapper.updateById(cur);
            }
            r.put("ok", true); r.put("self", true);
            return r;
        }
    }

    /** 心跳续锁(仅持有者)。返回是否仍持有(false 表示锁已被他人接管, 前端应转只读)。 */
    public boolean heartbeat(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        if (me == null) return false;
        int n = lockMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnEditLock>()
                .eq("resource_type", type).eq("resource_id", id).eq("holder", me)
                .set("heartbeat_at", LocalDateTime.now()));
        return n > 0;
    }

    /** 释放编辑锁(仅持有者)。 */
    public void release(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        if (me == null) return;
        lockMapper.delete(new QueryWrapper<DnEditLock>()
                .eq("resource_type", type).eq("resource_id", id).eq("holder", me));
    }

    /** 当前有效持有者(无/已超时返回 null)。 */
    public String currentHolder(String type, String id) {
        DnEditLock cur = lockMapper.selectOne(new QueryWrapper<DnEditLock>()
                .eq("resource_type", type).eq("resource_id", id).last("LIMIT 1"));
        if (cur == null || expired(cur, LocalDateTime.now())) return null;
        return cur.getHolder();
    }

    /** 服务端保存前断言: 当前用户必须持锁(他人持锁则拒)。供写操作兜底, 防绕过前端。 */
    public void assertHeld(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        String holder = currentHolder(type, id);
        if (holder != null && me != null && !holder.equals(me)) {
            throw new com.datanote.common.exception.BusinessException("「" + holder + "」正在编辑该内容, 你暂时无法保存; 请稍后再试");
        }
    }
}
