package com.mtbs.auth.entity;

import com.mtbs.shared.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "permissions", schema = "public")
@SQLDelete(sql = "UPDATE public.permissions SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission extends AuditableEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column
    private String description;
}
