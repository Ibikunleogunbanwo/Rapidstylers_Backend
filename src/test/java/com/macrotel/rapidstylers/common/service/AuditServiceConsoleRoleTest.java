package com.macrotel.rapidstylers.common.service;

import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import com.macrotel.rapidstylers.common.repo.AuditLogRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Admin-console actions say whether an owner or a manager acted. */
class AuditServiceConsoleRoleTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void signedIn(String accountId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("accountId", accountId);
        request.setAttribute("role", role);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static AuditLogEntity recorded(String actorId, String actorRole) {
        AuditService service = new AuditService();
        service.auditLogRepo = mock(AuditLogRepo.class);
        service.audit(actorId, actorRole, "MODERATE_REVIEW", "REVIEW", "1", "APPROVED");
        ArgumentCaptor<AuditLogEntity> row = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(service.auditLogRepo).save(row.capture());
        return row.getValue();
    }

    @Test
    void aManagerActionIsRecordedAsManagerWithTheirEmail() {
        signedIn("desk@rapidstylers.ca", "MANAGER");
        AuditLogEntity row = recorded("desk@rapidstylers.ca", "ADMIN");
        assertEquals("MANAGER", row.getActorRole());
        assertEquals("desk@rapidstylers.ca", row.getActorId());
    }

    @Test
    void anOwnerActionStaysAdmin() {
        signedIn("owner@rapidstylers.ca", "ADMIN");
        assertEquals("ADMIN", recorded("owner@rapidstylers.ca", "ADMIN").getActorRole());
    }

    @Test
    void outsideARequestOrForAnotherActorTheRoleIsKept() {
        assertEquals("ADMIN", recorded("owner@rapidstylers.ca", "ADMIN").getActorRole());
        clearRequest();
        signedIn("desk@rapidstylers.ca", "MANAGER");
        assertEquals("ADMIN", recorded("someone-else@rapidstylers.ca", "ADMIN").getActorRole());
        clearRequest();
        signedIn("c1", "MANAGER");
        assertEquals("CUSTOMER", recorded("c1", "CUSTOMER").getActorRole());
    }
}
