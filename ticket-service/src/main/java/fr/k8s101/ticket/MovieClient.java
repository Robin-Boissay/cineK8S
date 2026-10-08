package fr.k8s101.ticket;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP de movie-service.
 * L'URL de base vient de la propriété {@code movie.url} (variable d'environnement MOVIE_URL).
 */
@Component
public class MovieClient {

    public record Movie(long id, String title, String genre, BigDecimal price, int seats) {
    }

    private final RestClient restClient;

    public MovieClient(RestClient.Builder builder, @Value("${movie.url}") String movieUrl) {
        this.restClient = builder.baseUrl(movieUrl).build();
    }

    public Optional<Movie> findMovie(long id) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/api/movies/{id}", id)
                    .retrieve()
                    .body(Movie.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Appelle le endpoint liveness de movie-service ; lève une exception si injoignable. */
    public void ping() {
        restClient.get()
                .uri("/actuator/health/liveness")
                .retrieve()
                .toBodilessEntity();
    }
}
