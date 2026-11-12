package fr.aeria.backoffice;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import fr.aeria.backoffice.config.AeriaUser;

/** Roles (RG16), CSRF, ownership of interventions, and the pages themselves. */
@AutoConfigureMockMvc
class WebSecuriteTest extends DatabaseTest {

    private static final AeriaUser RESPONSABLE = new AeriaUser("responsable", "x", true, "RESPONSABLE", null);
    private static final AeriaUser CAMILLE = new AeriaUser("clefevre", "x", true, "TECHNICIEN", 1);

    @Autowired
    MockMvc mvc;

    @Test
    void anonyme_redirige_vers_la_connexion() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void pages_principales_s_affichent() throws Exception {
        mvc.perform(get("/").with(user(RESPONSABLE))).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("disponibilité moyenne")));
        mvc.perform(get("/capteurs").with(user(CAMILLE))).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("LH-001")));
        for (String page : new String[] {"/tickets", "/tickets?filtre=tous", "/techniciens", "/stock", "/capteurs/LH-009"}) {
            mvc.perform(get(page).with(user(RESPONSABLE))).andExpect(status().isOk());
        }
        int t = jdbc.queryForObject("SELECT max(id) FROM ticket", Integer.class);
        mvc.perform(get("/tickets/" + t).with(user(CAMILLE))).andExpect(status().isOk());
    }

    @Test
    void un_technicien_ne_cloture_pas_un_ticket() throws Exception {
        int t = jdbc.queryForObject("SELECT max(id) FROM ticket", Integer.class);
        mvc.perform(post("/tickets/" + t + "/cloturer").with(user(CAMILLE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/tickets/nouveau").with(user(CAMILLE))).andExpect(status().isForbidden());
    }

    @Test
    void sans_jeton_csrf_toute_ecriture_est_refusee() throws Exception {
        mvc.perform(post("/tickets/nouveau").with(user(RESPONSABLE))
                .param("capteur", "LH-001").param("type", "installation").param("titre", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void un_technicien_ne_touche_pas_l_intervention_d_un_autre() throws Exception {
        int t = ticket(capteurLibre("installation"), "installation");
        int iv = intervention(t, 6, LocalDateTime.of(2032, 1, 5, 9, 0));  // Thomas Girard
        mvc.perform(post("/tickets/" + t + "/interventions/" + iv + "/demarrer").with(user(CAMILLE)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tickets/" + t + "/interventions/" + iv + "/demarrer").with(user(RESPONSABLE)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("ok", "Intervention démarrée."));
    }

    @Test
    void le_responsable_cree_un_ticket_et_les_regles_remontent_en_message() throws Exception {
        String c = capteurLibre("installation");
        mvc.perform(post("/tickets/nouveau").with(user(RESPONSABLE)).with(csrf())
                .param("capteur", c).param("type", "installation").param("titre", "Déplacer le capteur"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("/tickets/*"));
        mvc.perform(post("/tickets/nouveau").with(user(RESPONSABLE)).with(csrf())
                .param("capteur", c).param("type", "installation").param("titre", "Doublon"))
                .andExpect(flash().attribute("erreur", Matchers.startsWith("RG3")));
    }

    @Test
    void api_json_en_http_basic() throws Exception {
        mvc.perform(get("/api/indicateurs").with(httpBasic("responsable", "demo-responsable")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tickets_actifs").isNumber());
        mvc.perform(get("/api/capteurs").with(httpBasic("responsable", "mauvais"))).andExpect(status().isUnauthorized());
    }
}
