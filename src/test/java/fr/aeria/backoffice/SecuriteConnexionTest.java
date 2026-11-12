package fr.aeria.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/** Fixes of the security audit (module 5): F1 bind address, F3 lockout, F6 headers, F7 security log. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecuriteConnexionTest extends DatabaseTest {

    @Autowired
    MockMvc mvc;

    @Value("${server.address}")
    String serverAddress;

    @Test
    void f1_ecoute_par_defaut_sur_la_boucle_locale() {
        assertThat(serverAddress).isEqualTo("127.0.0.1");
    }

    @Test
    void f3_compte_bloque_apres_cinq_echecs_meme_avec_le_bon_mot_de_passe() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(formLogin().user("ybenali").password("mauvais-" + i)).andExpect(unauthenticated());
        }
        mvc.perform(formLogin().user("ybenali").password("demo-technicien")).andExpect(unauthenticated());
        // other accounts are not affected
        mvc.perform(formLogin().user("hduval").password("demo-technicien")).andExpect(authenticated());
    }

    @Test
    void f3_un_succes_remet_le_compteur_a_zero() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(formLogin().user("lnguyen").password("mauvais-" + i)).andExpect(unauthenticated());
        }
        mvc.perform(formLogin().user("lnguyen").password("demo-technicien")).andExpect(authenticated());
        mvc.perform(formLogin().user("lnguyen").password("mauvais")).andExpect(unauthenticated());
        mvc.perform(formLogin().user("lnguyen").password("demo-technicien")).andExpect(authenticated());
    }

    @Test
    void f6_entetes_de_securite() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("geolocation=()")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void f7_journal_de_securite_exploitable_et_non_falsifiable(CapturedOutput output) throws Exception {
        mvc.perform(formLogin().user("tgirard").password("mauvais")).andExpect(unauthenticated());
        mvc.perform(formLogin().user("faux\nAERIA-SECURITY event=login_success user=admin").password("x"))
                .andExpect(unauthenticated());
        assertThat(output.getOut())
                .contains("AERIA-SECURITY event=login_failure user=tgirard ip=127.0.0.1 reason=BadCredentialsException")
                .contains("user=faux_AERIA-SECURITY_event=login_success_user=admin")  // injected newline neutralised
                .doesNotContain("event=login_success user=admin");
    }
}
