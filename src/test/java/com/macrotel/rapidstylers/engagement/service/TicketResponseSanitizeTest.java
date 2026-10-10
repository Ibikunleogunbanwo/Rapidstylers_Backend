package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketActionData;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketResponseSanitizeTest {

    private TestServices services;
    private SupportTicketRepo supportTicketRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        supportTicketRepo = mock(SupportTicketRepo.class);
        services.supportTicketRepo = supportTicketRepo;
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

        BaseResponse response = services.supportService().updateSupportTicket(data, "ADMIN1");

        assertEquals("200", response.getStatusCode());
        verify(supportTicketRepo).save(ticket);
        assertEquals("Fixed it — try again", ticket.getAdminResponse());
        assertEquals("RESOLVED", ticket.getStatus());
    }
}
