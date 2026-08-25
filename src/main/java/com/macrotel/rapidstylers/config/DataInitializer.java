package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.BlogPostEntity;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.ReviewEntity;
import com.macrotel.rapidstylers.repo.BlogPostRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES;

/**
 * Seeds starter data on first run:
 *   - 5 service type categories (Nail Technician, Eyelash Technician, Barber, Hairstylist, Makeup Artist)
 *   - 4 blog articles
 */
@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private BlogPostRepo blogPostRepo;

    @Autowired
    private ServiceRepo serviceRepo;

    @Autowired
    private BookAppointmentRepo bookAppointmentRepo;

    @Autowired
    private SubServiceRepo subServiceRepo;

    @Autowired
    private ReviewRepo reviewRepo;

    @Override
    public void run(String... args) {
        normalizeLegacyAppointments();
        normalizeLegacyServices();
        normalizeLegacyReviews();
        seedServiceTypes();
        seedBlogPosts();
    }

    /** Backfills canonical temporal columns for appointments created before the typed model. */
    private void normalizeLegacyAppointments() {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
        List<BookAppointmentEntity> appointments = bookAppointmentRepo.findAll();
        for (BookAppointmentEntity appointment : appointments) {
            try {
                if (appointment.getAppointmentDateValue() == null) {
                    appointment.setAppointmentDateValue(LocalDate.parse(appointment.getAppointmentDate()));
                }
                if (appointment.getAppointmentStartTime() == null) {
                    appointment.setAppointmentStartTime(LocalTime.parse(
                            appointment.getArrivalTime().trim().toUpperCase(Locale.ENGLISH), timeFormatter));
                }
                if (appointment.getDurationMinutes() == null) {
                    appointment.setDurationMinutes(DEFAULT_SERVICE_DURATION_MINUTES);
                }
                if (appointment.getAppointmentEndTime() == null) {
                    appointment.setAppointmentEndTime(appointment.getAppointmentStartTime()
                            .plusMinutes(appointment.getDurationMinutes()));
                }
                if (appointment.getAppointmentDateValue() != null
                        && appointment.getAppointmentStartTime() != null
                        && appointment.getAppointmentEndTime() != null) {
                    bookAppointmentRepo.save(appointment);
                }
            } catch (Exception ex) {
                // Do not prevent startup; booking validation fails closed for this row.
                System.err.println("[DataInitializer] Could not normalize appointment "
                        + appointment.getAppointmentId() + ": " + ex.getMessage());
            }
        }
    }

    /** Legacy reviews predate moderation; preserve their existing public visibility. */
    private void normalizeLegacyReviews() {
        reviewRepo.findAll().forEach(review -> {
            if (review.getModerationStatus() == null || review.getModerationStatus().isBlank()) {
                review.setModerationStatus("APPROVED");
                reviewRepo.save(review);
            }
        });
    }

    /** Gives pre-existing services the backward-compatible default duration. */
    private void normalizeLegacyServices() {
        subServiceRepo.findAll().forEach(service -> {
            if (service.getDurationMinutes() == null) {
                service.setDurationMinutes(DEFAULT_SERVICE_DURATION_MINUTES);
                subServiceRepo.save(service);
            }
        });
    }

    private void seedServiceTypes() {
        if (serviceRepo.count() > 0) {
            return;
        }
        String[] categories = {
            "Nail Technician",
            "Eyelash Technician",
            "Barber",
            "Hairstylist",
            "Makeup Artist"
        };
        for (String name : categories) {
            try {
                ServiceEntity entity = new ServiceEntity();
                entity.setServiceName(name);
                entity.setDescription("Professional " + name.toLowerCase() + " services");
                serviceRepo.save(entity);
                System.out.println("[DataInitializer] Seeded service type: " + name);
            } catch (Exception ex) {
                System.err.println("[DataInitializer] Failed to seed service type '" + name + "': " + ex.getMessage());
            }
        }
    }

    private void seedBlogPosts() {
        if (blogPostRepo.count() > 0) {
            return;
        }
        seedBlog("The Ultimate Guide to Braiding: From Basic to Intricate Styles", "Braiding",
                "Braids are one of the most versatile protective styles you can wear. Whether you are new to braiding or have been doing it for years, there is always something new to learn about technique, maintenance and styling.\n\n"
                        + "Start with the basics: box braids, cornrows and plaits form the foundation of most intricate looks. Each style starts the same way, with clean, detangled hair and a light leave-in conditioner. Section the hair into even parts and keep tension consistent from root to tip so the braid lies flat and lasts longer.\n\n"
                        + "For intricate styles like feed-in braids, knotless braids or braided updos, patience is everything. Work in small sections, use a good edge control for a clean hairline, and seal the ends with hot water or a product of your choice.\n\n"
                        + "Maintenance matters just as much as the braiding itself. Sleep with a satin scarf or bonnet, oil your scalp every few days, and wash your braids with a diluted shampoo every couple of weeks. With the right care, a great set of braids can last four to eight weeks and keep your natural hair protected underneath.",
                "https://img.freepik.com/free-photo/ai-generated-cute-girl-pic_23-2150649874.jpg?w=826");
        seedBlog("Quick and Easy Hairstyles for Busy Mornings", "Styling",
                "Mornings are short and your hair should not be the reason you run late. With a few simple styles in your back pocket, you can look polished in five minutes flat.\n\n"
                        + "A sleek low bun never fails. Brush the hair back, smooth it down with a little oil or gel, and twist it into a bun at the nape of your neck. Secure with a scrunchie and you are done.\n\n"
                        + "The claw clip is your best friend for busy days. Gather your hair loosely, twist it upward and clip it in place. Leave a few face-framing pieces out for a relaxed, effortless look.\n\n"
                        + "Half up, half down is another quick win. Take the top half of your hair, secure it with a clip or band, and let the rest fall naturally. It works on almost every hair length and texture.\n\n"
                        + "The secret to all of these styles is preparation. Keep a few dry shampoos, oils and accessories on hand, and give your hair a quick detangle the night before. Ten minutes of prep saves you twenty in the morning.",
                "https://img.freepik.com/free-photo/side-view-woman-styling-hair_23-2149659566.jpg?t=st=1708868604~exp=1708872204~hmac=a724d6651959e05a587b791dba7dbab024b8dc529d20566c14741d134583e345&w=826");
        seedBlog("Healthy Hair Tips: Essential Care and Maintenance Guide", "Hair Care",
                "Healthy hair starts with a healthy routine, and the basics matter more than expensive products. Hydration, gentle handling and consistency will take your hair further than any miracle bottle.\n\n"
                        + "Wash according to your hair type, not a fixed schedule. Oily scalps may need washing every few days, while dry or curly textures often do better every one to two weeks. Always follow with a conditioner and let your hair air dry when you can.\n\n"
                        + "Moisture is the foundation of elasticity. Use a leave-in conditioner or light oil on damp hair, and seal it in with a cream or butter if your hair is particularly dry. Deep conditioning once a month keeps strands strong and soft.\n\n"
                        + "Trim regularly. Even if you are growing your hair out, a trim every eight to twelve weeks removes split ends before they travel up the strand.\n\n"
                        + "Finally, protect your hair while you sleep. A satin pillowcase or bonnet reduces friction and prevents breakage, and it is the single easiest change you can make for healthier hair.",
                "https://img.freepik.com/free-photo/medium-shot-woman-arranging-hair_23-2149634993.jpg?t=st=1708868767~exp=1708872367~hmac=44c9b42f97f98a74588368862e364a6e2f7938b61e1563dcc8fd3ef51b42be57&w=826");
        seedBlog("Short and Chic: Modern Hairstyles for Short Haircuts", "Trends",
                "Short hair is having a moment, and it is easier to style than most people think. From sleek bobs to bold pixies, there is a short cut for every face shape and personality.\n\n"
                        + "The classic bob sits anywhere from the chin to the shoulders and frames the face beautifully. Ask your stylist for a cut that suits your texture, whether that is blunt, layered or angled.\n\n"
                        + "The pixie is the ultimate statement cut. It is low maintenance, dries in minutes, and pairs well with bold accessories. A little texturizing spray gives it that effortless, piecey finish.\n\n"
                        + "The shag works on short and medium lengths alike. Layers and soft fringe add movement, making thin hair look fuller and thick hair easier to manage.\n\n"
                        + "Short hair needs regular trims every four to six weeks to hold its shape, but the styling time you save is worth it. Talk to your stylist about what works for your hair type and lifestyle, and do not be afraid to try something new.",
                "https://img.freepik.com/free-photo/cool-girl-with-short-hair-looking-into-camera-background-white-backdrop-brunette-lady-with-glass-beige-outside-posing-backdrop-wall_197531-29357.jpg?t=st=1708868867~exp=1708872467~hmac=8acc269316521de8d8e9ca7cf302d3ef600de561bee3350a281a67bd7644845a&w=826");
    }

    private void seedBlog(String title, String category, String content, String imageUrl) {
        try {
            BlogPostEntity post = new BlogPostEntity();
            post.setTitle(title);
            post.setCategory(category);
            post.setContent(content);
            post.setImageUrl(imageUrl);
            post.setAuthor("RapidStylers Team");
            blogPostRepo.save(post);
        } catch (Exception ex) {
            System.err.println("[DataInitializer] Failed to seed blog post '" + title + "': " + ex.getMessage());
        }
    }
}
