package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import com.macrotel.rapidstylers.repo.OTPRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abandoned-signup recovery is a staged e-mail campaign: one email per milestone
 * (24h -> 7d -> 14d -> 1 month), each sent once, only when the attempt has aged
 * past that threshold and no account has been created yet.
 */
@SpringBootTest
// Keep the retention purge far in the future so a very old test attempt
// (e.g. the 30-day skip test) is not deleted by the same run's cleanup.
@TestPropertySource(properties = "app.signup-followup.retention-days=3650")
class SignupFollowUpServiceTest {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    @Autowired SignupFollowUpService signupFollowUpService;
    @Autowired OTPRepo otpRepo;
    @Autowired UserRepo userRepo;
    @Autowired OutboxEventRepo outboxEventRepo;

    private String email;

    @BeforeEach
    void identity() {
        email = "abandon." + System.currentTimeMillis() + "@rapidstylers.test";
    }

    @AfterEach
    void cleanup() {
        otpRepo.findAll().stream()
                .filter(o -> email.equals(o.getEmailAddress()))
                .forEach(otpRepo::delete);
        userRepo.findByEmailAddress(email).ifPresent(userRepo::delete);
        outboxEventRepo.findAll().stream()
                .filter(e -> email.equals(e.getAggregateId()))
                .forEach(outboxEventRepo::delete);
    }

    private void seedOtp(int hoursAgo, Integer stage) {
        OTPEntity otp = new OTPEntity();
        otp.setEmailAddress(email);
        otp.setPurpose("USER SIGN UP");
        otp.setCode("123456");
        otp.setInsertedDt(LocalDateTime.now().minusHours(hoursAgo).format(STAMP));
        if (stage != null) {
            otp.setFollowupStage(stage);
        }
        otpRepo.save(otp);
    }

    private List<OutboxEventEntity> reminders() {
        return outboxEventRepo.findAll().stream()
                .filter(e -> OutboxEventType.SIGNUP_REMINDER.equals(e.getEventType())
                        && email.equals(e.getAggregateId()))
                .toList();
    }

    private Integer stageOf(List<OutboxEventEntity> events) {
        if (events.isEmpty()) return null;
        String payload = events.get(0).getPayload();
        int i = payload.indexOf("\"stage\"");
        return Integer.parseInt(payload.substring(payload.indexOf(':', i) + 1).trim().split("[,}\\s]")[0]);
    }

    private Integer rowStage() {
        return otpRepo.findAll().stream()
                .filter(o -> email.equals(o.getEmailAddress()))
                .map(OTPEntity::getFollowupStage)
                .findFirst().orElse(null);
    }

    private java.time.LocalDateTime lastSentStamp() {
        return otpRepo.findAll().stream()
                .filter(o -> email.equals(o.getEmailAddress()))
                .map(OTPEntity::getFollowupUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    @Test
    void stage1SentAfterTwentyFourHoursOnce() {
        seedOtp(25, null);

        signupFollowUpService.followUpAbandonedSignups();

        List<OutboxEventEntity> sent = reminders();
        assertEquals(1, sent.size(), "a >24h abandoned sign-up gets the stage-1 email");
        assertEquals(Integer.valueOf(1), stageOf(sent), "the first email is stage 1");
        assertEquals(Integer.valueOf(1), rowStage(), "stage 1 recorded on the attempt");
        assertNotNull(lastSentStamp(), "the last-sent time is recorded for the admin funnel view");

        signupFollowUpService.followUpAbandonedSignups();
        assertEquals(1, reminders().size(), "stage 1 is not re-sent");
    }

    @Test
    void stage2SentAFamilyDayLater() {
        seedOtp(8 * 24, 1); // already had its stage-1 email, now 8 days old

        signupFollowUpService.followUpAbandonedSignups();

        List<OutboxEventEntity> sent = reminders();
        assertEquals(1, sent.size(), "the 7-day corporate email is sent");
        assertEquals(Integer.valueOf(2), stageOf(sent), "the second email is stage 2");
        assertEquals(Integer.valueOf(2), rowStage());
    }

    @Test
    void jumpsToHighestMilestoneWhenThresholdElapsed() {
        seedOtp(40 * 24, null); // far past 14d and 1 month, never emailed

        signupFollowUpService.followUpAbandonedSignups();

        List<OutboxEventEntity> sent = reminders();
        assertEquals(1, sent.size(), "only one email when many thresholds elapsed");
        assertEquals(Integer.valueOf(4), stageOf(sent), "the furthest milestone due is sent");
        assertEquals(Integer.valueOf(4), rowStage());
    }

    @Test
    void freshSignupAttemptIsNotEmailed() {
        seedOtp(5, null); // in progress

        signupFollowUpService.followUpAbandonedSignups();

        assertTrue(reminders().isEmpty(), "an in-progress sign-up is not emailed yet");
    }

    @Test
    void recoveryCampaignViewListsAbandonedAttempt() {
        seedOtp(25, null);

        boolean listed = otpRepo.findRecoveryCampaigns().stream()
                .anyMatch(row -> email.equals(String.valueOf(row[0])));

        assertTrue(listed, "the abandoned email must appear in the admin recovery view");
    }

    @Test
    void completedSignupIsNotEmailed() {
        UserEntity user = new UserEntity();
        user.setFirstname("Done");
        user.setLastname("User");
        user.setEmailAddress(email);
        user.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRST");
        user.setStatus("0");
        user.setUserId("U" + System.currentTimeMillis());
        userRepo.save(user);
        seedOtp(9 * 24, null);

        signupFollowUpService.followUpAbandonedSignups();

        assertTrue(reminders().isEmpty(), "an existing account never gets a sign-up campaign email");
    }
}