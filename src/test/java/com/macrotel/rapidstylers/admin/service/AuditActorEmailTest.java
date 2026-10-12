package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.admin.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.admin.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin rows in the activity log say which admin account acted. */
class AuditActorEmailTest {

    @Test
    void adminRowsCarryTheAdminAccountEmail() {
        AdminAccountRepo repo = mock(AdminAccountRepo.class);
        AdminAccountEntity ops = new AdminAccountEntity();
        ops.setId(5L);
        ops.setEmail("ops@rapidstylers.ca");
        when(repo.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repo.findByEmailIgnoreCase("OPS@rapidstylers.ca")).thenReturn(Optional.of(ops));
        when(repo.findById(5L)).thenReturn(Optional.of(ops));
        AdminService adminService = new AdminService();
        adminService.adminRecords = new AdminRecords(repo);

        AuditLogEntity byToken = new AuditLogEntity("OPS@rapidstylers.ca", "ADMIN", "UPDATE_COMMISSION", "SETTINGS", "k", "12.0 -> 15.0");
        AuditLogEntity again = new AuditLogEntity("OPS@rapidstylers.ca", "ADMIN", "MODERATE_REVIEW", "REVIEW", "1", "APPROVED");
        AuditLogEntity byRowId = new AuditLogEntity("5", "ADMIN", "MODERATE_REVIEW", "REVIEW", "2", "APPROVED");
        AuditLogEntity unknownAdmin = new AuditLogEntity("admin", "ADMIN", "X", "Y", "3", "");
        AuditLogEntity system = new AuditLogEntity("system", "SYSTEM", "PAYMENT_RECONCILIATION", "PAYMENT", "4", "");
        AuditLogEntity customer = new AuditLogEntity("U1@x.com", "CUSTOMER", "CREATE_REVIEW", "REVIEW", "5", "");

        adminService.withActorEmails(List.of(byToken, again, byRowId, unknownAdmin, system, customer));

        assertEquals("ops@rapidstylers.ca", byToken.getActorEmail());
        assertEquals("ops@rapidstylers.ca", again.getActorEmail());
        assertEquals("ops@rapidstylers.ca", byRowId.getActorEmail());
        assertNull(unknownAdmin.getActorEmail());
        assertNull(system.getActorEmail());
        assertNull(customer.getActorEmail());
        // One lookup per distinct admin, not per row.
        verify(repo, times(1)).findByEmailIgnoreCase("OPS@rapidstylers.ca");
        // The row itself is unchanged.
        assertEquals("OPS@rapidstylers.ca", byToken.getActorId());
    }

    @Test
    void managerRowsCarryTheManagerEmailToo() {
        AdminAccountRepo repo = mock(AdminAccountRepo.class);
        AdminAccountEntity manager = new AdminAccountEntity();
        manager.setEmail("desk@rapidstylers.ca");
        manager.setRole("MANAGER");
        when(repo.findByEmailIgnoreCase("desk@rapidstylers.ca")).thenReturn(Optional.of(manager));
        AdminService adminService = new AdminService();
        adminService.adminRecords = new AdminRecords(repo);
        AuditLogEntity row = new AuditLogEntity("desk@rapidstylers.ca", "MANAGER", "MODERATE_REVIEW", "REVIEW", "1", "APPROVED");

        adminService.withActorEmails(List.of(row));

        assertEquals("desk@rapidstylers.ca", row.getActorEmail());
    }
}
