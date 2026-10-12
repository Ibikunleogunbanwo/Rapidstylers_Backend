package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Legacy public ID photos move to private assets: dry run, real run, re-runs and failures. */
class IdentificationImageMigrationServiceTest {

    private static final String PUBLIC_A = "https://res.cloudinary.com/demo/image/upload/v1700000000/rapid_stylers/id/a1.jpg";
    private static final String PUBLIC_B = "https://res.cloudinary.com/demo/image/upload/v1700000000/rapid_stylers/id/b2.png";
    private static final String PRIVATE_C = "https://res.cloudinary.com/demo/image/authenticated/s--abc--/v1700000001/rapid_stylers/id/c3.jpg";

    private IdentificationImageMigrationService service;
    private CloudinaryService cloudinary;
    private StylerRecords stylerRecords;
    private ImageReclaimService reclaim;
    private AuditService audit;
    private List<StylerEntity> stylists;

    @BeforeEach
    void setUp() {
        CloudinaryService real = new CloudinaryService();
        ReflectionTestUtils.setField(real, "cloudName", "demo");
        ReflectionTestUtils.setField(real, "apiKey", "key123");
        ReflectionTestUtils.setField(real, "apiSecret", "s3cret");
        // URL parsing is real; the calls to Cloudinary are stubbed.
        cloudinary = spy(real);
        doAnswer(inv -> "https://res.cloudinary.com/demo/image/authenticated/s--sig--/v1800000000/"
                + inv.getArgument(1) + ".jpg").when(cloudinary).copyToAuthenticated(anyString(), anyString());
        doReturn(true).when(cloudinary).destroy(anyString(), anyString());

        stylists = new ArrayList<>(List.of(
                stylist(1L, "S1", PUBLIC_A),
                stylist(2L, "S2", PRIVATE_C),
                stylist(3L, "S3", "/images/id/static.jpg"),
                stylist(4L, "S4", PUBLIC_B)));
        stylerRecords = mock(StylerRecords.class);
        when(stylerRecords.withIdentificationImageAfter(anyLong(), anyInt())).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            int limit = inv.getArgument(1);
            return stylists.stream().filter(s -> s.getId() > after).limit(limit).collect(java.util.stream.Collectors.toList());
        });
        reclaim = mock(ImageReclaimService.class);
        when(reclaim.referenceCount(anyString())).thenReturn(1L);
        audit = mock(AuditService.class);

        service = new IdentificationImageMigrationService();
        service.cloudinaryService = cloudinary;
        service.stylerRecords = stylerRecords;
        service.imageReclaimService = reclaim;
        service.auditService = audit;
    }

    private static StylerEntity stylist(long id, String stylerId, String url) {
        StylerEntity styler = new StylerEntity();
        styler.setId(id);
        styler.setStylerId(stylerId);
        styler.setIdentificationImageUrl(url);
        return styler;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(BaseResponse response) {
        assertEquals("200", response.getStatusCode(), response.getMessage());
        return (Map<String, Object>) response.getData();
    }

    @Test
    void aDryRunCountsAndChangesNothing() {
        Map<String, Object> data = data(service.migrate("admin@example.com", true, null, null));

        assertEquals(true, data.get("dryRun"));
        assertEquals(4, data.get("examined"));
        assertEquals(2, data.get("found"));
        assertEquals(0, data.get("migrated"));
        assertEquals(1, data.get("alreadyPrivate"));
        assertEquals(0, data.get("failed"));
        assertEquals(1, data.get("notCloudinary"));
        assertEquals(List.of("S1", "S4"), data.get("ids"));
        assertNull(data.get("nextAfterId"));
        verify(cloudinary, never()).copyToAuthenticated(anyString(), anyString());
        verify(cloudinary, never()).destroy(anyString(), anyString());
        verify(stylerRecords, never()).save(any());
        verify(audit).audit(eq("admin@example.com"), eq("ADMIN"), eq("ADMIN_ID_IMAGE_MIGRATION_DRY_RUN"),
                eq("STYLER"), eq("ALL"), contains("found=2"));
    }

    @Test
    void aRealRunCopiesUpdatesTheRowThenDeletesThePublicOriginal() {
        Map<String, Object> data = data(service.migrate("admin@example.com", false, null, null));

        assertEquals(2, data.get("found"));
        assertEquals(2, data.get("migrated"));
        assertEquals(1, data.get("alreadyPrivate"));
        assertEquals(0, data.get("failed"));
        assertEquals(List.of("S1", "S4"), data.get("ids"));
        assertTrue(stylists.get(0).getIdentificationImageUrl().contains("/image/authenticated/"));
        assertTrue(stylists.get(3).getIdentificationImageUrl().contains("/image/authenticated/"));
        assertEquals(PRIVATE_C, stylists.get(1).getIdentificationImageUrl());
        var order = inOrder(cloudinary, stylerRecords);
        order.verify(cloudinary).copyToAuthenticated(PUBLIC_A, "rapid_stylers/id/a1");
        order.verify(stylerRecords).save(stylists.get(0));
        order.verify(cloudinary).destroy("rapid_stylers/id/a1", CloudinaryService.TYPE_UPLOAD);
        verify(audit).audit(eq("admin@example.com"), eq("ADMIN"), eq("ADMIN_ID_IMAGE_MIGRATION"),
                eq("STYLER"), eq("ALL"), contains("migrated=2"));
    }

    @Test
    void aSecondRunFindsNothingLeftToDo() {
        service.migrate("admin@example.com", false, null, null);
        clearInvocations(cloudinary, stylerRecords);

        Map<String, Object> data = data(service.migrate("admin@example.com", false, null, null));

        assertEquals(0, data.get("found"));
        assertEquals(3, data.get("alreadyPrivate"));
        verify(cloudinary, never()).copyToAuthenticated(anyString(), anyString());
        verify(stylerRecords, never()).save(any());
    }

    @Test
    void oneFailureIsReportedAndTheRunGoesOn() {
        doReturn(null).when(cloudinary).copyToAuthenticated(eq(PUBLIC_A), anyString());

        Map<String, Object> data = data(service.migrate("admin@example.com", false, null, null));

        assertEquals(2, data.get("found"));
        assertEquals(1, data.get("migrated"));
        assertEquals(1, data.get("failed"));
        assertEquals(List.of("S1"), data.get("failedIds"));
        assertEquals(PUBLIC_A, stylists.get(0).getIdentificationImageUrl());
        verify(cloudinary, never()).destroy(eq("rapid_stylers/id/a1"), anyString());
        assertTrue(stylists.get(3).getIdentificationImageUrl().contains("/image/authenticated/"));
    }

    @Test
    void aFailedRowSaveKeepsThePublicOriginal() {
        doThrow(new RuntimeException("db down")).when(stylerRecords).save(stylists.get(0));

        Map<String, Object> data = data(service.migrate("admin@example.com", false, null, null));

        assertEquals(List.of("S1"), data.get("failedIds"));
        verify(cloudinary, never()).destroy(eq("rapid_stylers/id/a1"), anyString());
    }

    @Test
    void aPublicOriginalAnotherRowStillUsesIsKept() {
        when(reclaim.referenceCount("rapid_stylers/id/a1")).thenReturn(2L);

        Map<String, Object> data = data(service.migrate("admin@example.com", false, null, null));

        assertEquals(2, data.get("migrated"));
        assertEquals(List.of("S1"), data.get("publicCopyKeptIds"));
        verify(cloudinary, never()).destroy(eq("rapid_stylers/id/a1"), anyString());
    }

    @Test
    void workIsBoundedAndResumesFromNextAfterId() {
        Map<String, Object> first = data(service.migrate("admin@example.com", true, null, 2));

        assertEquals(2, first.get("examined"));
        assertEquals(2L, first.get("nextAfterId"));
        verify(stylerRecords).withIdentificationImageAfter(0L, 2);

        Map<String, Object> second = data(service.migrate("admin@example.com", true, 2L, 2));
        assertEquals(2, second.get("examined"));
        assertEquals(List.of("S4"), second.get("ids"));
        // A full last page cannot tell there is nothing more, so one more (empty) call ends it.
        Map<String, Object> third = data(service.migrate("admin@example.com", true, 4L, 2));
        assertEquals(0, third.get("examined"));
        assertNull(third.get("nextAfterId"));
    }

    @Test
    void aRealRunNeedsCloudinary() {
        CloudinaryService unconfigured = new CloudinaryService();
        service.cloudinaryService = unconfigured;

        assertEquals("400", service.migrate("admin@example.com", false, null, null).getStatusCode());
        verify(stylerRecords, never()).withIdentificationImageAfter(anyLong(), anyInt());
    }
}
