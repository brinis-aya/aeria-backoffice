-- =====================================================================================
-- Requêtes métier du back-office (résultats commentés dans docs/rapport.md, section 6)
-- =====================================================================================

-- R1. État du parc par zone : capteurs par statut
SELECT CASE WHEN GROUPING(zone_code) = 1 THEN 'Total' ELSE coalesce(zone_code, '(non installé)') END AS zone,
       count(*) FILTER (WHERE statut = 'en_service')     AS en_service,
       count(*) FILTER (WHERE statut = 'en_maintenance') AS en_maintenance,
       count(*) FILTER (WHERE statut = 'en_stock')       AS en_stock,
       count(*) FILTER (WHERE statut = 'reforme')        AS reformes
FROM v_capteur_etat GROUP BY ROLLUP (zone_code) ORDER BY zone_code NULLS LAST;

-- R2. Répartition des incidents sur l'année, par type : volume, délai moyen, respect du SLA
SELECT ti.libelle, count(*) AS tickets,
       round(avg(extract(epoch FROM t.resolu_le - t.ouvert_le)) / 3600, 1) AS delai_moyen_h,
       round(100.0 * count(*) FILTER (WHERE t.resolu_le <= t.echeance_sla) / count(*) FILTER (WHERE t.resolu_le IS NOT NULL), 1) AS respect_sla_pct
FROM ticket t JOIN type_incident ti ON ti.code = t.type_code
GROUP BY ti.libelle ORDER BY tickets DESC;

-- R3. Les 3 capteurs les moins fiables (MTBF le plus court) et leur environnement
SELECT d.capteur_id, c.site_nom, c.environnement, d.pannes, d.mtbf_jours, d.disponibilite_pct
FROM v_disponibilite d JOIN v_capteur_etat c ON c.id = d.capteur_id
WHERE c.statut <> 'reforme'
ORDER BY d.mtbf_jours NULLS LAST LIMIT 3;

-- R4. Fiabilité par environnement d'installation (trafic, industriel, urbain, rural)
SELECT c.environnement, count(*) AS capteurs, round(avg(d.mtbf_jours), 1) AS mtbf_moyen_j,
       round(avg(d.disponibilite_pct), 2) AS disponibilite_moy
FROM v_disponibilite d JOIN v_capteur_etat c ON c.id = d.capteur_id
WHERE c.statut <> 'reforme' GROUP BY c.environnement ORDER BY mtbf_moyen_j;

-- R5. Activité des techniciens sur l'année : interventions, heures, taux de réussite au premier passage
SELECT te.prenom || ' ' || te.nom AS technicien, te.zone_code,
       count(iv.id) AS interventions,
       round(sum(extract(epoch FROM iv.fin - iv.debut)) / 3600, 1) AS heures,
       round(100.0 * count(*) FILTER (WHERE iv.resultat = 'reussie') / nullif(count(iv.id), 0), 1) AS reussite_pct
FROM technicien te LEFT JOIN intervention iv ON iv.technicien_id = te.id AND iv.fin IS NOT NULL
GROUP BY te.id ORDER BY interventions DESC;

-- R6. Tickets ayant nécessité plusieurs interventions (échec au premier passage)
SELECT t.id, t.capteur_id, t.type_code, count(iv.id) AS interventions,
       round(extract(epoch FROM t.resolu_le - t.ouvert_le) / 3600, 1) AS duree_h
FROM ticket t JOIN intervention iv ON iv.ticket_id = t.id
GROUP BY t.id HAVING count(iv.id) > 1 ORDER BY interventions DESC, duree_h DESC LIMIT 10;

-- R7. Pièces les plus consommées et coût total sur 12 mois
SELECT p.reference, p.libelle, sum(ip.quantite) AS quantite, sum(ip.quantite * p.prix_unitaire) AS cout,
       rank() OVER (ORDER BY sum(ip.quantite * p.prix_unitaire) DESC) AS rang_cout
FROM intervention_piece ip JOIN piece p ON p.id = ip.piece_id JOIN intervention iv ON iv.id = ip.intervention_id
WHERE iv.fin >= now() - interval '12 months'
GROUP BY p.id ORDER BY cout DESC;

-- R8. Étalonnages : taux de conformité et dérive moyenne de la pente par modèle
SELECT m.nom AS modele, count(*) AS etalonnages,
       round(100.0 * count(*) FILTER (WHERE e.conforme) / count(*), 1) AS conformes_pct,
       round(avg(abs(e.pente - 1)), 3) AS ecart_pente_moyen
FROM etalonnage e JOIN capteur c ON c.id = e.capteur_id JOIN modele_capteur m ON m.id = c.modele_id
GROUP BY m.nom ORDER BY m.nom;

-- R9. Évolution mensuelle du MTTR des urgences avec moyenne mobile sur 3 mois (fenêtre glissante)
SELECT mois, mttr_h,
       round(avg(mttr_h) OVER (ORDER BY mois ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 1) AS mttr_moyenne_3m
FROM v_kpi_mensuel WHERE priorite = 1 ORDER BY mois;

-- R10. Capteurs à remplacer dans les 18 mois (fin de vie théorique) : budget de renouvellement
SELECT id, modele, site_nom, fin_de_vie::date
FROM v_capteur_etat WHERE statut IN ('en_service', 'en_maintenance') AND fin_de_vie <= now() + interval '18 months'
ORDER BY fin_de_vie;

-- R11. Traçabilité : délai de validation par le responsable (résolu -> clôturé)
SELECT round(avg(extract(epoch FROM c.change_le - r.change_le)) / 3600, 1) AS validation_moyenne_h,
       percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM c.change_le - r.change_le) / 3600) AS p90_h
FROM ticket_historique r
JOIN ticket_historique c ON c.ticket_id = r.ticket_id AND c.nouveau_statut = 'cloture'
WHERE r.nouveau_statut = 'resolu';
