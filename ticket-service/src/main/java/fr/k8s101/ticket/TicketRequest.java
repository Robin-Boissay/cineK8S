package fr.k8s101.ticket;

/** Corps de la requête POST /api/tickets. */
public record TicketRequest(long movieId, int seats) {
}
