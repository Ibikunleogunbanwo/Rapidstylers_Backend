package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.security.access.Roles;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which uploads belong to whom.
 *
 * <p>Every upload signature puts the file in a folder named after the caller:
 * {@code rapid_stylers/profile/signup_3f9a.../photo}. The name is a keyed hash
 * of the caller (the sign-up email, or the account id), so it says nothing
 * about them to someone reading a URL and cannot be guessed. A caller may only
 * delete files in their own folder, and a stylist's sign-up may only use photos
 * from the folder of the email they verified.
 *
 * <p>Uploads from before this change have no owner folder. They are still
 * accepted where they are already used, but only an admin may delete one.
 */
@Component
public class UploadOwnership {

    private static final Pattern OWNER_FOLDER = Pattern.compile("/(signup|acct)_[0-9a-f]{16}/");

    @Value("${cloudinary.api-secret:}")
    String secret = "";

    /** The owner folder name for a caller: signup_... for a sign-up pass, acct_... for an account. */
    public String folderFor(String role, String subject) {
        if (role == null || subject == null || subject.isBlank()) {
            return null;
        }
        String kind = Roles.SIGNUP_UPLOAD.equals(role) ? "signup_" : "acct_";
        return kind + hash(role + ":" + subject.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether the public id sits in this caller's own folder. */
    public boolean owns(String role, String subject, String publicId) {
        String folder = folderFor(role, subject);
        return folder != null && publicId != null && publicId.contains("/" + folder + "/");
    }

    /** Whether the public id was uploaded into some owner's folder (newer uploads all are). */
    public static boolean hasOwnerFolder(String publicId) {
        return publicId != null && OWNER_FOLDER.matcher(publicId).find();
    }

    /** Whether the public id came from a sign-up pass, so it may be abandoned. */
    public static boolean isSignupUpload(String publicId) {
        return publicId != null && publicId.contains("/signup_") && hasOwnerFolder(publicId);
    }

    private String hash(String value) {
        try {
            String key = secret == null || secret.isBlank() ? "rapidstylers-uploads" : secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not name the upload folder", ex);
        }
    }
}
