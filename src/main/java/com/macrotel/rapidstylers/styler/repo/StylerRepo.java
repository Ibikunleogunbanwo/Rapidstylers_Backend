package com.macrotel.rapidstylers.styler.repo;

import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Ids of active, approved stylists, for admin announcements. */
    @Query("SELECT s.stylerId FROM StylerEntity s WHERE s.status = '0' AND s.verificationStatus = 'APPROVED' AND s.stylerId IS NOT NULL")
    List<String> findActiveApprovedStylerIds();

    /** The next stylists after an id that have an ID photo on file, oldest first (ID photo migration). */
    List<StylerEntity> findByIdGreaterThanAndIdentificationImageUrlIsNotNullOrderByIdAsc(Long id,
            org.springframework.data.domain.Pageable page);

    /** How many stylist records hold this exact image URL (used before destroying an asset). */
    long countByProfileImageUrlContaining(String fragment);

    long countByIdentificationImageUrlContaining(String fragment);

    long countByCoverImageUrlContaining(String fragment);

    Optional<StylerEntity> findByPhoneNumber(String phoneNumber);

    @Query(value ="SELECT * FROM stylers WHERE business_name LIKE %:businessName%", nativeQuery = true)
    List<StylerEntity> searchStyler(@Param("businessName") String businessName);

    List<StylerEntity> findByServiceTypeId(String serviceTypeId);

    List<StylerEntity> findByProvinceIgnoreCase(String province);

    List<StylerEntity> findByCityIgnoreCase(String city);

    @Query(value = "SELECT * FROM stylers WHERE city =:city AND service_type_id =:serviceTypeId", nativeQuery = true)
    List<StylerEntity> findByCityAndServiceType(@Param("city") String city, @Param("serviceTypeId") String serviceTypeId);
}
