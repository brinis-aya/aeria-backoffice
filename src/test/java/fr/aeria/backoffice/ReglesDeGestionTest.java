package fr.aeria.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fr.aeria.backoffice.db.BusinessRuleException;

/** One test per management rule (RG), executed by the PostgreSQL triggers and constraints. */
class ReglesDeGestionTest extends DatabaseTest {

    private static final LocalDateTime FUTUR = LocalDateTime.of(2030, 3, 4, 9, 0);

    @Test
    void rg4_echeance_calculee_selon_la_priorite() {
        int t = ticket(capteurLibre("installation"), "installation");  // P3 by default
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT priorite, extract(epoch FROM echeance_sla - ouvert_le) / 3600 AS h FROM ticket WHERE id = ?", t);
        assertThat(((Number) row.get("priorite")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("h")).intValue()).isEqualTo(72);

        String c = capteurLibre("vandalisme");
        int urgent = ticket(c, "vandalisme");  // P1 by default
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM echeance_sla - ouvert_le) / 3600 FROM ticket WHERE id = ?",
                Integer.class, urgent)).isEqualTo(4);
    }

    @Test
    void rg3_pas_de_doublon_de_ticket_actif() {
        String c = capteurLibre("installation");
        ticket(c, "installation");
        assertThatThrownBy(() -> ticket(c, "installation"))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("RG3");
    }

    @Test
    void rg8_transition_interdite() {
        int t = ticket(capteurLibre("installation"), "installation");
        assertThatThrownBy(() -> sql("UPDATE ticket SET statut = 'resolu' WHERE id = ?", t))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("RG8").hasMessageContaining("ouvert -> resolu");
        assertThatThrownBy(() -> sql("UPDATE ticket SET statut = 'assigne' WHERE id = ?", t))
                .hasMessageStartingWith("RG5");
    }

    @Test
    void rg6_competence_et_activite_du_technicien() {
        int pose = ticket(capteurLibre("installation"), "installation");  // requires HAUTEUR
        assertThatThrownBy(() -> intervention(pose, 1, FUTUR))           // Camille Lefèvre: no HAUTEUR
                .hasMessageStartingWith("RG6").hasMessageContaining("HAUTEUR");
        int panne = ticket(capteurLibre("panne"), "panne");               // requires ELEC
        assertThatThrownBy(() -> intervention(panne, 7, FUTUR))          // Julie Martin: inactive
                .hasMessageStartingWith("RG6").hasMessageContaining("inactif");
    }

    @Test
    void cycle_de_vie_complet_et_historique() {
        String c = capteurLibre("installation");
        int t = ticket(c, "installation");
        int iv = intervention(t, 6, FUTUR);
        assertThat(statut(t)).isEqualTo("assigne");

        sql("UPDATE intervention SET debut = ? WHERE id = ?", java.sql.Timestamp.valueOf(FUTUR), iv);
        assertThat(statut(t)).isEqualTo("en_cours");
        assertThat(jdbc.queryForObject("SELECT statut FROM capteur WHERE id = ?", String.class, c)).isEqualTo("en_maintenance");

        // RG9: not resolved without a successful intervention
        assertThatThrownBy(() -> sql("UPDATE ticket SET statut = 'resolu' WHERE id = ?", t)).hasMessageStartingWith("RG9");

        sql("UPDATE intervention SET fin = ?, resultat = 'reussie', compte_rendu = 'ok' WHERE id = ?",
                java.sql.Timestamp.valueOf(FUTUR.plusHours(2)), iv);
        sql("UPDATE ticket SET statut = 'resolu' WHERE id = ?", t);
        sql("UPDATE ticket SET statut = 'cloture' WHERE id = ?", t);
        assertThat(jdbc.queryForObject("SELECT statut FROM capteur WHERE id = ?", String.class, c)).isEqualTo("en_service");
        assertThat(jdbc.queryForObject("SELECT resolu_le IS NOT NULL AND cloture_le IS NOT NULL FROM ticket WHERE id = ?",
                Boolean.class, t)).isTrue();

        List<Map<String, Object>> h = jdbc.queryForList(
                "SELECT nouveau_statut, par FROM ticket_historique WHERE ticket_id = ? ORDER BY id", t);
        assertThat(h).extracting(r -> r.get("nouveau_statut"))
                .containsExactly("ouvert", "assigne", "en_cours", "resolu", "cloture");
        assertThat(h).allSatisfy(r -> assertThat(r.get("par")).isEqualTo(USER));
    }

    @Test
    void rg7_stock_decremente_et_jamais_negatif() {
        int t = ticket(capteurLibre("panne"), "panne");
        int iv = intervention(t, 3, FUTUR.plusDays(3));
        Map<String, Object> piece = jdbc.queryForMap("SELECT id, stock FROM piece WHERE reference = 'CT-ESP32'");
        int id = (Integer) piece.get("id");
        int stock = (Integer) piece.get("stock");

        assertThatThrownBy(() -> sql("INSERT INTO intervention_piece VALUES (?, ?, ?)", iv, id, stock + 1))
                .hasMessage("RG7 : stock insuffisant pour la pièce CT-ESP32");
        sql("INSERT INTO intervention_piece VALUES (?, ?, 1)", iv, id);
        assertThat(jdbc.queryForObject("SELECT stock FROM piece WHERE id = ?", Integer.class, id)).isEqualTo(stock - 1);
        sql("DELETE FROM intervention_piece WHERE intervention_id = ?", iv);  // returned to stock
        assertThat(jdbc.queryForObject("SELECT stock FROM piece WHERE id = ?", Integer.class, id)).isEqualTo(stock);
    }

    @Test
    void rg13_un_technicien_ne_peut_pas_etre_a_deux_endroits() {
        LocalDateTime jour = LocalDateTime.of(2031, 6, 10, 10, 0);
        int a = intervention(ticket(capteurLibre("installation"), "installation"), 4, jour);
        int b = intervention(ticket(capteurLibre("installation"), "installation"), 4, jour);
        sql("UPDATE intervention SET debut = ?, fin = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(jour), java.sql.Timestamp.valueOf(jour.plusHours(2)), a);
        assertThatThrownBy(() -> sql("UPDATE intervention SET debut = ?, fin = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(jour.plusHours(1)), java.sql.Timestamp.valueOf(jour.plusHours(3)), b))
                .hasMessageStartingWith("RG13");
    }

    @Test
    void rg2_un_capteur_sur_un_seul_site_a_la_fois() {
        String installe = jdbc.queryForObject(
                "SELECT capteur_id FROM installation WHERE date_depose IS NULL ORDER BY capteur_id LIMIT 1", String.class);
        assertThatThrownBy(() -> sql("INSERT INTO installation (capteur_id, site_id, date_pose) VALUES (?, 1, now())", installe))
                .hasMessageStartingWith("RG2");
        assertThatThrownBy(() -> sql(
                "INSERT INTO installation (capteur_id, site_id, date_pose) VALUES ('LH-009', 1, now())"))
                .hasMessageStartingWith("RG1");  // LH-009 was stolen and retired in the demo year
    }

    @Test
    void rg10_etalonnage_non_conforme_ouvre_un_ticket_derive() {
        String c = capteurLibre("derive");
        sql("""
                INSERT INTO etalonnage (capteur_id, station_reference, polluant, debut, fin, pente, ordonnee, r2, conforme)
                VALUES (?, 'FR05089', 'pm25', current_date - 7, current_date, 1.6, 2.0, 0.62, TRUE)""", c);
        assertThat(jdbc.queryForObject("SELECT conforme FROM etalonnage WHERE capteur_id = ? ORDER BY id DESC LIMIT 1",
                Boolean.class, c)).isFalse();  // the trigger decides, not the client
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ticket WHERE capteur_id = ? AND type_code = 'derive' AND statut = 'ouvert'""",
                Integer.class, c)).isEqualTo(1);
    }

    @Test
    void rg14_supervision_detecte_un_capteur_muet_une_seule_fois() {
        String c = capteurLibre("silence");
        sql("UPDATE capteur SET derniere_communication = now() - interval '2 hours' WHERE id = ?", c);
        assertThat(db.asUser(USER, () -> jdbc.queryForObject("SELECT detecter_anomalies()", Integer.class))).isPositive();
        assertThat(db.asUser(USER, () -> jdbc.queryForObject("SELECT detecter_anomalies()", Integer.class))).isZero();
        Map<String, Object> t = jdbc.queryForMap(
                "SELECT priorite, origine FROM ticket WHERE capteur_id = ? AND type_code = 'silence' AND statut = 'ouvert'", c);
        assertThat(((Number) t.get("priorite")).intValue()).isEqualTo(1);
        assertThat(t.get("origine")).isEqualTo("auto");
    }

    @Test
    void proposer_techniciens_respecte_competence_zone_et_activite() {
        int t = ticket(capteurLibre("installation"), "installation");
        List<Map<String, Object>> p = jdbc.queryForList("SELECT * FROM proposer_techniciens(?)", t);
        assertThat(p).isNotEmpty();
        assertThat(p).extracting(r -> r.get("technicien_id")).doesNotContain(1, 5, 7);  // no HAUTEUR, or inactive
    }

    @Test
    void indicateurs_coherents_sur_l_annee_de_demonstration() {
        assertThat(jdbc.queryForList("SELECT disponibilite_pct FROM v_disponibilite", Double.class))
                .hasSizeGreaterThan(10).allSatisfy(d -> assertThat(d).isBetween(90.0, 100.0));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT mois) FROM v_kpi_mensuel", Integer.class)).isGreaterThanOrEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT sum(cout_pieces) FROM v_cout_mensuel", Double.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isGreaterThan(100);
        Duration demo = Duration.ofDays(jdbc.queryForObject(
                "SELECT (max(ouvert_le)::date - min(ouvert_le)::date) FROM ticket", Integer.class));
        assertThat(demo.toDays()).isGreaterThan(300);
    }
}
