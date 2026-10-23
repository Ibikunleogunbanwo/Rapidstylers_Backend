package com.macrotel.rapidstylers.booking.repo;

import com.macrotel.rapidstylers.booking.entity.QuoteEntity;
import java.util.List;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuoteRepo extends JpaRepository<QuoteEntity, Long> {
    Optional<QuoteEntity> findByQuoteRef(String quoteRef);

    /** Locks the row, so a quote is accepted once and cannot be re-priced mid-checkout. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuoteEntity q where q.quoteRef = :quoteRef")
    Optional<QuoteEntity> findByQuoteRefForUpdate(@Param("quoteRef") String quoteRef);

    List<QuoteEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<QuoteEntity> findByStylerIdOrderByCreatedAtDesc(String stylerId);

    List<QuoteEntity> findByUserIdAndStatusIn(String userId, List<String> statuses);
}
