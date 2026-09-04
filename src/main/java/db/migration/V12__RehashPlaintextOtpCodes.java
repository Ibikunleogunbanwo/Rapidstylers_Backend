package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Data migration: re-hashes legacy plaintext OTP codes still sitting in
 * {@code otp_codes.code}.
 *
 * <p>OTPs are now always stored as BCrypt hashes (see AppUtils.hashOtp), but
 * rows written before that change hold the raw 6-digit code. A leaked copy of
 * the table would expose those directly, so this migration encodes every row
 * that does not already look like a BCrypt hash. Rows already hashed (prefix
 * {@code $2}) are left untouched, making the migration safe and idempotent.
 *
 * <p>This must be a Java migration: MySQL has no BCrypt function, so the
 * hashing is done with the same encoder the application verifies with
 * (BCryptPasswordEncoder). Verified codes are intentionally NOT touched — only
 * the representation changes, so a code still inside its validity window keeps
 * verifying for the user who received it by email.
 */
public class V12__RehashPlaintextOtpCodes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String findPlaintext = "SELECT id, code FROM otp_codes "
                + "WHERE code IS NOT NULL AND code <> '' AND code NOT LIKE ?";
        String updateHashed = "UPDATE otp_codes SET code = ? WHERE id = ?";

        try (PreparedStatement find = context.getConnection().prepareStatement(findPlaintext);
             PreparedStatement update = context.getConnection().prepareStatement(updateHashed)) {

            find.setString(1, "$2%"); // BCrypt hashes always start with "$2"
            try (ResultSet rows = find.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    String plaintextCode = rows.getString(2);
                    update.setString(1, encoder.encode(plaintextCode));
                    update.setLong(2, id);
                    update.addBatch();
                }
                update.executeBatch();
            }
        }
    }
}
