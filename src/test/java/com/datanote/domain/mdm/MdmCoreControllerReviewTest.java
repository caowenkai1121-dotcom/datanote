package com.datanote.domain.mdm;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.mdm.mapper.*;
import com.datanote.domain.mdm.model.DnMdmChangeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MdmCoreControllerReviewTest {

    @Mock private DnMdmDomainMapper domainMapper;
    @Mock private DnMdmEntityMapper entityMapper;
    @Mock private DnMdmAttributeMapper attributeMapper;
    @Mock private MdmService mdmService;
    @Mock private DnMdmChangeRequestMapper changeMapper;
    @Mock private DnMdmGoldenRecordMapper goldenMapper;
    @Mock private MdmMatchService matchService;
    @Mock private com.datanote.platform.notify.NotificationService notificationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reject_selfSubmittedAdminRequest_isDeniedBeforeClaim() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", Collections.emptyList()));
        DnMdmChangeRequest req = new DnMdmChangeRequest();
        req.setId(5L);
        req.setStatus("pending");
        req.setRequestedBy("admin");
        when(changeMapper.selectById(5L)).thenReturn(req);
        DnMdmChangeRequest body = new DnMdmChangeRequest();
        body.setReviewComment("no");

        MdmCoreController controller = new MdmCoreController(domainMapper, entityMapper, attributeMapper,
                mdmService, changeMapper, goldenMapper, matchService, new ObjectMapper(), notificationService);

        assertThrows(BusinessException.class, () -> controller.reject(5L, body));
        verify(changeMapper, never()).update(any(), any());
    }
}
