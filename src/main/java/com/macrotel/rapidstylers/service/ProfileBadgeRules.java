package com.macrotel.rapidstylers.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The badges a professional's profile can carry, and exactly what earns each one.
 *
 * A badge is a claim the marketplace makes on someone's behalf, so every one of
 * them has to be backed by a fact the platform actually stores, and by enough of
 * that fact to mean something. Three of them earn their place:
 *
 *   TOP_RATED       a real record of good work. Requires an average of 4.7 or
 *                   higher across at least 10 approved reviews, because an
 *                   average taken over two or three reviews is noise (a single
 *                   3-star lands a three-review profile on 4.3). Ten reviews at
 *                   4.7 is at least 47 of a possible 50 rating points.
 *
 *   FIRST_BOOKING   work has actually been delivered. Requires at least one
 *                   completed appointment. This is not the same as the
 *                   appointment tally on the profile, which counts every request
 *                   including the ones still pending, cancelled or rejected —
 *                   this badge says a job was finished. It is held only while no
 *                   review exists, because once one lands the rating says more
 *                   than the milestone does.
 *
 *   NEW             an honest expectation-setter rather than an achievement:
 *                   nothing has happened yet (no approved reviews, no
 *                   appointments at all, not even a cancelled one) and the
 *                   account is inside the new-professional window.
 *
 * Deliberately not carried:
 *
 *   "Verified"      every listed professional has already cleared the approval
 *                   and address checks, so this badge would sit on 100% of
 *                   profiles and tell a visitor nothing while implying that the
 *                   unbadged are unverified.
 *   "Comes to you"  there is no stored flag for a professional offering home
 *                   visits, so nothing could earn it.
 *   "Established"   a second volume badge would restate the appointment tally
 *                   the profile already prints, in a second place.
 *
 * Only the first of these is a standing badge; NEW and FIRST_BOOKING are stages,
 * and their definitions are disjoint (NEW requires zero appointments, so it
 * cannot coexist with a completed booking). At most one stage badge is ever
 * shown, so a profile can never read "New" and "First booking completed" at once.
 *
 * The rules live here rather than in the client so every surface that shows a
 * profile — the public page today, cards and any future surface tomorrow — reads
 * the same answer from one implementation.
 *
 * Where a fact is missing the badge is not awarded. That direction matters: an
 * unprovable claim must never be made, and a badge that briefly goes unshown is
 * nothing compared with one that lies.
 */
public final class ProfileBadgeRules {

    public static final String TOP_RATED = "TOP_RATED";
    public static final String FIRST_BOOKING = "FIRST_BOOKING";
    public static final String NEW = "NEW";

    /** Review average a professional must hold for TOP_RATED. */
    public static final double TOP_RATED_MIN_AVERAGE = 4.7;

    /** Approved reviews a professional must hold for TOP_RATED, for a stable average. */
    public static final int TOP_RATED_MIN_REVIEWS = 10;

    /** How long an account still counts as new, in days. Three months. */
    public static final int NEW_WINDOW_DAYS = 90;

    private ProfileBadgeRules(){ }

    /**
     * @param reviewCount     approved reviews; null when the caller cannot prove it
     * @param averageRating   average of those reviews, or null
     * @param appointmentCount every appointment on record, any status; null when unknown
     * @param completedCount  appointments actually finished; null when unknown
     * @param dateRegistered  the join date the signup wrote ("2026-09-16")
     */
    public static List<String> earned(Long reviewCount, Double averageRating,
                                      Long appointmentCount, Long completedCount,
                                      String dateRegistered){
        return earned(reviewCount, averageRating, appointmentCount, completedCount,
                dateRegistered, LocalDate.now());
    }

    /** Same rules against an explicit "today", so the window can be tested. */
    public static List<String> earned(Long reviewCount, Double averageRating,
                                      Long appointmentCount, Long completedCount,
                                      String dateRegistered, LocalDate today){
        List<String> badges = new ArrayList<>();
        long reviews = reviewCount == null ? 0 : Math.max(0, reviewCount);

        // Standing: a record long enough and strong enough to mean something.
        if(averageRating != null
                && reviews >= TOP_RATED_MIN_REVIEWS
                && averageRating >= TOP_RATED_MIN_AVERAGE){
            badges.add(TOP_RATED);
        }

        // Stage: only while no review can speak for the professional instead.
        if(reviews == 0){
            if(completedCount != null && completedCount > 0){
                badges.add(FIRST_BOOKING);
            } else if(appointmentCount != null && appointmentCount == 0
                    && joinedWithinNewWindow(dateRegistered, today)){
                badges.add(NEW);
            }
        }

        return badges;
    }

    /**
     * Whether a stored registration date falls inside the new-professional
     * window. An unreadable date, a missing one, or one in the future returns
     * false: an unknown age cannot support a claim about how new someone is, and
     * treating unknown as "new" is the old, false claim in disguise.
     */
    public static boolean joinedWithinNewWindow(String dateRegistered, LocalDate today){
        if(dateRegistered == null || today == null){
            return false;
        }
        String day = dateRegistered.trim();
        if(day.length() > 10){
            // The column holds an ISO date, but accept a stored datetime too.
            day = day.substring(0, 10);
        }
        try{
            long ageDays = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(day), today);
            return ageDays >= 0 && ageDays <= NEW_WINDOW_DAYS;
        }
        catch(DateTimeParseException ex){
            return false;
        }
    }
}
