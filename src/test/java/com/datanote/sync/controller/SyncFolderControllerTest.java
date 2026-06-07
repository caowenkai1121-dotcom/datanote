package com.datanote.domain.integration.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.domain.integration.mapper.DnSyncFolderMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.integration.model.DnSyncFolder;
import com.datanote.domain.integration.model.DnSyncJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncFolderControllerTest {

    @Test
    void deleteRemovesDescendantFoldersAndMovesTheirJobsToRoot() {
        DnSyncFolderMapper folderMapper = mock(DnSyncFolderMapper.class);
        DnSyncJobMapper syncJobMapper = mock(DnSyncJobMapper.class);
        SyncFolderController controller = new SyncFolderController(folderMapper, syncJobMapper);

        when(folderMapper.selectList(null)).thenReturn(Arrays.asList(
                folder(1L, 0L),
                folder(2L, 1L),
                folder(3L, 2L),
                folder(4L, 0L)));

        controller.delete(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(syncJobMapper).update(isNull(), any(UpdateWrapper.class));
        verify(folderMapper).deleteBatchIds(idsCaptor.capture());

        Collection<Long> deletedIds = idsCaptor.getValue();
        assertTrue(deletedIds.contains(1L));
        assertTrue(deletedIds.contains(2L));
        assertTrue(deletedIds.contains(3L));
        assertTrue(!deletedIds.contains(4L));
    }

    private static DnSyncFolder folder(Long id, Long parentId) {
        DnSyncFolder folder = new DnSyncFolder();
        folder.setId(id);
        folder.setParentId(parentId);
        folder.setFolderName("f" + id);
        return folder;
    }
}
