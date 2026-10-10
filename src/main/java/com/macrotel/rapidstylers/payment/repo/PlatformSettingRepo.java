package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.PlatformSettingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepo extends JpaRepository<PlatformSettingEntity, Long> {
    Optional<PlatformSettingEntity> findBySettingKey(String settingKey);
}
