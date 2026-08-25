package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.SavedStylistEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SavedStylistTest {
    private AppService appService;
    private SavedStylistRepo savedRepo;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        savedRepo = mock(SavedStylistRepo.class);
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        appService.savedStylistRepo = savedRepo;
        appService.userRepo = userRepo;
        appService.stylerRepo = stylerRepo;
        appService.dtoService = mock(DTOService.class);
    }

    @Test
    void saveIsIdempotentAndRequiresApprovedStylist() {
        UserEntity user = new UserEntity();
        StylerEntity stylist = stylist("APPROVED");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(stylist));
        when(savedRepo.findByUserIdAndStylerId("USER1", "STYLER1"))
                .thenReturn(Optional.empty(), Optional.of(new SavedStylistEntity("USER1", "STYLER1")));

        BaseResponse first = appService.saveStylist("USER1", "STYLER1");
        BaseResponse second = appService.saveStylist("USER1", "STYLER1");

        assertEquals("200", first.getStatusCode());
        assertEquals("200", second.getStatusCode());
        verify(savedRepo).save(any(SavedStylistEntity.class));
    }

    @Test
    void rejectedStylistCannotBeSaved() {
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(new UserEntity()));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(stylist("REJECTED")));

        BaseResponse response = appService.saveStylist("USER1", "STYLER1");

        assertEquals("400", response.getStatusCode());
        verify(savedRepo, never()).save(any(SavedStylistEntity.class));
    }

    @Test
    void listReturnsOnlyApprovedLiveStylistDtos() {
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(new UserEntity()));
        SavedStylistEntity approved = new SavedStylistEntity("USER1", "STYLER1");
        SavedStylistEntity rejected = new SavedStylistEntity("USER1", "STYLER2");
        when(savedRepo.findByUserIdOrderByCreatedAtDesc("USER1"))
                .thenReturn(Arrays.asList(approved, rejected));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(stylist("APPROVED")));
        when(stylerRepo.findByStylerId("STYLER2")).thenReturn(Optional.of(stylist("REJECTED")));
        StylerAccountDTO dto = new StylerAccountDTO();
        dto.setStylerId("STYLER1");
        when(appService.dtoService.stylerAccountDTO(any(StylerEntity.class))).thenReturn(dto);

        BaseResponse response = appService.listSavedStylists("USER1");

        assertEquals("200", response.getStatusCode());
        assertEquals(1, ((java.util.List<?>) response.getData()).size());
    }

    private StylerEntity stylist(String status) {
        StylerEntity stylist = new StylerEntity();
        stylist.setStylerId("STYLER1");
        stylist.setVerificationStatus(status);
        return stylist;
    }
}
