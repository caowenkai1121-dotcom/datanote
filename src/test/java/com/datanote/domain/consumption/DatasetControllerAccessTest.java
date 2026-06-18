package com.datanote.domain.consumption;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.LinkedHashMap;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasetControllerAccessTest {

    @Mock private DatasetService datasetService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void query_usesCurrentUserInsteadOfConsumerParam() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "n/a", Collections.emptyList()));
        when(datasetService.query(3L, "bob")).thenReturn(new LinkedHashMap<>());

        new DatasetController(datasetService).query(3L, "alice");

        verify(datasetService).query(3L, "bob");
    }
}
