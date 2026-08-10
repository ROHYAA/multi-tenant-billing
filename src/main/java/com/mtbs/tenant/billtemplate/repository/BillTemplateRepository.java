package com.mtbs.tenant.billtemplate.repository;

import com.mtbs.tenant.billtemplate.entity.BillTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillTemplateRepository extends JpaRepository<BillTemplate, Long> {

    List<BillTemplate> findAllByIsActiveTrue();

    Optional<BillTemplate> findByCode(String code);
}
