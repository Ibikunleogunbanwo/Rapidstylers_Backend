package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.OTPEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OTPRepo extends JpaRepository<OTPEntity,Long> {
    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose=:purpose AND is_used='1' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> checkSignUpValidityOtp(@Param("emailAddress") String emailAddress, @Param("purpose") String purpose);

    /**
     * Most recent unverified (is_used='1') OTP for an email, any purpose.
     * Purpose is validated at the service layer, and the code is compared
     * against its BCrypt hash — the plaintext is never stored.
     */
    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND is_used='1' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> findLatestUnusedByEmail(@Param("emailAddress") String emailAddress);

    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose='USER SIGN UP' AND is_used='0' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> verifyOtpSuccess(@Param("emailAddress") String emailAddress);

    /** Most recently verified (consumed) OTP for an email + purpose. */
    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose=:purpose AND is_used='0' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> verifyOtpSuccessForPurpose(@Param("emailAddress") String emailAddress, @Param("purpose") String purpose);

    /**
     * For every email that has started a sign-up and still has no user_accounts
     * row: the email, the latest sign-up attempt's insertedDt ("uuuu-MM-dd
     * HH:mm:ss") and that latest attempt's followup_stage. The latest row is
     * the authoritative attempt (a new sign-up resets the stage to 0).
     */
    @Query(value = "SELECT o.email_address AS email, " +
                    "(SELECT o2.inserted_dt FROM otp_codes o2 " +
                    "  WHERE o2.email_address = o.email_address AND o2.purpose='USER SIGN UP' ORDER BY o2.id DESC LIMIT 1) AS attempt_dt, " +
                    "COALESCE((SELECT o3.followup_stage FROM otp_codes o3 " +
                    "  WHERE o3.email_address = o.email_address AND o3.purpose='USER SIGN UP' ORDER BY o3.id DESC LIMIT 1), 0) AS stage " +
                    "FROM otp_codes o WHERE o.purpose = 'USER SIGN UP' " +
                    "GROUP BY o.email_address " +
                    "HAVING NOT EXISTS (SELECT 1 FROM user_accounts u WHERE u.email_address = o.email_address)", nativeQuery = true)
    List<Object[]> findSignupAttemptCandidates();

    /** Records that the latest sign-up attempt for an email reached a follow-up stage. */
    @Modifying
    @Query(value = "UPDATE otp_codes SET followup_stage = :stage, followup_updated_at = :now WHERE id = ( " +
                    "  SELECT id FROM (SELECT id FROM otp_codes " +
                    "    WHERE email_address = :email AND purpose='USER SIGN UP' ORDER BY id DESC LIMIT 1) t)", nativeQuery = true)
    void markLatestSignupStage(@Param("email") String email, @Param("stage") Integer stage, @Param("now") LocalDateTime now);

    /**
     * Recovery-campaign rows for the admin funnel view: for each email that has
     * started a sign-up, the latest attempt time, the stage reached, the time
     * that stage's email was last sent, and whether a user_accounts row now
     * exists (i.e. the person converted). Result: {email, attempt_dt, stage,
     * last_sent, converted}.
     */
    @Query(value = "SELECT o.email_address AS email, " +
                    "(SELECT o2.inserted_dt FROM otp_codes o2 " +
                    "  WHERE o2.email_address = o.email_address AND o2.purpose='USER SIGN UP' ORDER BY o2.id DESC LIMIT 1) AS attempt_dt, " +
                    "COALESCE((SELECT o3.followup_stage FROM otp_codes o3 " +
                    "  WHERE o3.email_address = o.email_address AND o3.purpose='USER SIGN UP' ORDER BY o3.id DESC LIMIT 1), 0) AS stage, " +
                    "(SELECT o4.followup_updated_at FROM otp_codes o4 " +
                    "  WHERE o4.email_address = o.email_address AND o4.purpose='USER SIGN UP' ORDER BY o4.id DESC LIMIT 1) AS last_sent, " +
                    "EXISTS(SELECT 1 FROM user_accounts u WHERE u.email_address = o.email_address) AS converted " +
                    "FROM otp_codes o WHERE o.purpose = 'USER SIGN UP' " +
                    "GROUP BY o.email_address " +
                    "ORDER BY attempt_dt DESC", nativeQuery = true)
    List<Object[]> findRecoveryCampaigns();

    /** Purges OTP rows older than the retention cutoff. */
    @Modifying
    @Query(value = "DELETE FROM otp_codes WHERE inserted_dt < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") String cutoff);
}