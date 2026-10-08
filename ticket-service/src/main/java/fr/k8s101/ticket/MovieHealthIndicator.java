package fr.k8s101.ticket;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Indicateur de santé "movie" : UP si movie-service répond, DOWN sinon.
 * Le nom du bean ("movie") est le nom sous lequel il peut être référencé dans un groupe de santé.
 */
@Component("movie")
public class MovieHealthIndicator implements HealthIndicator {

    private final MovieClient movieClient;

    public MovieHealthIndicator(MovieClient movieClient) {
        this.movieClient = movieClient;
    }

    @Override
    public Health health() {
        try {
            movieClient.ping();
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
