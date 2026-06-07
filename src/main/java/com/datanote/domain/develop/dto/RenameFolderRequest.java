package com.datanote.domain.develop.dto;

import lombok.Data;

/**
 * 重命名文件夹请求
 */
@Data
public class RenameFolderRequest {
    private Long id;
    private String folderName;
}
