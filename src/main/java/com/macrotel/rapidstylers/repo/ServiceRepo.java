package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceRepo extends JpaRepository<ServiceEntity,Long> {
    Optional<ServiceEntity> findByServiceName(String serviceName);
}
