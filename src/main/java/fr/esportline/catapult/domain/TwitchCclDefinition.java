package fr.esportline.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "twitch_ccl_definition")
@Getter
@Setter
public class TwitchCclDefinition {

    @Id
    private String id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Returns the set of description texts currently mapped — used by the admin template. */
    public Set<String> getMappedDescriptions() {
        return igdbMappings.stream()
            .map(IgdbRatingDescriptor::getDescription)
            .collect(Collectors.toSet());
    }

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "ccl_igdb_mapping",
        joinColumns = @JoinColumn(name = "twitch_ccl_id"),
        inverseJoinColumns = @JoinColumn(name = "igdb_rating_descriptor_id")
    )
    private Set<IgdbRatingDescriptor> igdbMappings = new HashSet<>();
}
