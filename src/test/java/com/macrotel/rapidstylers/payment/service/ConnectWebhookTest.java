package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.stripe.model.Account;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectWebhookTest {

    // Connect onboarding webhook handling lives in PaymentService.
    private PaymentService paymentOps;
    private StylerRepo stylerRepo;
    private EmailConfig emailConfig;

    @BeforeEach
    void setUp() {
        paymentOps = new PaymentService();
        stylerRepo = mock(StylerRepo.class);
        emailConfig = mock(EmailConfig.class);
        paymentOps.stylerRecords = new com.macrotel.rapidstylers.styler.service.StylerRecords(stylerRepo, mock(com.macrotel.rapidstylers.styler.repo.SubServiceRepo.class));
        paymentOps.emailConfig = emailConfig;
        paymentOps.appointmentRecords = new com.macrotel.rapidstylers.booking.service.AppointmentRecords(mock(com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo.class));
        // audit() is a no-op on a bare AuditService (null repo guard).
        paymentOps.auditService = new AuditService();
    }

    @Test
    void completeTransitionEmailsStylistAndPersistsStatus() {
        StylerEntity styler = styler("Ada", "Client", "styler@example.com", "PENDING");
        when(stylerRepo.findByStripeConnectAccountId("acct_1")).thenReturn(Optional.of(styler));

        Account account = new Account();
        account.setId("acct_1");
        account.setDetailsSubmitted(true);
        account.setPayoutsEnabled(true);

        paymentOps.handleAccountUpdated(account);

        verify(stylerRepo).save(styler);
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETE", styler.getConnectOnboardingStatus());
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Payouts are ready"), contains("connected"));
    }

    @Test
    void rejectedTransitionEmailsStylistWithDisabledReason() {
        StylerEntity styler = styler("Ada", "Client", "styler@example.com", "PENDING");
        when(stylerRepo.findByStripeConnectAccountId("acct_1")).thenReturn(Optional.of(styler));

        Account.Requirements requirements = new Account.Requirements();
        requirements.setDisabledReason("rejected.other");
        Account account = new Account();
        account.setId("acct_1");
        account.setDetailsSubmitted(true);
        account.setPayoutsEnabled(false);
        account.setRequirements(requirements);

        paymentOps.handleAccountUpdated(account);

        org.junit.jupiter.api.Assertions.assertEquals("REJECTED", styler.getConnectOnboardingStatus());
        org.junit.jupiter.api.Assertions.assertEquals("rejected.other", styler.getConnectDisabledReason());
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("needs attention"), contains("rejected.other"));
    }

    @Test
    void completeTransitionClearsPersistedDisabledReason() {
        StylerEntity styler = styler("Ada", "Client", "styler@example.com", "REJECTED");
        styler.setConnectDisabledReason("rejected.other");
        when(stylerRepo.findByStripeConnectAccountId("acct_1")).thenReturn(Optional.of(styler));

        Account account = new Account();
        account.setId("acct_1");
        account.setDetailsSubmitted(true);
        account.setPayoutsEnabled(true);

        paymentOps.handleAccountUpdated(account);

        org.junit.jupiter.api.Assertions.assertEquals("COMPLETE", styler.getConnectOnboardingStatus());
        org.junit.jupiter.api.Assertions.assertNull(styler.getConnectDisabledReason());
    }

    @Test
    void alreadyCompleteDoesNotResendEmail() {
        StylerEntity styler = styler("Ada", "Client", "styler@example.com", "COMPLETE");
        when(stylerRepo.findByStripeConnectAccountId("acct_1")).thenReturn(Optional.of(styler));

        Account account = new Account();
        account.setId("acct_1");
        account.setDetailsSubmitted(true);
        account.setPayoutsEnabled(true);

        paymentOps.handleAccountUpdated(account);

        verify(stylerRepo).save(any(StylerEntity.class));
        verify(emailConfig, never()).sendSimpleMail(eq("styler@example.com"), contains("Payouts are ready"), contains("connected"));
    }

    private StylerEntity styler(String firstname, String lastname, String email, String status) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setStripeConnectAccountId("acct_1");
        styler.setConnectOnboardingStatus(status);
        styler.setFirstname(firstname);
        styler.setLastname(lastname);
        styler.setEmailAddress(email);
        return styler;
    }
}
