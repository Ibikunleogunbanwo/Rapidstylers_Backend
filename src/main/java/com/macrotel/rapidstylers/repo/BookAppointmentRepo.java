package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookAppointmentRepo extends JpaRepository<BookAppointmentEntity, Long> {
}
