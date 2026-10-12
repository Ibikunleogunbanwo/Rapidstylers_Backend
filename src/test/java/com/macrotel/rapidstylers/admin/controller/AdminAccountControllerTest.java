package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.admin.pojo.AdminPasswordData;
import com.macrotel.rapidstylers.admin.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.auth.service.RefreshTokenService;
import com.macrotel.rapidstylers.auth.service.StepUpService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppConstants;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAccountControllerTest {

    private final AdminAccountRepo repo = mock(AdminAccountRepo.class);
    private final AdminAccountController controller =
            new AdminAccountController(repo, new BCryptPasswordEncoder());

    @BeforeEach
    void allowStepUp() {
        // These tests exercise the password policy in isolation; stand in for a
        // satisfied step-up so they aren't blocked by the 403 re-auth gate.
        StepUpService stepUp = mock(StepUpService.class);
        // any() (not anyString()) so it also matches the null actor/password when
        // these pure-DTO tests call the controller outside a real request thread.
        when(stepUp.verify(any(), any())).thenReturn(true);
        controller.setStepUpService(stepUp);
        controller.setRefreshTokenService(refreshTokenService);
    }

    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

    private AdminAccountEntity admin() {
        AdminAccountEntity e = new AdminAccountEntity();
        e.setId(5L);
        e.setEmail("admin@example.com");
        e.setPasswordHash(new BCryptPasswordEncoder().encode("old-strong-pass-1"));
        e.setEnabled(true);
        e.setRole("ADMIN");
        return e;
    }

    @Test
    void changesPasswordForExistingAdmin() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));

        AdminPasswordData data = new AdminPasswordData();
        data.setPassword("New-Str0ng-Passw0rd!");
        ResponseEntity<BaseResponse> result = controller.changeAdminPassword(5L, data);

        assertEquals(AppConstants.SUCCESS_STATUS_CODE, result.getBody().getStatusCode());
        assertNotEquals("old-strong-pass-1", account.getPasswordHash());
        verify(repo).save(account);
        // A reset password usually means a leaked one: end that admin's sessions.
        verify(refreshTokenService).revokeAllForAccount("admin@example.com", "ADMIN");
        verify(refreshTokenService).revokeAllForAccount("admin@example.com", "MANAGER");
    }

    @Test
    void disablingAnAdminEndsTheirSessions() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));

        controller.disableAdmin(5L);

        verify(refreshTokenService).revokeAllForAccount("admin@example.com", "ADMIN");
    }

    @Test
    void rejectsWeakDefaultPassword() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));

        AdminPasswordData data = new AdminPasswordData();
        data.setPassword("ChangeMe_Admin_Password");
        ResponseEntity<BaseResponse> result = controller.changeAdminPassword(5L, data);

        assertEquals(AppConstants.ERROR_STATUS_CODE, result.getBody().getStatusCode());
        assertTrue(result.getBody().getMessage().toLowerCase().contains("weak"));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectsTooShortPasswordBeforePersistence() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));

        AdminPasswordData data = new AdminPasswordData();
        data.setPassword("short");
        ResponseEntity<BaseResponse> result = controller.changeAdminPassword(5L, data);

        assertEquals(AppConstants.ERROR_STATUS_CODE, result.getBody().getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void returnsErrorWhenAdminNotFound() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        AdminPasswordData data = new AdminPasswordData();
        data.setPassword("New-Str0ng-Passw0rd!");
        ResponseEntity<BaseResponse> result = controller.changeAdminPassword(99L, data);

        assertEquals(AppConstants.ERROR_STATUS_CODE, result.getBody().getStatusCode());
        verify(repo, never()).save(any());
    }

    private AdminAccountEntity manager() {
        AdminAccountEntity e = admin();
        e.setId(7L);
        e.setEmail("manager@example.com");
        e.setRole("MANAGER");
        return e;
    }

    private com.macrotel.rapidstylers.admin.pojo.AdminAccountData newAccount(String role) {
        com.macrotel.rapidstylers.admin.pojo.AdminAccountData data = new com.macrotel.rapidstylers.admin.pojo.AdminAccountData();
        data.setEmail("New@Example.com");
        data.setPassword("New-Str0ng-Passw0rd!");
        data.setRole(role);
        return data;
    }

    private static com.macrotel.rapidstylers.admin.pojo.AdminRoleData role(String role) {
        com.macrotel.rapidstylers.admin.pojo.AdminRoleData data = new com.macrotel.rapidstylers.admin.pojo.AdminRoleData();
        data.setRole(role);
        return data;
    }

    @Test
    void createDefaultsToOwner() {
        when(repo.save(any())).thenAnswer(call -> call.getArgument(0));

        ResponseEntity<BaseResponse> result = controller.createAdmin(newAccount(null));

        assertEquals(AppConstants.SUCCESS_STATUS_CODE, result.getBody().getStatusCode());
        assertEquals("ADMIN", ((java.util.Map<?, ?>) result.getBody().getData()).get("role"));
    }

    @Test
    void createsAManager() {
        when(repo.save(any())).thenAnswer(call -> call.getArgument(0));

        ResponseEntity<BaseResponse> result = controller.createAdmin(newAccount("manager"));

        java.util.Map<?, ?> view = (java.util.Map<?, ?>) result.getBody().getData();
        assertEquals("MANAGER", view.get("role"));
        assertEquals("new@example.com", view.get("email"));
    }

    @Test
    void roleValuesAreLimitedToAdminAndManager() {
        javax.validation.Validator validator = javax.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertTrue(validator.validate(newAccount("OWNER")).stream().anyMatch(v -> v.getPropertyPath().toString().equals("role")));
        assertTrue(validator.validate(newAccount("MANAGER")).isEmpty());
        assertTrue(validator.validate(newAccount(null)).isEmpty());
        assertTrue(!validator.validate(role("SUPERUSER")).isEmpty());
        assertTrue(!validator.validate(role("")).isEmpty());
        assertTrue(validator.validate(role("admin")).isEmpty());
    }

    @Test
    void listShowsEachAccountsRole() {
        when(repo.findAll()).thenReturn(java.util.List.of(admin(), manager()));

        java.util.List<?> rows = (java.util.List<?>) controller.listAdmins().getBody().getData();

        assertEquals("ADMIN", ((java.util.Map<?, ?>) rows.get(0)).get("role"));
        assertEquals("MANAGER", ((java.util.Map<?, ?>) rows.get(1)).get("role"));
    }

    @Test
    void promotingAManagerSignsThemOutEverywhere() {
        AdminAccountEntity account = manager();
        when(repo.findById(7L)).thenReturn(Optional.of(account));

        ResponseEntity<BaseResponse> result = controller.changeAdminRole(7L, role("ADMIN"));

        assertEquals(AppConstants.SUCCESS_STATUS_CODE, result.getBody().getStatusCode());
        assertEquals("ADMIN", account.getRole());
        verify(repo).save(account);
        // Old sessions carry the old role: both kinds are ended.
        verify(refreshTokenService).revokeAllForAccount("manager@example.com", "MANAGER");
        verify(refreshTokenService).revokeAllForAccount("manager@example.com", "ADMIN");
    }

    @Test
    void demotingAnOwnerWhenAnotherOwnerRemains() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));
        when(repo.countByRoleIgnoreCaseAndEnabledTrue("ADMIN")).thenReturn(2L);

        ResponseEntity<BaseResponse> result = controller.changeAdminRole(5L, role("MANAGER"));

        assertEquals(AppConstants.SUCCESS_STATUS_CODE, result.getBody().getStatusCode());
        assertEquals("MANAGER", account.getRole());
    }

    @Test
    void theLastOwnerCannotBeDemoted() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));
        when(repo.countByRoleIgnoreCaseAndEnabledTrue("ADMIN")).thenReturn(1L);

        ResponseEntity<BaseResponse> result = controller.changeAdminRole(5L, role("MANAGER"));

        assertEquals(AppConstants.ERROR_STATUS_CODE, result.getBody().getStatusCode());
        assertEquals("ADMIN", account.getRole());
        verify(repo, never()).save(any());
    }

    @Test
    void anOwnerCannotChangeTheirOwnRole() {
        AdminAccountEntity account = admin();
        when(repo.findById(5L)).thenReturn(Optional.of(account));
        when(repo.countByRoleIgnoreCaseAndEnabledTrue("ADMIN")).thenReturn(3L);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute("accountId", "admin@example.com");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            ResponseEntity<BaseResponse> result = controller.changeAdminRole(5L, role("MANAGER"));
            assertEquals(AppConstants.ERROR_STATUS_CODE, result.getBody().getStatusCode());
            assertEquals("ADMIN", account.getRole());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void roleChangeNeedsStepUp() {
        StepUpService refusing = mock(StepUpService.class);
        when(refusing.verify(any(), any())).thenReturn(false);
        controller.setStepUpService(refusing);

        ResponseEntity<BaseResponse> result = controller.changeAdminRole(7L, role("ADMIN"));

        assertEquals(403, result.getStatusCodeValue());
        verify(repo, never()).save(any());
    }
}
