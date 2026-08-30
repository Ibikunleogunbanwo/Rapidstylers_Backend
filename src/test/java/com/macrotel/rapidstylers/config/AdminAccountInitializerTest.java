package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAccountInitializerTest {

    @Test
    void weakPasswordsAreTreatedAsWeak() {
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("ChangeMe_Admin_Password"));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("changeme"));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("admin123"));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("password"));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("rapidstylers"));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("short"));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword(null));
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword(""));
        // separators and whitespace are normalized before the substring match
        assertTrue(AdminAccountInitializer.isWeakBootstrapPassword("C h a n g e M e"));
    }

    @Test
    void strongPasswordsAreAccepted() {
        assertFalse(AdminAccountInitializer.isWeakBootstrapPassword("asdfghjklzxcvbnm"));
        assertFalse(AdminAccountInitializer.isWeakBootstrapPassword("j7que9-v2ziK-bravo"));
    }

    @Test
    void refusesToSeedWithKnownDefaultPassword() {
        AdminAccountRepo repo = mock(AdminAccountRepo.class);
        when(repo.existsByEmailIgnoreCase("admin@example.com")).thenReturn(false);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        AdminAccountInitializer init = new AdminAccountInitializer(repo, encoder);
        ReflectionTestUtils.setField(init, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(init, "adminPassword", "ChangeMe_Admin_Password");

        init.run();

        // the weak bootstrap default must never be written to the DB
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotSeedWhenAdminAlreadyExists() {
        AdminAccountRepo repo = mock(AdminAccountRepo.class);
        when(repo.existsByEmailIgnoreCase("admin@example.com")).thenReturn(true);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        AdminAccountInitializer init = new AdminAccountInitializer(repo, encoder);
        ReflectionTestUtils.setField(init, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(init, "adminPassword", "asdfghjklzxcvbnm");

        init.run();

        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void seedsStrongPasswordWhenMissing() {
        AdminAccountRepo repo = mock(AdminAccountRepo.class);
        when(repo.existsByEmailIgnoreCase("admin@example.com")).thenReturn(false);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        AdminAccountInitializer init = new AdminAccountInitializer(repo, encoder);
        ReflectionTestUtils.setField(init, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(init, "adminPassword", "asdfghjklzxcvbnm");

        init.run();

        verify(repo).save(org.mockito.ArgumentMatchers.any());
    }
}