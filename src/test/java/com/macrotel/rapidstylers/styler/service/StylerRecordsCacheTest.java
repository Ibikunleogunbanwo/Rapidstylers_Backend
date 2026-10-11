package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Other domains save stylists through StylerRecords (a Stripe payout update, a
 * sign-in). The cached profile card must go with each save, or the public page
 * keeps showing the old state until the entry expires.
 */
class StylerRecordsCacheTest {

    @Test
    void savingAStylistDropsTheirCachedProfileCard() {
        StylerRepo stylerRepo = mock(StylerRepo.class);
        ReadCacheService cache = mock(ReadCacheService.class);
        StylerRecords records = new StylerRecords(stylerRepo, null);
        records.readCacheService = cache;
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("GS1");
        when(stylerRepo.save(styler)).thenReturn(styler);

        records.save(styler);

        verify(stylerRepo).save(styler);
        verify(cache).evict(ReadCacheService.KEY_STYLER_DTO + "GS1");
    }
}
