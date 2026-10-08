package fr.k8s101.ticket;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MovieClient movieClient;

    private static String body(long movieId, int seats) {
        return "{\"movieId\":" + movieId + ",\"seats\":" + seats + "}";
    }

    @Test
    void createsATicket() throws Exception {
        when(movieClient.findMovie(1)).thenReturn(Optional.of(
                new MovieClient.Movie(1, "Pod Fiction", "Thriller", new BigDecimal("10.50"), 80)));

        mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(1, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movieTitle").value("Pod Fiction"))
                .andExpect(jsonPath("$.total").value(21.0));
    }

    @Test
    void unknownMovieIs422() throws Exception {
        when(movieClient.findMovie(99)).thenReturn(Optional.empty());

        mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(99, 1)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void notEnoughSeatsIs409() throws Exception {
        when(movieClient.findMovie(2)).thenReturn(Optional.of(
                new MovieClient.Movie(2, "Le Seigneur des Pods", "Fantasy", new BigDecimal("12.00"), 3)));

        mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(2, 5)))
                .andExpect(status().isConflict());
    }

    @Test
    void movieUnreachableIs503() throws Exception {
        when(movieClient.findMovie(1)).thenThrow(new ResourceAccessException("boom"));

        mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(1, 1)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void zeroSeatsIs400() throws Exception {
        mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(1, 0)))
                .andExpect(status().isBadRequest());
    }
}
