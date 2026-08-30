package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.config.AppConstants;
import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.pojo.AdminPasswordData;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

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
}