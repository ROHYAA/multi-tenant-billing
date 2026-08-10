package com.mtbs.tenant.numbering.repository;

import com.mtbs.tenant.numbering.entity.NumberSeries;
import com.mtbs.tenant.numbering.enums.NumberSeriesType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NumberSeriesRepository extends JpaRepository<NumberSeries, Long> {

    /**
     * PESSIMISTIC_WRITE row lock — the standard, portable JPA way to
     * serialize concurrent "read current value, increment, write it back"
     * sequences. Must be called inside a @Transactional method; the lock
     * is held until that transaction commits. This is what actually fixes
     * the race condition BillService's old COUNT(*)-based numbering had.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM NumberSeries n WHERE n.seriesType = :seriesType AND n.isActive = true")
    Optional<NumberSeries> findBySeriesTypeAndIsActiveTrueForUpdate(@Param("seriesType") NumberSeriesType seriesType);

    Optional<NumberSeries> findBySeriesTypeAndIsActiveTrue(NumberSeriesType seriesType);
}
