package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.SupportTicketActionData;
import com.macrotel.rapidstylers.repo.SupportTicketRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketResponseSanitizeTest {

    private AppService appService;
    private SupportTicketRepo supportTicketRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        supportTicketRepo = mock(SupportTicketRepo.class);
        appService.supportTicketRepo = supportTicketRepo;
    }

    @Test
    void adminResponseIsSanitizedBeforeSave() {
        SupportTicketEntity ticket = new SupportTicketEntity("U1", "Login issue", "Cannot sign in");
        ticket.setId(7L);
        when(supportTicketRepo.findById(7L)).thenReturn(Optional.of(ticket));

        SupportTicketActionData data = new SupportTicketActionData();
        data.setTicketId(7L);
        data.setStatus("RESOLVED");
        data.setAdminResponse("Fixed it <script>alert(1)</script> — try again <img src=x onerror=alert(2)>");

        BaseResponse response = appService.updateSupportTicket(data, "ADMIN1");

        assertEquals("200", response.getStatusCode());
        verify(supportTicketRepo).save(ticket);
        assertEquals("Fixed it — try again", ticket.getAdminResponse());
        assertEquals("RESOLVED", ticket.getStatus());
    }
}
