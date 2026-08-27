package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.PayoutReversalEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.PayoutReversalRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutReversalServiceTest {

    private PayoutReversalRepo payoutReversalRepo;
    private StripeService stripeService;
    private EmailConfig emailConfig;
    private PayoutReversalService service;

    @BeforeEach
    void setUp() {
        payoutReversalRepo = mock(PayoutReversalRepo.class);
        stripeService = mock(StripeService.class);
        emailConfig = mock(EmailConfig.class);
        service = new PayoutReversalService();
        ReflectionTestUtils.setField(service, "payoutReversalRepo", payoutReversalRepo);
        ReflectionTestUtils.setField(service, "stripeService", stripeService);
        ReflectionTestUtils.setField(service, "auditLogRepo", mock(AuditLogRepo.class));
        ReflectionTestUtils.setField(service, "emailConfig", emailConfig);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "retryIntervalMs", 1800000L);
        ReflectionTestUtils.setField(service, "adminAlertEmail", "ops@example.com");
    }

    private PayoutReversalEntity reversal(String transferId, String status, int attempts) {
        PayoutReversalEntity record = new PayoutReversalEntity();
        record.setReversalId("REV-12345678");
        record.setAppointmentId("APPT1");
        record.setTransferId(transferId);
        record.setAmount("112.50");
        record.setStatus(status);
        record.setAttempts(attempts);
        record.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        return record;
    }

    @Test
    void requestReversalCreatesRecordAndReversesImmediately() throws Exception {
        when(payoutReversalRepo.findByTransferId("tr_1")).thenReturn(Collections.emptyList());
        com.stripe.model.TransferReversal reversal = new com.stripe.model.TransferReversal();
        reversal.setId("trrev_1");
        doReturn(reversal).when(stripeService).reverseTransfer(eq("tr_1"), anyString(), startsWith("reversal_tr_1_REV-"));

        service.requestReversal("APPT1", "tr_1", "112.50", "cancelled");

        ArgumentCaptor<PayoutReversalEntity> captor = ArgumentCaptor.forClass(PayoutReversalEntity.class);
        verify(payoutReversalRepo, org.mockito.Mockito.times(2)).save(captor.capture());
        PayoutReversalEntity saved = captor.getAllValues().get(1); // create + outcome
        assertEquals("REVERSED", saved.getStatus());
        assertEquals("trrev_1", saved.getStripeReversalId());
        assertEquals("tr_1", saved.getTransferId());
        assertEquals("APPT1", saved.getAppointmentId());
        verify(stripeService).reverseTransfer(eq("tr_1"), anyString(), startsWith("reversal_tr_1_REV-"));
    }

    @Test
    void failedReversalIsMarkedRetryableWithBackoff() throws Exception {
        when(payoutReversalRepo.findByTransferId("tr_1")).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("Insufficient funds in destination")).when(stripeService).reverseTransfer(anyString(), anyString(), anyString());

        service.requestReversal("APPT1", "tr_1", "112.50", "cancelled");

        ArgumentCaptor<PayoutReversalEntity> captor = ArgumentCaptor.forClass(PayoutReversalEntity.class);
        verify(payoutReversalRepo, org.mockito.Mockito.times(2)).save(captor.capture());
        PayoutReversalEntity saved = captor.getAllValues().get(1);
        assertEquals("FAILED", saved.getStatus());
        assertEquals(1, saved.getAttempts());
        assertTrue(saved.getNextAttemptAt().isAfter(LocalDateTime.now()));
        assertTrue(saved.getLastError().contains("Insufficient funds"));
    }

    @Test
    void transferAlreadyTrackedIsNotRequestedAgain() throws Exception {
        when(payoutReversalRepo.findByTransferId("tr_1"))
                .thenReturn(List.of(reversal("tr_1", "FAILED", 1)));

        service.requestReversal("APPT1", "tr_1", "112.50", "cancelled");

        verify(payoutReversalRepo, never()).save(any(PayoutReversalEntity.class));
        verify(stripeService, never()).reverseTransfer(anyString(), anyString(), anyString());
    }

    @Test
    void retryJobRecoversFailedReversalWhenFundsAllow() throws Exception {
        PayoutReversalEntity record = reversal("tr_1", "FAILED", 1);
        when(payoutReversalRepo.findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(record));
        com.stripe.model.TransferReversal reversal = new com.stripe.model.TransferReversal();
        reversal.setId("trrev_2");
        doReturn(reversal).when(stripeService).reverseTransfer(eq("tr_1"), anyString(), startsWith("reversal_tr_1_REV-"));

        service.retryDueReversals();

        assertEquals("REVERSED", record.getStatus());
        assertEquals("trrev_2", record.getStripeReversalId());
        verify(payoutReversalRepo).save(record);
    }

    @Test
    void exhaustedAttemptsMarkPermanentFailureAndAlertOps() throws Exception {
        PayoutReversalEntity record = reversal("tr_1", "FAILED", 4); // one attempt from the budget
        when(payoutReversalRepo.findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(record));
        doThrow(new RuntimeException("Insufficient funds")).when(stripeService).reverseTransfer(anyString(), anyString(), anyString());

        service.retryDueReversals();

        assertEquals("PERMANENTLY_FAILED", record.getStatus());
        assertEquals(5, record.getAttempts());
        verify(emailConfig).sendSimpleMail(eq("ops@example.com"), contains("Payout recovery action required"), contains("tr_1"));
    }
}
