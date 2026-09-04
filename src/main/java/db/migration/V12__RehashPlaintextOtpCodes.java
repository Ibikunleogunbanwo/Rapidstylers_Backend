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
 *
 * <p>Like every other migration here, the run is guarded by an
 * {@code information_schema} existence check: on a fresh database the
 * {@code otp_codes} table does not exist yet (Hibernate {@code ddl-auto=update}
 * creates entity tables only AFTER Flyway runs), so the migration must be a
 * no-op there — a table that does not exist yet cannot hold legacy plaintext
 * rows to rehash.
 */
public class V12__RehashPlaintextOtpCodes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        if (!tableExists(context, "otp_codes")) {
            // Fresh schema: otp_codes will be created empty by Hibernate after
            // Flyway, so there is nothing to rehash. Matches the guarded-SQL
            // convention of V1-V11.
            return;
        }

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

    private boolean tableExists(Context context, String table) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = LOWER(?)";
        try (PreparedStatement ps = context.getConnection().prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }
}
