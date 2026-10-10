package com.macrotel.rapidstylers.payment.repo;

import com.macrotel.rapidstylers.payment.entity.PlatformSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformSettingRepo extends JpaRepository<PlatformSettingEntity, Long> {
    Optional<PlatformSettingEntity> findBySettingKey(String settingKey);
}
