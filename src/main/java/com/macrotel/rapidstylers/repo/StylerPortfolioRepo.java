package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.StylerPortfolioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StylerPortfolioRepo extends JpaRepository<StylerPortfolioEntity, Long> {
    @Query("SELECT s FROM StylerPortfolioEntity s WHERE s.stylerId =:stylerId AND s.name =:portfolioName")
    Optional<StylerPortfolioEntity> isPortfolioExist(@Param("stylerId") String stylerId, @Param("portfolioName") String portfolioName);

    List<StylerPortfolioEntity> findByStylerId(String stylerId);

    List<StylerPortfolioEntity> findByCategory(String category);
}
