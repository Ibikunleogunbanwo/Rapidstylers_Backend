package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketData;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportTicketSanitizeTest {

    private TestServices services;
    private UserRepo userRepo;
    private SupportTicketRepo supportTicketRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        userRepo = mock(UserRepo.class);
        supportTicketRepo = mock(SupportTicketRepo.class);
        services.userRepo = userRepo;
        services.supportTicketRepo = supportTicketRepo;
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

        BaseResponse response = services.supportService().createSupportTicket("U1", data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<SupportTicketEntity> captor = ArgumentCaptor.forClass(SupportTicketEntity.class);
        verify(supportTicketRepo).save(captor.capture());
        assertEquals("Login broken", captor.getValue().getSubject());
        assertEquals("Cant sign in please help", captor.getValue().getMessage());
    }
}
