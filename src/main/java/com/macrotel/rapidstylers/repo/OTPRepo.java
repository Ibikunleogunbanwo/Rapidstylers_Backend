package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.OTPEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OTPRepo extends JpaRepository<OTPEntity,Long> {
    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose=:purpose AND is_used='1' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> checkSignUpValidityOtp(@Param("emailAddress") String emailAddress, @Param("purpose") String purpose);

    Optional<OTPEntity> findByCode(String otpCode);

    @Query(value = "SELECT o FROM OTPEntity o WHERE o.code =:otpCode AND o.isUsed='1'")
    Optional<OTPEntity> checkUserCode(@Param("otpCode") String otpCode);

    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose='USER SIGN UP' AND is_used='0' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> verifyOtpSuccess(@Param("emailAddress") String emailAddress);

    /** Most recently verified (consumed) OTP for an email + purpose. */
    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose=:purpose AND is_used='0' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> verifyOtpSuccessForPurpose(@Param("emailAddress") String emailAddress, @Param("purpose") String purpose);

}
