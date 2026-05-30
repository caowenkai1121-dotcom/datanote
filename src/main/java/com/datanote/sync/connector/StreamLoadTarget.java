package com.datanote.sync.connector;

/** DS-M4：Doris/StarRocks Stream Load 目标端点（HTTP 端口 + 解密后凭据）。 */
public final class StreamLoadTarget {
    public final String host;
    public final int httpPort;
    public final String user;
    public final String password;

    public StreamLoadTarget(String host, int httpPort, String user, String password) {
        this.host = host;
        this.httpPort = httpPort;
        this.user = user;
        this.password = password;
    }
}
