package com.datanote.domain.datamodel;

import com.datanote.common.model.R;
import com.datanote.domain.datamodel.model.DnModel;
import com.datanote.platform.iam.DataAclService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataModelControllerTest {

    @Test
    void listModels_filtersDeniedImmutableListWithoutMutatingSource() {
        DataModelService service = mock(DataModelService.class);
        DataAclService dataAclService = mock(DataAclService.class);
        com.datanote.domain.approval.ApprovalService unifiedApproval = mock(com.datanote.domain.approval.ApprovalService.class);
        DataModelController controller = new DataModelController(service, dataAclService, unifiedApproval);

        DnModel denied = new DnModel();
        denied.setId(2L);
        when(service.listModels(null, null, null)).thenReturn(Collections.singletonList(denied));
        when(dataAclService.deniedIds("MODEL")).thenReturn(Collections.singleton("2"));

        R<List<DnModel>> response = controller.listModels(null, null, null);

        assertNotNull(response.getData());
        assertTrue(response.getData().isEmpty());
    }
}
