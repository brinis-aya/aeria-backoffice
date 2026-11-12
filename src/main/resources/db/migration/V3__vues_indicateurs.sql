-- =====================================================================================
-- Vues de restitution et indicateurs de maintenance
-- =====================================================================================

-- État courant du parc : un capteur, son modèle, son site actuel, sa santé
CREATE VIEW v_capteur_etat AS
SELECT c.id, c.numero_serie, m.fabricant || ' ' || m.nom AS modele, m.polluants, c.statut,
       s.code AS site_code, s.nom AS site_nom, s.commune, s.environnement, s.zone_code,
       s.latitude, s.longitude, i.date_pose,
       c.derniere_communication, c.messages_recus, c.messages_rejetes,
       CASE WHEN c.messages_recus > 0 THEN round(100.0 * c.messages_rejetes / c.messages_recus, 2) END AS taux_rejet_pct,
       (SELECT count(*) FROM ticket t WHERE t.capteur_id = c.id
          AND t.statut IN ('ouvert', 'assigne', 'en_cours'))::int AS tickets_actifs,
       prochain_etalonnage(c.id) AS prochain_etalonnage,
       c.date_achat + make_interval(months => m.duree_vie_mois) AS fin_de_vie
FROM capteur c
JOIN modele_capteur m ON m.id = c.modele_id
LEFT JOIN installation i ON i.capteur_id = c.id AND i.date_depose IS NULL
LEFT JOIN site s ON s.id = i.site_id;

-- Tickets enrichis, avec le respect du délai contractuel (RG4)
CREATE VIEW v_ticket AS
SELECT t.*, ti.libelle AS type_libelle, ti.competence_requise,
       s.nom AS site_nom, s.commune, s.zone_code,
       CASE
           WHEN t.statut = 'annule' THEN NULL
           WHEN t.resolu_le IS NOT NULL THEN t.resolu_le > t.echeance_sla
           ELSE horodatage() > t.echeance_sla
       END AS hors_delai,
       round(extract(epoch FROM coalesce(t.resolu_le, horodatage()) - t.ouvert_le) / 3600, 1) AS duree_h,
       (SELECT te.prenom || ' ' || te.nom FROM intervention iv JOIN technicien te ON te.id = iv.technicien_id
         WHERE iv.ticket_id = t.id ORDER BY iv.planifiee_le DESC LIMIT 1) AS technicien
FROM ticket t
JOIN type_incident ti ON ti.code = t.type_code
LEFT JOIN installation i ON i.capteur_id = t.capteur_id AND i.date_depose IS NULL
LEFT JOIN site s ON s.id = i.site_id;

-- MTTR (délai moyen de résolution) et taux de respect du SLA, par mois et par priorité
CREATE VIEW v_kpi_mensuel AS
SELECT date_trunc('month', t.resolu_le)::date AS mois, t.priorite,
       count(*) AS tickets_resolus,
       round(avg(extract(epoch FROM t.resolu_le - t.ouvert_le)) / 3600, 1) AS mttr_h,
       round(100.0 * count(*) FILTER (WHERE t.resolu_le <= t.echeance_sla) / count(*), 1) AS respect_sla_pct
FROM ticket t
WHERE t.resolu_le IS NOT NULL
GROUP BY 1, 2;

-- Disponibilité sur 365 jours : part du temps installé sans panne bloquante (silence, panne, vandalisme).
-- Les périodes d'indisponibilité qui se chevauchent sont fusionnées (range_agg) pour ne pas compter deux fois.
CREATE VIEW v_disponibilite AS
WITH fenetre AS (
    SELECT tstzrange(horodatage() - INTERVAL '365 days', horodatage()) AS f
), service AS (
    SELECT i.capteur_id, range_agg(tstzrange(i.date_pose, coalesce(i.date_depose, horodatage())) * f) AS periodes
    FROM installation i, fenetre GROUP BY i.capteur_id
), arret AS (
    SELECT t.capteur_id, range_agg(tstzrange(t.ouvert_le, coalesce(t.resolu_le, t.cloture_le, horodatage())) * f) AS periodes,
           count(*) AS pannes
    FROM ticket t, fenetre
    WHERE t.type_code IN ('silence', 'panne', 'vandalisme') AND t.statut <> 'annule'
    GROUP BY t.capteur_id
), durees AS (
    SELECT s.capteur_id,
           (SELECT coalesce(sum(upper(r) - lower(r)), INTERVAL '0') FROM unnest(s.periodes) r) AS en_service,
           (SELECT coalesce(sum(upper(r) - lower(r)), INTERVAL '0') FROM unnest(a.periodes * s.periodes) r) AS a_l_arret,
           coalesce(a.pannes, 0) AS pannes
    FROM service s LEFT JOIN arret a USING (capteur_id)
)
SELECT capteur_id,
       round(extract(epoch FROM en_service) / 86400, 1) AS jours_en_service,
       round(extract(epoch FROM a_l_arret) / 3600, 1) AS heures_arret,
       pannes,
       CASE WHEN en_service > INTERVAL '0'
            THEN round(100 * (1 - extract(epoch FROM a_l_arret) / extract(epoch FROM en_service)), 2) END AS disponibilite_pct,
       CASE WHEN pannes > 0
            THEN round(extract(epoch FROM en_service - a_l_arret) / 86400 / pannes, 1) END AS mtbf_jours
FROM durees;

-- Charge des techniciens : en cours / planifié, et activité des 30 derniers jours
CREATE VIEW v_charge_technicien AS
SELECT te.id, te.matricule, te.prenom || ' ' || te.nom AS nom_complet, te.zone_code, te.actif,
       count(iv.id) FILTER (WHERE iv.fin IS NULL) AS interventions_a_faire,
       count(iv.id) FILTER (WHERE iv.fin >= horodatage() - INTERVAL '30 days') AS interventions_30j,
       round(coalesce(sum(extract(epoch FROM iv.fin - iv.debut)) FILTER (WHERE iv.fin >= horodatage() - INTERVAL '30 days'), 0) / 3600, 1) AS heures_30j,
       (SELECT string_agg(tc.competence_code || ':' || tc.niveau, ' ' ORDER BY tc.competence_code)
          FROM technicien_competence tc WHERE tc.technicien_id = te.id) AS competences
FROM technicien te
LEFT JOIN intervention iv ON iv.technicien_id = te.id
GROUP BY te.id;

-- Pièces à réapprovisionner
CREATE VIEW v_stock_alerte AS
SELECT p.*, (p.stock <= p.seuil_reappro) AS a_commander,
       coalesce((SELECT sum(ip.quantite) FROM intervention_piece ip JOIN intervention iv ON iv.id = ip.intervention_id
                  WHERE ip.piece_id = p.id AND iv.fin >= horodatage() - INTERVAL '90 days'), 0) AS consommation_90j
FROM piece p;

-- Coût de maintenance mensuel : pièces + main-d'œuvre (taux horaire interne de 45 €)
CREATE VIEW v_cout_mensuel AS
SELECT date_trunc('month', iv.fin)::date AS mois,
       count(*) AS interventions,
       round(sum(d.heures), 1) AS heures,
       round(sum(d.heures) * 45, 2) AS cout_main_oeuvre,
       coalesce(sum(p.cout), 0) AS cout_pieces
FROM intervention iv
CROSS JOIN LATERAL (SELECT extract(epoch FROM iv.fin - iv.debut) / 3600 AS heures) d
LEFT JOIN LATERAL (SELECT sum(ip.quantite * pc.prix_unitaire) AS cout
                   FROM intervention_piece ip JOIN piece pc ON pc.id = ip.piece_id
                   WHERE ip.intervention_id = iv.id) p ON TRUE
WHERE iv.fin IS NOT NULL
GROUP BY 1;
