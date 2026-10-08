package fr.k8s101.ticket;

import java.math.BigDecimal;
import java.time.Instant;

/** Une réservation validée. */
public record Ticket(long id, long movieId, String movieTitle, int seats, BigDecimal total, Instant createdAt) {
}
