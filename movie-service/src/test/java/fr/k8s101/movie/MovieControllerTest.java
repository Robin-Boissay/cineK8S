package fr.k8s101.movie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MovieController.class)
class MovieControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsAllMovies() throws Exception {
        mvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void returnsOneMovie() throws Exception {
        mvc.perform(get("/api/movies/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Pod Fiction"));
    }

    @Test
    void unknownMovieIs404() throws Exception {
        mvc.perform(get("/api/movies/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void whoamiExposesTheEnvironment() throws Exception {
        mvc.perform(get("/api/movies/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("local"))
                .andExpect(jsonPath("$.hostname").isNotEmpty());
    }
}
