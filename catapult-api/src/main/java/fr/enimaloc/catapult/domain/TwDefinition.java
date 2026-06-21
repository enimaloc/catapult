package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tw_definition")
@Getter
@Setter
public class TwDefinition {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
