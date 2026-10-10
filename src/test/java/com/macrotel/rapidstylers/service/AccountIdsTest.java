package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** New account ids never repeat one a customer or stylist already holds. */
class AccountIdsTest {

    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private AccountIds accountIds;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        accountIds = new AccountIds(userRepo, stylerRepo);
    }

    @Test
    void aFreeIdIsKept() {
        assertEquals("A1234B", accountIds.freeAccountId("A1234B", () -> "never"));
    }

    @Test
    void anIdHeldByACustomerOrAStylerIsRedrawn() {
        when(userRepo.findByUserId("A1111B")).thenReturn(Optional.of(new UserEntity()));
        when(stylerRepo.findByStylerId("A2222B")).thenReturn(Optional.of(new StylerEntity()));
        Iterator<String> draws = List.of("A2222B", "A3333B").iterator();

        assertEquals("A3333B", accountIds.freeAccountId("A1111B", draws::next));
    }

    @Test
    void givesUpRatherThanLoopingForever() {
        when(userRepo.findByUserId(anyString())).thenReturn(Optional.of(new UserEntity()));

        assertThrows(IllegalStateException.class, () -> accountIds.freeAccountId("A1111B", () -> "A1111B"));
    }

    @Test
    void customerIdsUseTheInitialsWhenBothNamesAreGiven() {
        String id = accountIds.newCustomerId("Ada", "Lovelace");

        assertTrue(id.matches("A\\d{4}L"), id);
    }

    @Test
    void customerIdsFallBackToUWithoutNames() {
        assertTrue(accountIds.newCustomerId("", "").matches("U\\d{4}"));
    }
}
