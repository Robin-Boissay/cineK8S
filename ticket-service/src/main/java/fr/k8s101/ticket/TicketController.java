package fr.k8s101.ticket;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final MovieClient movieClient;

    // État EN MÉMOIRE : propre à chaque instance (Pod) et perdu au redémarrage
    private final List<Ticket> tickets = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public TicketController(MovieClient movieClient) {
        this.movieClient = movieClient;
    }

    @GetMapping
    public List<Ticket> all() {
        return tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Ticket create(@RequestBody TicketRequest request) {
        if (request.seats() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il faut réserver au moins 1 place");
        }

        MovieClient.Movie movie;
        try {
            movie = movieClient.findMovie(request.movieId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Film " + request.movieId() + " inconnu de l'affiche"));
        } catch (ResourceAccessException e) {
            // movie-service ne répond pas (DNS, connexion refusée, timeout…)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "movie-service injoignable : " + e.getMessage());
        }

        if (movie.seats() < request.seats()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pas assez de places pour " + movie.title());
        }

        BigDecimal total = movie.price().multiply(BigDecimal.valueOf(request.seats()));
        Ticket ticket = new Ticket(sequence.incrementAndGet(), movie.id(), movie.title(),
                request.seats(), total, Instant.now());
        tickets.add(ticket);
        return ticket;
    }
}
