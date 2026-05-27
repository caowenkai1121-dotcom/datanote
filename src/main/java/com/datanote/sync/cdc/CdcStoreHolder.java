package com.datanote.sync.cdc;

import com.datanote.mapper.DnCdcOffsetMapper;
import com.datanote.mapper.DnCdcSchemaHistoryMapper;

/**
 * CDC 存储桥接持有者。
 *
 * <p>Debezium / Kafka-Connect 通过配置项指定 offset / schema-history 存储类的全限定名，
 * 这些类由框架用反射 {@code newInstance()} 创建，<b>不是 Spring Bean</b>，无法 @Autowired 注入 mapper。
 * 因此用本类持有 mapper 的静态引用：Spring 启动阶段调用 {@link #init} 注入，
 * 反射创建的存储类再从这里静态获取 mapper。
 */
public final class CdcStoreHolder {

    /** 由 CdcEngineManager 在 @PostConstruct 注入。 */
    public static volatile DnCdcOffsetMapper offsetMapper;

    /** 由 CdcEngineManager 在 @PostConstruct 注入。 */
    public static volatile DnCdcSchemaHistoryMapper historyMapper;

    private CdcStoreHolder() {
    }

    /**
     * 注入 Spring 管理的 mapper，供 Debezium 反射创建的存储类使用。
     */
    public static void init(DnCdcOffsetMapper om, DnCdcSchemaHistoryMapper hm) {
        offsetMapper = om;
        historyMapper = hm;
    }
}
