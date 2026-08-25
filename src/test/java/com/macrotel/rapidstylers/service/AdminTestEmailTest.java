package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminTestEmailTest {

    private AppService appService;
    private EmailConfig emailConfig;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        emailConfig = mock(EmailConfig.class);
        appService.emailConfig = emailConfig;
    }

    @Test
    void sendsTestEmailThroughEmailConfig() {
        when(emailConfig.sendSimpleMail("ogunbanwoibikunlea@gmail.com", "RapidStylers test email",
                "<h2>RapidStylers</h2><p>This is a test email sent from the admin console to verify email delivery.</p>"))
                .thenReturn("Mail Sent Successfully...");
        BaseResponse response = appService.sendTestEmail("ADMIN1", "ogunbanwoibikunlea@gmail.com");
        assertEquals("200", response.getStatusCode());
        verify(emailConfig).sendSimpleMail("ogunbanwoibikunlea@gmail.com", "RapidStylers test email",
                "<h2>RapidStylers</h2><p>This is a test email sent from the admin console to verify email delivery.</p>");
    }

    @Test
    void rejectsMalformedRecipientWithoutSending() {
        BaseResponse response = appService.sendTestEmail("ADMIN1", "not-an-email");
        assertEquals("400", response.getStatusCode());
        verify(emailConfig, never()).sendSimpleMail(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reportsProviderFailureAsError() {
        when(emailConfig.sendSimpleMail(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("Error while Sending Mail");
        BaseResponse response = appService.sendTestEmail("ADMIN1", "ogunbanwoibikunlea@gmail.com");
        assertEquals("400", response.getStatusCode());
    }
}
