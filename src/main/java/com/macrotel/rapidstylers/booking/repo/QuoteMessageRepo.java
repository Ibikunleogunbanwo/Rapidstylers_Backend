package com.macrotel.rapidstylers.booking.repo;

import com.macrotel.rapidstylers.booking.entity.QuoteMessageEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteMessageRepo extends JpaRepository<QuoteMessageEntity, Long> {
    List<QuoteMessageEntity> findByQuoteRefOrderByIdAsc(String quoteRef);

    List<QuoteMessageEntity> findByQuoteRefInOrderByIdAsc(Collection<String> quoteRefs);

    long countByQuoteRef(String quoteRef);
}
