package fr.aeria.backoffice.repo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sensor fleet: sensors, sites, installations, calibrations (read side). */
@Repository
public class ParcRepository {

    private final JdbcTemplate jdbc;

    public ParcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> capteurs() {
        return jdbc.queryForList("""
                SELECT c.*, d.disponibilite_pct, d.mtbf_jours, d.pannes
                FROM v_capteur_etat c LEFT JOIN v_disponibilite d ON d.capteur_id = c.id
                ORDER BY c.statut = 'reforme', c.id""");
    }

    public Optional<Map<String, Object>> capteur(String id) {
        return jdbc.queryForList("""
                SELECT c.*, d.disponibilite_pct, d.mtbf_jours, d.pannes, d.heures_arret, d.jours_en_service
                FROM v_capteur_etat c LEFT JOIN v_disponibilite d ON d.capteur_id = c.id
                WHERE c.id = ?""", id).stream().findFirst();
    }

    public List<Map<String, Object>> installations(String capteurId) {
        return jdbc.queryForList("""
                SELECT i.date_pose, i.date_depose, s.code, s.nom, s.commune, s.environnement
                FROM installation i JOIN site s ON s.id = i.site_id
                WHERE i.capteur_id = ? ORDER BY i.date_pose DESC""", capteurId);
    }

    public List<Map<String, Object>> tickets(String capteurId) {
        return jdbc.queryForList("""
                SELECT id, type_libelle, priorite, statut, titre, ouvert_le, resolu_le, hors_delai, duree_h
                FROM v_ticket WHERE capteur_id = ? ORDER BY ouvert_le DESC""", capteurId);
    }

    public List<Map<String, Object>> etalonnages(String capteurId) {
        return jdbc.queryForList("""
                SELECT debut, fin, station_reference, polluant, pente, ordonnee, r2, conforme
                FROM etalonnage WHERE capteur_id = ? ORDER BY fin DESC""", capteurId);
    }

    public List<Map<String, Object>> typesIncident() {
        return jdbc.queryForList(
                "SELECT code, libelle, priorite_defaut FROM type_incident ORDER BY priorite_defaut, libelle");
    }

    public List<String> capteursSurSite() {
        return jdbc.queryForList(
                "SELECT id FROM capteur WHERE statut IN ('en_service', 'en_maintenance') ORDER BY id", String.class);
    }
}
