package fr.esportline.catapult.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IgdbGameExternalIdPk implements Serializable {
    private String igdbId;
    private long sourceId;
}
