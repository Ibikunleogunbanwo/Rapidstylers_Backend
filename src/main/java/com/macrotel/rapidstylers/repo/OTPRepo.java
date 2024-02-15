package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.OTPEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OTPRepo extends JpaRepository<OTPEntity,Long> {
    @Query(value = "SELECT * FROM otp_codes " +
                    "WHERE email_address =:emailAddress AND purpose='USER SIGN UP' AND is_used='1' ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<OTPEntity> checkSignUpValidityOtp(@Param("emailAddress") String emailAddress);

    Optional<OTPEntity> findByCode(String otpCode);
}
