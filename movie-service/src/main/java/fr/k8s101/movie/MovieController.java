package fr.k8s101.movie;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/movies")
public class MovieController {

    private static final List<Movie> MOVIES = List.of(
            new Movie(1, "Pod Fiction", "Thriller", new BigDecimal("10.50"), 80),
            new Movie(2, "Le Seigneur des Pods", "Fantasy", new BigDecimal("12.00"), 3),
            new Movie(3, "Docker Wars", "Science-fiction", new BigDecimal("9.00"), 150),
            new Movie(4, "Rollback to the Future", "Comédie", new BigDecimal("8.50"), 0));

    private final String environment;

    public MovieController(@Value("${movie.environment}") String environment) {
        this.environment = environment;
    }

    @GetMapping
    public List<Movie> all() {
        return MOVIES;
    }

    @GetMapping("/{id}")
    public Movie byId(@PathVariable long id) {
        return MOVIES.stream()
                .filter(movie -> movie.id() == id)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Film " + id + " introuvable"));
    }

    // Utile pour voir quel Pod a répondu lorsqu'on scale le Deployment
    @GetMapping("/whoami")
    public Map<String, String> whoami() {
        return Map.of("hostname", hostname(), "environment", environment);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
