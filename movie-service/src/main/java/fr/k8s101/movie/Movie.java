package fr.k8s101.movie;

import java.math.BigDecimal;

/**
 * Un film à l'affiche.
 *
 * @param seats nombre de places encore disponibles
 */
public record Movie(long id, String title, String genre, BigDecimal price, int seats) {
}
