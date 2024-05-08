package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookAppointmentRepo extends JpaRepository<BookAppointmentEntity, Long> {
    List<BookAppointmentEntity> findByUserId(String userId);

    @Query(value = "SELECT b FROM BookAppointmentEntity b WHERE b.userId =:userId AND b.status ='1'")
    List<BookAppointmentEntity> userPendingAppointment(@Param("userId") String userId);

    List<BookAppointmentEntity> findByStylerId(String stylerId);
}
