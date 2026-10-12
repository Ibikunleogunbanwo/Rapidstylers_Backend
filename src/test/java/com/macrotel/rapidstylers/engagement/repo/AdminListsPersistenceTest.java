package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.engagement.entity.AnnouncementEntity;
import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.payment.entity.ReconciliationRunEntity;
import com.macrotel.rapidstylers.payment.repo.ReconciliationRunRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The new admin lists against the real schema (V35-V37 and the queries behind
 * them): review search, kept reconciliation runs, announcement history, ticket
 * sender roles and the case-insensitive category name check.
 */
@SpringBootTest
class AdminListsPersistenceTest {

    @Autowired
    ReviewRepo reviewRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    ReconciliationRunRepo reconciliationRunRepo;
    @Autowired
    AnnouncementRepo announcementRepo;
    @Autowired
    SupportTicketRepo supportTicketRepo;
    @Autowired
    ServiceRepo serviceRepo;

    private final String tag = "ALT" + System.nanoTime();
    private final List<Runnable> cleanUp = new ArrayList<>();

    @AfterEach
    void removeRows() {
        cleanUp.forEach(Runnable::run);
    }

    private ReviewEntity review(String stylerId, String userName, String message, String status) {
        ReviewEntity review = new ReviewEntity();
        review.setStylerId(stylerId);
        review.setUserName(userName);
        review.setMessage(message);
        review.setRatingScore(4);
        review.setModerationStatus(status);
        ReviewEntity saved = reviewRepo.save(review);
        cleanUp.add(() -> reviewRepo.deleteById(saved.getId()));
        return saved;
    }

    @Test
    void reviewsAreSearchedByReviewerMessageAndStudioName() {
        StylerEntity studio = new StylerEntity();
        studio.setStylerId(tag);
        studio.setBusinessName("Studio " + tag);
        StylerEntity savedStudio = stylerRepo.save(studio);
        cleanUp.add(() -> stylerRepo.delete(savedStudio));
        ReviewEntity byStudio = review(tag, "Ada", "Lovely", "APPROVED");
        ReviewEntity byName = review("OTHER", "Reviewer " + tag, "Fine", "APPROVED");
        ReviewEntity byMessage = review("OTHER", "Bo", "Loved it " + tag.toLowerCase(), "APPROVED");
        review(tag, "Cy", "Hidden", "REJECTED");

        List<ReviewEntity> found = reviewRepo.adminSearch("APPROVED", "%" + tag.toLowerCase() + "%", PageRequest.of(0, 20)).getContent();

        assertEquals(List.of(byMessage.getId(), byName.getId(), byStudio.getId()),
                found.stream().map(ReviewEntity::getId).toList());
        assertEquals(1, reviewRepo.adminSearch("REJECTED", "%" + tag.toLowerCase() + "%", PageRequest.of(0, 20)).getTotalElements());
        assertTrue(reviewRepo.adminSearch("APPROVED", null, PageRequest.of(0, 1)).getTotalElements() >= 3);
    }

    @Test
    void theLatestReconciliationRunComesBack() {
        ReconciliationRunEntity older = run(LocalDateTime.now().minusDays(1), "SCHEDULED");
        ReconciliationRunEntity newer = run(LocalDateTime.now().plusYears(50), "MANUAL");

        ReconciliationRunEntity latest = reconciliationRunRepo.findFirstByOrderByRanAtDescIdDesc().orElseThrow();

        assertEquals(newer.getId(), latest.getId());
        assertEquals("{\"ok\":true,\"tag\":\"" + tag + "\"}", latest.getReportJson());
        assertTrue(older.getId() < newer.getId());
    }

    private ReconciliationRunEntity run(LocalDateTime at, String trigger) {
        ReconciliationRunEntity run = new ReconciliationRunEntity();
        run.setRanAt(at);
        run.setTriggerSource(trigger);
        run.setOk(true);
        run.setIssueCount(0);
        run.setReportJson("{\"ok\":true,\"tag\":\"" + tag + "\"}");
        ReconciliationRunEntity saved = reconciliationRunRepo.save(run);
        cleanUp.add(() -> reconciliationRunRepo.deleteById(saved.getId()));
        return saved;
    }

    @Test
    void announcementsAndTicketSenderRolesAreKept() {
        AnnouncementEntity sent = new AnnouncementEntity();
        sent.setTitle(tag);
        sent.setMessage("Message");
        sent.setRecipientCount(3);
        sent.setSentAt(LocalDateTime.now().plusYears(50));
        sent.setSentBy("admin@example.com");
        AnnouncementEntity saved = announcementRepo.save(sent);
        cleanUp.add(() -> announcementRepo.deleteById(saved.getId()));
        assertEquals(tag, announcementRepo.findTop200ByOrderBySentAtDescIdDesc().get(0).getTitle());

        SupportTicketEntity ticket = supportTicketRepo.save(new SupportTicketEntity(tag, "STYLIST", "Subject", "Message"));
        cleanUp.add(() -> supportTicketRepo.deleteById(ticket.getId()));
        assertEquals("STYLIST", supportTicketRepo.findByUserIdOrderByUpdatedAtDesc(tag).get(0).getSenderRole());
    }

    @Test
    void categoryNamesMatchIgnoringCaseAndSurroundingSpaces() {
        ServiceEntity service = new ServiceEntity();
        service.setServiceName(" Cat " + tag + " ");
        ServiceEntity saved = serviceRepo.save(service);
        cleanUp.add(() -> serviceRepo.deleteById(saved.getId()));

        assertEquals(1, serviceRepo.findBySameName("cat " + tag.toLowerCase()).size());
    }
}
