package com.macrotel.rapidstylers.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The words, places and hours behind the demo showroom, kept apart from
 * {@link DemoContentInitializer} so the seeding rules stay readable. Everything
 * here is fixed data plus a few pure lookups; nothing touches a repository.
 */
final class DemoSeedContent {

    private DemoSeedContent() {
    }

    /** Where a sample professional works: a street address and the matching map point. */
    record DemoLocation(String address, double latitude, double longitude) {
    }

    /**
     * Twenty-five work addresses on real Calgary commercial streets with
     * invented suites. Laid out five per category, and within each category one
     * per part of the city (downtown or inner city, NW, NE, SW, SE), so the five
     * professionals in a category are spread across town rather than stacked on
     * one block. Coordinates are the approximate point of each street address,
     * which is what nearby search and distance-based travel fees read.
     */
    static final DemoLocation[] LOCATIONS = {
        new DemoLocation("Suite 210, 815 1 St SW", 51.0457, -114.0652),          // Downtown
        new DemoLocation("Unit 8, 3630 Brentwood Rd NW", 51.0866, -114.1318),    // Brentwood
        new DemoLocation("Unit 14, 1440 52 St NE", 51.0655, -113.9618),          // Marlborough
        new DemoLocation("Suite 120, 2008 33 Ave SW", 51.0237, -114.1043),       // Marda Loop
        new DemoLocation("Unit 5, 1215 9 Ave SE", 51.0408, -114.0292),           // Inglewood

        new DemoLocation("Unit 4, 1111 11 Ave SW", 51.0412, -114.0868),          // Beltline
        new DemoLocation("Suite 220, 5005 Dalhousie Dr NW", 51.1029, -114.1611), // Dalhousie
        new DemoLocation("Suite 205, 3301 32 Ave NE", 51.0812, -113.9893),       // Sunridge
        new DemoLocation("Unit 3, 1935 37 St SW", 51.0362, -114.1391),           // Killarney
        new DemoLocation("Unit 8, 7740 18 St SE", 50.9845, -114.0068),           // Ogden

        new DemoLocation("Suite 305, 924 17 Ave SW", 51.0379, -114.0829),        // 17 Ave
        new DemoLocation("Unit 9, 40 Crowfoot Cres NW", 51.1227, -114.2068),     // Crowfoot
        new DemoLocation("Unit 11, 5075 Falconridge Blvd NE", 51.0992, -113.9493), // Falconridge
        new DemoLocation("Suite 150, 6455 Macleod Tr SW", 50.9986, -114.0738),   // Chinook
        new DemoLocation("Unit 9, 4307 130 Ave SE", 50.9306, -113.9671),         // South Trail

        new DemoLocation("Unit 2, 1240 Kensington Rd NW", 51.0528, -114.0918),   // Kensington
        new DemoLocation("Unit 3, 4820 Northland Dr NW", 51.0958, -114.1447),    // Northland
        new DemoLocation("Unit 7, 1919 Edmonton Tr NE", 51.0712, -114.0556),     // Tuxedo Park
        new DemoLocation("Unit 18, 5580 Signal Hill Ctr SW", 51.0178, -114.1722), // Signal Hill
        new DemoLocation("Suite 210, 9919 Fairmount Dr SE", 50.9668, -114.0483), // Acadia

        new DemoLocation("Suite 410, 333 5 Ave SW", 51.0490, -114.0668),         // Downtown
        new DemoLocation("Unit 6, 3500 Varsity Dr NW", 51.0880, -114.1480),      // Varsity
        new DemoLocation("Unit 6, 220 Saddletowne Cir NE", 51.1252, -113.9484),  // Saddle Ridge
        new DemoLocation("Unit 4, 2525 Woodview Dr SW", 50.9478, -114.1236),     // Woodbine
        new DemoLocation("Unit 11, 80 McKenzie Towne Ave SE", 50.9168, -113.9639) // McKenzie Towne
    };

    /**
     * Addresses an earlier version of the seeder handed out, all with downtown
     * coordinates. A seed-owned row still carrying one of these has never been
     * edited, so it is safe to move onto its own spot in {@link #LOCATIONS}.
     */
    static final Set<String> LEGACY_ADDRESSES = Set.of(
        "Suite 210, 102 8 Ave SW", "Unit 4, 1414 8 St SW", "Suite 320, 1100 1 St SE",
        "Unit 12, 3510 17 Ave SW", "Suite 200, 1301 16 Ave NW", "Unit 8, 404 10 St NW",
        "Suite 410, 888 3 St SW", "Unit 3, 1935 37 St SW", "Suite 150, 6005 3 St SW",
        "Unit 20, 4800 16 Ave NW", "Suite 220, 222 7 St SW", "Unit 14, 2116 33 Ave SW",
        "Suite 305, 924 17 Ave SW", "Unit 9, 5009 16 Ave NW", "Suite 180, 110 9 Ave SW",
        "Unit 6, 1010 1 Ave NE"
    );

    /**
     * Weekly hours, one profile per stylist: weekday, Saturday and Sunday
     * windows. Every window is at least eight hours so the booking calendar
     * shows plenty of open times on all seven days, and the profiles differ so
     * the showroom does not read as one schedule copied everywhere.
     */
    private static final String[][][] WEEKLY_HOURS = {
        {{"08:00", "20:00"}, {"09:00", "18:00"}, {"10:00", "18:00"}},
        {{"09:00", "21:00"}, {"08:00", "18:00"}, {"10:00", "19:00"}},
        {{"07:00", "19:00"}, {"08:00", "17:00"}, {"09:00", "17:00"}},
        {{"10:00", "22:00"}, {"09:00", "20:00"}, {"11:00", "19:00"}},
        {{"08:30", "20:30"}, {"09:00", "19:00"}, {"10:00", "18:00"}}
    };

    /** The start and end time for one day (0 = Sunday ... 6 = Saturday) of a stylist's week. */
    static String[] hoursFor(String stylerId, int dayOfWeek) {
        String[][] profile = WEEKLY_HOURS[Math.floorMod(String.valueOf(stylerId).hashCode(), WEEKLY_HOURS.length)];
        if (dayOfWeek == 0) return profile[2].clone();
        if (dayOfWeek == 6) return profile[1].clone();
        return profile[0].clone();
    }

    /** The two windows the earlier seeder wrote: Tuesday 10-5 and Saturday 9-4. */
    static boolean isLegacySeedWindow(String day, String start, String end) {
        return ("2".equals(day) && "10:00".equals(start) && "17:00".equals(end))
                || ("6".equals(day) && "09:00".equals(start) && "16:00".equals(end));
    }

    /* ── reviews ───────────────────────────────────────────────────────────── */

    /**
     * Review text by category and score. "%s" is the professional's first name.
     * Written the way clients actually write: specific about the service,
     * honest about small annoyances, and never about a neighbourhood, so every
     * line fits wherever the professional works.
     */
    private static final String[][] NAIL = {
        {
            "%s did a gel manicure that lasted three full weeks with no lifting. The shape was exactly what I showed in my photo.",
            "Super tidy cuticle work and %s never rushed me. Booked my next fill before I left.",
            "Came in with chipped acrylics from somewhere else. %s soaked them off gently and my nails actually feel healthy now.",
            "Pedicure was relaxing and thorough. %s noticed a rough patch on my heel and sorted it without making it awkward.",
            "Simple nude set for a wedding and it photographed beautifully. %s talked me out of a length I would have regretted."
        },
        {
            "Lovely clean manicure. Took a bit longer than booked, but %s let me know up front.",
            "Great colour match and neat lines. I just wish there were more evening slots on weekends."
        },
        {
            "Polish looked great at first but one nail chipped after four days. %s offered a fix, I just could not get back in time."
        }
    };

    private static final String[][] LASH = {
        {
            "Classic set looks natural, which is exactly what I asked for. %s checked the mapping with me before starting.",
            "First lash appointment ever and %s explained everything, including how to wash them. Zero irritation.",
            "Honestly fell asleep on the bed. The refill took under an hour and my lashes look full again.",
            "%s is careful with isolation, none of my lashes are clumped. Retention has been great for three weeks.",
            "I have sensitive eyes and %s switched to a gentler adhesive the moment I mentioned it."
        },
        {
            "Really pretty set. A little heavier on the outer corners than I expected, but it has grown on me.",
            "Good retention and a calm studio. Parking right outside is tight at lunchtime."
        },
        {
            "The set looked nice but I lost more lashes than I hoped in the first week. %s was kind about it and offered a touch up."
        }
    };

    private static final String[][] BARBER = {
        {
            "Cleanest skin fade I have had in Calgary. %s took time on the blend and lined me up perfectly.",
            "Brought my seven year old for a first proper cut. %s was patient and kept things fun the whole time.",
            "Beard trim was spot on. Kept the length I wanted and cleaned up the neckline properly.",
            "%s asks how you style it at home before cutting. Small thing, makes a big difference.",
            "On time, quick, and the fade still looked sharp at the two week mark."
        },
        {
            "Solid cut and good conversation. Waited about ten minutes past my booking time.",
            "Fade was great. The top came out a touch shorter than I like, I will be clearer next time."
        },
        {
            "Decent cut but the end felt a bit rushed, and the line up was slightly uneven on one side."
        }
    };

    private static final String[][] MAKEUP = {
        {
            "%s did my makeup for my sister's wedding and it lasted from 10am through the last dance.",
            "Soft glam exactly like my reference photo. My skin still looked like skin, not a mask.",
            "%s matched my foundation perfectly, which never happens for me. I loved every photo.",
            "Booked a lesson and finally learned a winged liner that does not come out lopsided.",
            "Calm, organized and on time for a 6am bridal start. Everyone in the bridal party looked like themselves, just polished."
        },
        {
            "Gorgeous look. The lashes started lifting late in the evening, but everything else held up well.",
            "Really happy overall. I would have liked a little more blush and should have just said so."
        },
        {
            "Pretty result, but it came out heavier than the soft glam I asked for and there was no time left to tone it down."
        }
    };

    private static final String[][] HAIR = {
        {
            "%s gave me a silk press that stayed bouncy for over a week, with no heat damage.",
            "Knotless braids were neat, light and done faster than I expected. My scalp felt fine from day one.",
            "My loc retwist looks so clean. %s was gentle with my edges and gave good tips for between visits.",
            "Finally someone who knows how to handle my 4C hair without making me feel like a problem.",
            "%s listened to how much time I have in the mornings and gave me a style that works with it."
        },
        {
            "Beautiful braids, but the appointment ran about an hour longer than quoted.",
            "Silk press looked great. A little more heat than I usually like, but %s checked in throughout."
        },
        {
            "The braids looked good but they were tighter than I wanted and my scalp was sore for two days."
        }
    };

    /** Lines that fit any category, mixed in so no two profiles read alike. */
    private static final String[][] GENERAL = {
        {
            "Booked last minute and still got full attention. Nothing felt rushed and the result held up for weeks.",
            "Second visit now. Same care as the first, and %s remembered exactly how I like it.",
            "Calm space, no upselling, and I left with a look I can actually keep up at home.",
            "First time I have booked my next appointment before even leaving the chair.",
            "Easy to book, easy to find, and %s was ready right on time."
        },
        {
            "Good work at a fair price. We started about ten minutes late, with a heads up by text.",
            "Solid visit start to finish. The studio is small, so it gets warm on summer afternoons."
        },
        {
            "The work was good, but we ran well past my booked time and I was late getting back to work."
        }
    };

    /**
     * Text the earlier seeder used. Several assumed every professional was a
     * woman working downtown, which reads wrong on a barber in the NE. A seeded
     * review still carrying one of these is rewritten; anything else is left as is.
     */
    static final Set<String> LEGACY_REVIEW_MESSAGES = Set.of(
        "Booked late and still got her full attention. Nothing felt rushed and the finish held up for weeks.",
        "She asked what I actually wanted before touching anything, then said honestly what would work and what would not.",
        "Second visit. Same care as the first, and she remembered exactly how I like it finished.",
        "Calm space, no upselling, and I left with something I can actually keep up at home.",
        "Walked in with a photo and a bad experience from another place. She fixed it and explained every step.",
        "Ran a little over because she was fixing something nobody else had noticed. Worth the extra ten minutes.",
        "My daughter is picky about everything and she left happy. That is the whole review.",
        "First time I have booked the next appointment before leaving instead of thinking about it for a week.",
        "Really happy with the result. Not a five only because parking downtown cost me fifteen minutes.",
        "Good work at a fair price. We started about ten minutes late, which she warned me about by text.",
        "She clearly knows the craft and took her time. I would have liked a bit more guidance on aftercare.",
        "Solid visit start to finish. The space is small, so it gets warm in the afternoon.",
        "The work was good, but we ran well past the time I booked, so I was late getting back to work.",
        "Neat work and friendly, though I asked for a shorter finish and it came out longer than we agreed."
    );

    /** The short category word used to pick reviews: nail, lash, barber, makeup or hair. */
    static String categoryOf(String serviceTypeName) {
        String name = serviceTypeName == null ? "" : serviceTypeName.toLowerCase(Locale.ROOT);
        if (name.contains("nail")) return "nail";
        if (name.contains("lash")) return "lash";
        if (name.contains("barber") || name.contains("fade") || name.contains("clipper")) return "barber";
        if (name.contains("makeup")) return "makeup";
        return "hair";
    }

    /**
     * One review's text. Deterministic for a key, so a restart never rewrites a
     * professional's reviews. Lines that use the name are skipped when the
     * profile has none.
     */
    static String reviewMessage(String category, int rating, int key, String firstName) {
        int tier = rating >= 5 ? 0 : (rating == 4 ? 1 : 2);
        boolean hasName = firstName != null && !firstName.isBlank();
        List<String> pool = new ArrayList<>();
        for (String line : poolFor(category)[tier]) {
            if (hasName || !line.contains("%s")) pool.add(line);
        }
        for (String line : GENERAL[tier]) {
            if (hasName || !line.contains("%s")) pool.add(line);
        }
        String line = pool.get(Math.floorMod(key, pool.size()));
        return hasName ? String.format(Locale.ROOT, line, firstName.trim()) : line;
    }

    private static String[][] poolFor(String category) {
        return switch (category) {
            case "nail" -> NAIL;
            case "lash" -> LASH;
            case "barber" -> BARBER;
            case "makeup" -> MAKEUP;
            default -> HAIR;
        };
    }
}
