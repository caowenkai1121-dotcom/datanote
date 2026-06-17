package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 数据中心文件（dn_ai_file）。用户上传/下载; agent 生成文件复用。磁盘存 stored_name(uuid), 原名仅显示。 */
@Data
@TableName("dn_ai_file")
public class DnAiFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileName;
    private String storedName;
    private String contentType;
    private Long sizeBytes;
    private String owner;
    private String source;
    private String sessionId;
    private LocalDateTime createdAt;
    /** 文档知识库索引状态(特性B): null/none=不适用; pending/indexing/indexed/failed。 */
    private String indexStatus;
    /** 已入库向量块数(特性B)。 */
    private Integer chunkCount;
}
