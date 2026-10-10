package com.macrotel.rapidstylers.common.repo;

import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The admin activity log pages and narrows on the database, newest first. */
@SpringBootTest
class AuditLogSearchTest {

    @Autowired
    AuditLogRepo auditLogRepo;

    private final List<Long> created = new ArrayList<>();
    private final String actor = "LOGTEST" + System.nanoTime();

    @AfterEach
    void cleanUp() {
        auditLogRepo.deleteAllById(created);
    }

    private void log(String action, String resourceId, String createdAt) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setActorId(actor);
        entry.setActorRole("ADMIN");
        entry.setAction(action);
        entry.setResourceType("APPOINTMENT");
        entry.setResourceId(resourceId);
        entry.setCreatedAt(createdAt);
        created.add(auditLogRepo.save(entry).getId());
    }

    @Test
    void pagesNewestFirstAndFiltersByActionActorAndRecord() {
        log("REFUND", "APPT1", "2026-10-10 10:00:00");
        log("REFUND", "APPT2", "2026-10-10 11:00:00");
        log("SERVICE_DISPUTED", "APPT1", "2026-10-10 12:00:00");

        Page<AuditLogEntity> first = auditLogRepo.search(null, actor, null, PageRequest.of(0, 2));
        assertEquals(3, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals("SERVICE_DISPUTED", first.getContent().get(0).getAction());
        assertEquals(1, auditLogRepo.search(null, actor, null, PageRequest.of(1, 2)).getContent().size());

        assertEquals(2, auditLogRepo.search("REFUND", actor, null, PageRequest.of(0, 10)).getTotalElements());
        Page<AuditLogEntity> oneRecord = auditLogRepo.search(null, actor, "APPT1", PageRequest.of(0, 10));
        assertEquals(2, oneRecord.getTotalElements());
        assertEquals("REFUND", oneRecord.getContent().get(1).getAction());
    }
}
