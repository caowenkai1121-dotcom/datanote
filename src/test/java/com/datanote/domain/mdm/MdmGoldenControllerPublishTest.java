package com.datanote.domain.mdm;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.mdm.mapper.DnMdmGoldenHistoryMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MdmGoldenControllerPublishTest {

    @Mock private DnMdmGoldenRecordMapper goldenMapper;
    @Mock private DnMdmGoldenHistoryMapper historyMapper;
    @Mock private com.datanote.domain.mdm.mapper.DnMdmEntityMapper entityMapper;
    @Mock private com.datanote.domain.mdm.mapper.DnMdmSurvivorshipRuleMapper ruleMapper;
    @Mock private MdmMatchService matchService;
    @Mock private MdmPublishService mdmPublishService;
    @Mock private MdmService mdmService;

    @Test
    void publish_draftRecord_mustGoThroughApproval() {
        DnMdmGoldenRecord record = new DnMdmGoldenRecord();
        record.setId(9L);
        record.setStatus("draft");
        when(goldenMapper.selectById(9L)).thenReturn(record);

        MdmGoldenController controller = new MdmGoldenController(goldenMapper, historyMapper, entityMapper,
                ruleMapper, matchService, mdmPublishService, mdmService);

        assertThrows(BusinessException.class, () -> controller.publish(9L));
        verify(goldenMapper, never()).updateById(any(DnMdmGoldenRecord.class));
    }
}
