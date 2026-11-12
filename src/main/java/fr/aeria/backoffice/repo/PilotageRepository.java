package fr.aeria.backoffice.repo;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Steering: indicators, technicians' workload, stock and costs. */
@Repository
public class PilotageRepository {

    private final JdbcTemplate jdbc;

    public PilotageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> indicateurs() {
        return jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM ticket WHERE statut IN ('ouvert', 'assigne', 'en_cours')) AS tickets_actifs,
                  (SELECT count(*) FROM ticket WHERE statut IN ('ouvert', 'assigne', 'en_cours') AND priorite = 1) AS p1_actifs,
                  (SELECT count(*) FROM v_ticket WHERE hors_delai AND statut IN ('ouvert', 'assigne', 'en_cours')) AS hors_delai,
                  (SELECT count(*) FROM ticket WHERE statut = 'resolu') AS a_valider,
                  (SELECT round(avg(extract(epoch FROM resolu_le - ouvert_le)) / 3600, 1) FROM ticket
                    WHERE resolu_le >= horodatage() - interval '30 days' AND priorite = 1) AS mttr_p1_30j,
                  (SELECT round(100.0 * count(*) FILTER (WHERE resolu_le <= echeance_sla) / nullif(count(*), 0), 1)
                     FROM ticket WHERE resolu_le >= horodatage() - interval '30 days') AS sla_30j,
                  (SELECT round(avg(disponibilite_pct), 2) FROM v_disponibilite d JOIN capteur c ON c.id = d.capteur_id
                    WHERE c.statut <> 'reforme') AS disponibilite,
                  (SELECT count(*) FROM capteur WHERE statut = 'en_service') AS en_service,
                  (SELECT count(*) FROM capteur WHERE statut = 'en_maintenance') AS en_maintenance,
                  (SELECT count(*) FROM capteur WHERE statut = 'en_stock') AS en_stock,
                  (SELECT count(*) FROM v_stock_alerte WHERE a_commander) AS stock_alertes,
                  (SELECT count(*) FROM v_capteur_etat WHERE statut IN ('en_service', 'en_maintenance')
                     AND prochain_etalonnage <= (horodatage() + interval '7 days')::date) AS etalonnages_7j""");
    }

    public List<Map<String, Object>> urgents() {
        return jdbc.queryForList("""
                SELECT * FROM v_ticket WHERE statut IN ('ouvert', 'assigne', 'en_cours')
                ORDER BY hors_delai DESC, priorite, echeance_sla LIMIT 8""");
    }

    public List<Map<String, Object>> kpiMensuel() {
        return jdbc.queryForList("""
                SELECT mois, sum(tickets_resolus) AS tickets,
                       round(sum(mttr_h * tickets_resolus) FILTER (WHERE priorite = 1)
                             / nullif(sum(tickets_resolus) FILTER (WHERE priorite = 1), 0), 1) AS mttr_p1,
                       round(sum(respect_sla_pct * tickets_resolus) / sum(tickets_resolus), 1) AS sla
                FROM v_kpi_mensuel GROUP BY mois ORDER BY mois DESC LIMIT 12""");
    }

    public List<Map<String, Object>> techniciens() {
        return jdbc.queryForList("SELECT * FROM v_charge_technicien ORDER BY actif DESC, zone_code, nom_complet");
    }

    /** Interventions not finished yet; all of them for the manager (technicienId null). */
    public List<Map<String, Object>> interventionsAFaire(Integer technicienId) {
        return jdbc.queryForList("""
                SELECT iv.id, iv.ticket_id, iv.nature, iv.planifiee_le, iv.debut, t.capteur_id, t.titre, t.priorite,
                       t.type_libelle, t.site_nom, t.commune, t.hors_delai, t.statut, t.technicien
                FROM intervention iv JOIN v_ticket t ON t.id = iv.ticket_id
                WHERE iv.fin IS NULL AND (CAST(? AS integer) IS NULL OR iv.technicien_id = ?)
                ORDER BY iv.debut IS NULL, iv.planifiee_le""", technicienId, technicienId);
    }

    public List<Map<String, Object>> stock() {
        return jdbc.queryForList("SELECT * FROM v_stock_alerte ORDER BY a_commander DESC, reference");
    }

    public List<Map<String, Object>> couts() {
        return jdbc.queryForList("SELECT * FROM v_cout_mensuel ORDER BY mois DESC LIMIT 12");
    }
}
