package com.macrotel.rapidstylers.styler.repo;

import com.macrotel.rapidstylers.styler.entity.StylerPortfolioEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StylerPortfolioRepo extends JpaRepository<StylerPortfolioEntity, Long> {
    @Query("SELECT s FROM StylerPortfolioEntity s WHERE s.stylerId =:stylerId AND s.name =:portfolioName")
    Optional<StylerPortfolioEntity> isPortfolioExist(@Param("stylerId") String stylerId, @Param("portfolioName") String portfolioName);

    List<StylerPortfolioEntity> findByStylerId(String stylerId);

    List<StylerPortfolioEntity> findByCategory(String category);

    /** How many portfolio rows hold this exact image URL (used before destroying an asset). */
    long countByImageUrlContaining(String fragment);
}
