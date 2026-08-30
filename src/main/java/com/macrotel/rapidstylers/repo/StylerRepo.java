package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.StylerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

public interface StylerRepo extends JpaRepository<StylerEntity, Long> {
    @Query(value = "SELECT s FROM StylerEntity s WHERE s.emailAddress =:emailAddress AND s.status= '0'")
    Optional<StylerEntity> isEmailExist (@Param("emailAddress") String emailAddress);

    Optional<StylerEntity> findByEmailAddress(String emailAddress);

    Optional<StylerEntity> findByStylerId(String stylerId);

    /** Batch fetch used by search/discovery paths to avoid per-styler N+1 lookups. */
    List<StylerEntity> findByStylerIdIn(Collection<String> stylerIds);

    /** Serializes booking attempts for one stylist inside the booking transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StylerEntity s WHERE s.stylerId = :stylerId")
    Optional<StylerEntity> findByStylerIdForUpdate(@Param("stylerId") String stylerId);

    Optional<StylerEntity> findByStripeConnectAccountId(String stripeConnectAccountId);

    Optional<StylerEntity> findByPhoneNumber(String phoneNumber);

    @Query(value ="SELECT * FROM stylers WHERE business_name LIKE %:businessName%", nativeQuery = true)
    List<StylerEntity> searchStyler(@Param("businessName") String businessName);

    List<StylerEntity> findByServiceTypeId(String serviceTypeId);

    List<StylerEntity> findByProvinceIgnoreCase(String province);

    List<StylerEntity> findByCityIgnoreCase(String city);

    @Query(value = "SELECT * FROM stylers WHERE city =:city AND service_type_id =:serviceTypeId", nativeQuery = true)
    List<StylerEntity> findByCityAndServiceType(@Param("city") String city, @Param("serviceTypeId") String serviceTypeId);
}
