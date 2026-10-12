package com.macrotel.rapidstylers.engagement.repo;

import com.macrotel.rapidstylers.engagement.entity.AnnouncementEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepo extends JpaRepository<AnnouncementEntity, Long> {
    List<AnnouncementEntity> findTop200ByOrderBySentAtDescIdDesc();
}
