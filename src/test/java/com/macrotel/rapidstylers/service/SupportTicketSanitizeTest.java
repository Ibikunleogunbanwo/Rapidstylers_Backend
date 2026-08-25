package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.SupportTicketData;
import com.macrotel.rapidstylers.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportTicketSanitizeTest {

    private AppService appService;
    private UserRepo userRepo;
    private SupportTicketRepo supportTicketRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        userRepo = mock(UserRepo.class);
        supportTicketRepo = mock(SupportTicketRepo.class);
        appService.userRepo = userRepo;
        appService.supportTicketRepo = supportTicketRepo;
    }

    @Test
    void ticketSubjectAndMessageAreSanitizedBeforeSave() {
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(new UserEntity()));
        when(supportTicketRepo.save(org.mockito.ArgumentMatchers.any(SupportTicketEntity.class)))
                .thenAnswer(inv -> {
                    SupportTicketEntity entity = inv.getArgument(0);
                    entity.setId(1L);
                    return entity;
                });

        SupportTicketData data = new SupportTicketData();
        data.setSubject("<script>alert(1)</script> Login broken");
        data.setMessage("Cant sign in <img src=x onerror=alert(2)> please help");

        BaseResponse response = appService.createSupportTicket("U1", data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<SupportTicketEntity> captor = ArgumentCaptor.forClass(SupportTicketEntity.class);
        verify(supportTicketRepo).save(captor.capture());
        assertEquals("Login broken", captor.getValue().getSubject());
        assertEquals("Cant sign in please help", captor.getValue().getMessage());
    }
}
