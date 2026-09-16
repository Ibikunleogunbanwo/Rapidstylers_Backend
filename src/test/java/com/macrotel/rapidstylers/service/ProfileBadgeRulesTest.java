package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The badge rules are a promise about a professional, so each rule is pinned at
 * its boundary and in the case where the fact behind it is missing.
 */
class ProfileBadgeRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    private static List<String> earned(Long reviews, Double average, Long appointments,
                                       Long completed, String joined){
        return ProfileBadgeRules.earned(reviews, average, appointments, completed, joined, TODAY);
    }

    private static String daysAgo(int days){
        return TODAY.minusDays(days).toString();
    }

    // ---------- Top rated ----------

    @Test
    @DisplayName("top rated: 4.7 across 10 reviews, the exact threshold")
    void topRatedAtTheThreshold(){
        assertTrue(earned(10L, 4.7, 20L, 20L, daysAgo(400)).contains(ProfileBadgeRules.TOP_RATED));
    }

    @Test
    @DisplayName("top rated: one tenth under the average is not enough")
    void topRatedMissesOnAverage(){
        assertFalse(earned(10L, 4.6, 20L, 20L, daysAgo(400)).contains(ProfileBadgeRules.TOP_RATED));
    }

    @Test
    @DisplayName("top rated: a perfect average from too few reviews is not enough")
    void topRatedNeedsVolume(){
        assertFalse(earned(9L, 5.0, 20L, 20L, daysAgo(400)).contains(ProfileBadgeRules.TOP_RATED));
    }

    @Test
    @DisplayName("top rated: an unknown average can never earn it")
    void topRatedNeedsAnAverage(){
        assertFalse(earned(30L, null, 20L, 20L, daysAgo(400)).contains(ProfileBadgeRules.TOP_RATED));
    }

    @Test
    @DisplayName("top rated: an average with no reviews behind it earns nothing")
    void topRatedNeedsReviews(){
        assertFalse(earned(0L, 5.0, 20L, 20L, daysAgo(400)).contains(ProfileBadgeRules.TOP_RATED));
    }

    // ---------- First booking completed ----------

    @Test
    @DisplayName("first booking: one finished job and no reviews")
    void firstBookingAtTheThreshold(){
        assertEquals(List.of(ProfileBadgeRules.FIRST_BOOKING),
                earned(0L, 0.0, 3L, 1L, daysAgo(30)));
    }

    @Test
    @DisplayName("first booking: a request that was never finished does not earn it")
    void firstBookingNeedsAFinishedJob(){
        assertEquals(List.of(), earned(0L, 0.0, 3L, 0L, daysAgo(30)));
    }

    @Test
    @DisplayName("first booking: an unknown finished count earns nothing rather than guessing")
    void firstBookingNeedsTheFact(){
        assertEquals(List.of(), earned(0L, 0.0, 3L, null, daysAgo(30)));
    }

    @Test
    @DisplayName("first booking: it retires as soon as a review lands")
    void firstBookingRetiresOnReview(){
        assertFalse(earned(1L, 5.0, 3L, 1L, daysAgo(30)).contains(ProfileBadgeRules.FIRST_BOOKING));
    }

    // ---------- New ----------

    @Test
    @DisplayName("new: joined today with nothing on record")
    void newAtSignup(){
        assertEquals(List.of(ProfileBadgeRules.NEW), earned(0L, 0.0, 0L, 0L, daysAgo(0)));
    }

    @Test
    @DisplayName("new: day 90 is still inside the window, day 91 is not")
    void newWindowBoundary(){
        assertTrue(earned(0L, 0.0, 0L, 0L, daysAgo(ProfileBadgeRules.NEW_WINDOW_DAYS))
                .contains(ProfileBadgeRules.NEW));
        assertFalse(earned(0L, 0.0, 0L, 0L, daysAgo(ProfileBadgeRules.NEW_WINDOW_DAYS + 1))
                .contains(ProfileBadgeRules.NEW));
    }

    @Test
    @DisplayName("new: a booking on the books, even cancelled, ends it")
    void newEndsOnAnyBooking(){
        assertFalse(earned(0L, 0.0, 1L, 0L, daysAgo(1)).contains(ProfileBadgeRules.NEW));
    }

    @Test
    @DisplayName("new: a review ends it")
    void newEndsOnReview(){
        assertFalse(earned(2L, 5.0, 0L, 0L, daysAgo(1)).contains(ProfileBadgeRules.NEW));
    }

    @Test
    @DisplayName("new: an unknown appointment count cannot be read as zero")
    void newNeedsTheTally(){
        assertFalse(earned(0L, 0.0, null, null, daysAgo(1)).contains(ProfileBadgeRules.NEW));
    }

    @Test
    @DisplayName("new: a missing, unreadable or future join date earns nothing")
    void newNeedsAReadableDate(){
        assertFalse(earned(0L, 0.0, 0L, 0L, null).contains(ProfileBadgeRules.NEW));
        assertFalse(earned(0L, 0.0, 0L, 0L, "").contains(ProfileBadgeRules.NEW));
        assertFalse(earned(0L, 0.0, 0L, 0L, "not a date").contains(ProfileBadgeRules.NEW));
        assertFalse(earned(0L, 0.0, 0L, 0L, TODAY.plusDays(1).toString())
                .contains(ProfileBadgeRules.NEW));
    }

    @Test
    @DisplayName("new: a stored datetime is read as its day")
    void newAcceptsAStoredDatetime(){
        assertTrue(ProfileBadgeRules.joinedWithinNewWindow(daysAgo(10) + "T09:41:00Z", TODAY));
    }

    // ---------- The set as a whole ----------

    @Test
    @DisplayName("nothing known means no badges")
    void nothingKnown(){
        assertEquals(List.of(), earned(null, null, null, null, null));
    }

    @Test
    @DisplayName("a new profile can never also claim a finished booking")
    void stagesAreDisjoint(){
        for(int days = 0; days <= 120; days++){
            for(long appointments = 0; appointments <= 3; appointments++){
                for(long completed = 0; completed <= 3; completed++){
                    List<String> badges = earned(0L, 0.0, appointments, completed, daysAgo(days));
                    if(badges.contains(ProfileBadgeRules.NEW)){
                        assertFalse(badges.contains(ProfileBadgeRules.FIRST_BOOKING));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("once a rating exists it carries the profile alone, with no stage badge")
    void standingReplacesTheStageBadge(){
        assertEquals(List.of(ProfileBadgeRules.TOP_RATED), earned(12L, 4.8, 12L, 12L, daysAgo(200)));
        assertEquals(List.of(), earned(12L, 4.2, 12L, 12L, daysAgo(200)));
    }

    @Test
    @DisplayName("a negative or absurd count never earns a badge")
    void negativeCounts(){
        assertNotEquals(List.of(ProfileBadgeRules.TOP_RATED), earned(-5L, 5.0, 0L, 0L, daysAgo(500)));
        assertEquals(List.of(), earned(-5L, 5.0, 0L, -1L, daysAgo(500)));
    }
}
