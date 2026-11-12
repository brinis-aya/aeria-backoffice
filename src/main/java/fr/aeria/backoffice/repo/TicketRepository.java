package fr.aeria.backoffice.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

import fr.aeria.backoffice.config.AeriaUser;
import fr.aeria.backoffice.db.BusinessRuleException;
import fr.aeria.backoffice.db.Db;

/**
 * Tickets and interventions. Writes go through {@link Db#asUser}: the triggers check the rules
 * (RG3 to RG9, RG13) and record the history; this class only expresses the intent.
 */
@Repository
public class TicketRepository {

    public record PieceLine(int pieceId, int quantite) {
    }

    private final JdbcTemplate jdbc;
    private final Db db;

    public TicketRepository(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    // ------------------------------------------------------------------ read

    public List<Map<String, Object>> list(String filtre, Integer technicienId) {
        String where = switch (filtre) {
            case "hors-delai" -> "hors_delai AND statut NOT IN ('cloture', 'annule')";
            case "a-valider" -> "statut = 'resolu'";
            case "clos" -> "statut IN ('cloture', 'annule')";
            case "tous" -> "TRUE";
            case "mes" -> "EXISTS (SELECT 1 FROM intervention iv WHERE iv.ticket_id = v_ticket.id AND iv.technicien_id = ?)";
            default -> "statut IN ('ouvert', 'assigne', 'en_cours', 'resolu')";
        };
        String sql = "SELECT * FROM v_ticket WHERE " + where
                + " ORDER BY statut IN ('cloture', 'annule'), hors_delai DESC NULLS LAST, priorite, ouvert_le DESC LIMIT 300";
        return "mes".equals(filtre) ? jdbc.queryForList(sql, technicienId) : jdbc.queryForList(sql);
    }

    public Optional<Map<String, Object>> detail(int id) {
        return jdbc.queryForList("SELECT * FROM v_ticket WHERE id = ?", id).stream().findFirst();
    }

    public List<Map<String, Object>> historique(int id) {
        return jdbc.queryForList(
                "SELECT ancien_statut, nouveau_statut, change_le, par FROM ticket_historique WHERE ticket_id = ? ORDER BY id",
                id);
    }

    public List<Map<String, Object>> interventions(int ticketId) {
        return jdbc.queryForList("""
                SELECT iv.*, te.prenom || ' ' || te.nom AS technicien,
                       (SELECT string_agg(p.reference || ' x' || ip.quantite, ', ' ORDER BY p.reference)
                          FROM intervention_piece ip JOIN piece p ON p.id = ip.piece_id
                         WHERE ip.intervention_id = iv.id) AS pieces
                FROM intervention iv JOIN technicien te ON te.id = iv.technicien_id
                WHERE iv.ticket_id = ? ORDER BY iv.planifiee_le""", ticketId);
    }

    public List<Map<String, Object>> propositions(int ticketId) {
        return jdbc.queryForList("SELECT * FROM proposer_techniciens(?)", ticketId);
    }

    public List<Map<String, Object>> pieces() {
        return jdbc.queryForList("SELECT id, reference, libelle, stock FROM piece ORDER BY reference");
    }

    // ------------------------------------------------------------------ write

    public int create(String login, String capteurId, String type, Integer priorite, String titre, String description) {
        return db.asUser(login, () -> jdbc.queryForObject("""
                INSERT INTO ticket (capteur_id, type_code, priorite, origine, titre, description, ouvert_par)
                VALUES (?, ?, ?, 'manuel', ?, ?, ?) RETURNING id""",
                Integer.class, capteurId, type, priorite, titre, description, login));
    }

    public void planifier(String login, int ticketId, int technicienId, LocalDateTime quand) {
        db.asUser(login, () -> jdbc.update("""
                INSERT INTO intervention (ticket_id, technicien_id, nature, planifiee_le)
                SELECT t.id, ?, CASE t.type_code WHEN 'etalonnage' THEN 'preventive'
                                                 WHEN 'installation' THEN 'installation' ELSE 'corrective' END, ?
                FROM ticket t WHERE t.id = ?""", technicienId, Timestamp.valueOf(quand), ticketId));
    }

    public void demarrer(AeriaUser user, int interventionId) {
        checkOwner(user, interventionId);
        db.asUser(user.getUsername(), () -> {
            if (jdbc.update("UPDATE intervention SET debut = now() WHERE id = ? AND debut IS NULL", interventionId) == 0) {
                throw new BusinessRuleException("Cette intervention a déjà commencé.");
            }
        });
    }

    /** Ends an intervention; a successful one resolves the ticket (BPMN: « Passer le ticket résolu »). */
    public void terminer(AeriaUser user, int interventionId, String resultat, String compteRendu, List<PieceLine> pieces) {
        checkOwner(user, interventionId);
        db.asUser(user.getUsername(), () -> {
            int n = jdbc.update("""
                    UPDATE intervention SET fin = greatest(now(), debut + interval '1 minute'), resultat = ?, compte_rendu = ?
                    WHERE id = ? AND debut IS NOT NULL AND fin IS NULL""", resultat, compteRendu, interventionId);
            if (n == 0) {
                throw new BusinessRuleException("Intervention non démarrée ou déjà terminée.");
            }
            for (PieceLine p : pieces) {
                jdbc.update("INSERT INTO intervention_piece (intervention_id, piece_id, quantite) VALUES (?, ?, ?)",
                        interventionId, p.pieceId(), p.quantite());
            }
            if ("reussie".equals(resultat)) {
                jdbc.update("""
                        UPDATE ticket SET statut = 'resolu'
                        WHERE id = (SELECT ticket_id FROM intervention WHERE id = ?) AND statut = 'en_cours'""",
                        interventionId);
            }
        });
    }

    public void changerStatut(String login, int ticketId, String statut) {
        db.asUser(login, () -> jdbc.update("UPDATE ticket SET statut = ? WHERE id = ?", statut, ticketId));
    }

    private void checkOwner(AeriaUser user, int interventionId) {
        Integer owner = jdbc.queryForList("SELECT technicien_id FROM intervention WHERE id = ?", Integer.class, interventionId)
                .stream().findFirst().orElseThrow(() -> new BusinessRuleException("Intervention inconnue."));
        if (!user.isResponsable() && !owner.equals(user.getTechnicienId())) {
            throw new AccessDeniedException("Cette intervention est affectée à un autre technicien.");
        }
    }
}
