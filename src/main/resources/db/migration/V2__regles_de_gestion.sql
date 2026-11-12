-- =====================================================================================
-- Règles de gestion appliquées par la base (triggers et fonctions PL/pgSQL)
-- Elles valent quel que soit le client (application, import, psql) : la base est garante.
-- =====================================================================================

-- Horodatage et auteur « logiques » : l'application pose l'auteur par transaction
-- (SET LOCAL aeria.utilisateur) ; le jeu de démonstration rejoue l'historique en posant l'heure.
CREATE FUNCTION horodatage() RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('aeria.horodatage', true), '')::timestamptz, now())
$$;

CREATE FUNCTION auteur() RETURNS VARCHAR LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('aeria.utilisateur', true), ''), 'systeme')
$$;

-- RG4 : délai de résolution contractuel selon la priorité (préventif : 10 jours)
CREATE FUNCTION delai_sla(p_priorite SMALLINT, p_origine VARCHAR) RETURNS INTERVAL LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN p_origine = 'preventif' THEN INTERVAL '10 days'
        WHEN p_priorite = 1 THEN INTERVAL '4 hours'
        WHEN p_priorite = 2 THEN INTERVAL '24 hours'
        ELSE INTERVAL '72 hours'
    END
$$;

-- ------------------------------------------------------------------ ticket

CREATE FUNCTION ticket_avant_insertion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.statut <> 'ouvert' THEN
        RAISE EXCEPTION 'RG8 : un ticket est toujours créé au statut « ouvert »';
    END IF;
    IF NEW.priorite IS NULL THEN
        SELECT priorite_defaut INTO NEW.priorite FROM type_incident WHERE code = NEW.type_code;
    END IF;
    NEW.echeance_sla := NEW.ouvert_le + delai_sla(NEW.priorite, NEW.origine);
    NEW.ouvert_par := CASE WHEN NEW.ouvert_par = 'systeme' THEN auteur() ELSE NEW.ouvert_par END;
    RETURN NEW;
END $$;

CREATE TRIGGER t_ticket_avant_insertion BEFORE INSERT ON ticket
    FOR EACH ROW EXECUTE FUNCTION ticket_avant_insertion();

-- RG8 : cycle de vie du ticket (machine à états)
--   ouvert -> assigne -> en_cours -> resolu -> cloture
--   ouvert|assigne -> annule ; en_cours -> assigne (replanification) ; resolu -> en_cours (réouverture)
CREATE FUNCTION ticket_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.statut = OLD.statut THEN
        RETURN NEW;
    END IF;
    IF NOT (OLD.statut, NEW.statut) IN (
        ('ouvert', 'assigne'), ('ouvert', 'annule'), ('assigne', 'en_cours'), ('assigne', 'annule'),
        ('en_cours', 'resolu'), ('en_cours', 'assigne'), ('resolu', 'cloture'), ('resolu', 'en_cours')) THEN
        RAISE EXCEPTION 'RG8 : transition interdite % -> % (ticket %)', OLD.statut, NEW.statut, OLD.id;
    END IF;
    IF NEW.statut = 'assigne' AND NOT EXISTS (SELECT 1 FROM intervention WHERE ticket_id = NEW.id) THEN
        RAISE EXCEPTION 'RG5 : un ticket ne peut être assigné sans intervention planifiée';
    END IF;
    IF NEW.statut = 'resolu' THEN
        IF NOT EXISTS (SELECT 1 FROM intervention WHERE ticket_id = NEW.id AND resultat = 'reussie') THEN
            RAISE EXCEPTION 'RG9 : un ticket n''est résolu qu''après une intervention réussie';
        END IF;
        NEW.resolu_le := horodatage();
    END IF;
    IF NEW.statut = 'en_cours' AND OLD.statut = 'resolu' THEN
        NEW.resolu_le := NULL;
    END IF;
    IF NEW.statut IN ('cloture', 'annule') THEN
        NEW.cloture_le := horodatage();
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER t_ticket_transition BEFORE UPDATE OF statut ON ticket
    FOR EACH ROW EXECUTE FUNCTION ticket_transition();

-- RG12 (traçabilité) et RG11 (état du capteur)
CREATE FUNCTION ticket_apres_changement() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.statut <> OLD.statut THEN
        INSERT INTO ticket_historique (ticket_id, ancien_statut, nouveau_statut, change_le, par)
        VALUES (NEW.id, CASE WHEN TG_OP = 'UPDATE' THEN OLD.statut END, NEW.statut,
                CASE WHEN TG_OP = 'INSERT' THEN NEW.ouvert_le ELSE horodatage() END, auteur());
    END IF;
    IF NEW.statut = 'en_cours' THEN
        UPDATE capteur SET statut = 'en_maintenance' WHERE id = NEW.capteur_id AND statut = 'en_service';
    ELSIF NEW.statut IN ('cloture', 'annule') THEN
        UPDATE capteur SET statut = 'en_service'
        WHERE id = NEW.capteur_id AND statut = 'en_maintenance'
          AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.capteur_id = NEW.capteur_id
                          AND t.statut = 'en_cours' AND t.id <> NEW.id)
          AND EXISTS (SELECT 1 FROM installation i WHERE i.capteur_id = NEW.capteur_id AND i.date_depose IS NULL);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER t_ticket_apres_changement AFTER INSERT OR UPDATE OF statut ON ticket
    FOR EACH ROW EXECUTE FUNCTION ticket_apres_changement();

-- ------------------------------------------------------------------ intervention

-- RG6 : le technicien est actif et maîtrise la compétence exigée par le type d'incident
CREATE FUNCTION intervention_controle() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_statut VARCHAR;
    v_competence VARCHAR;
BEGIN
    SELECT t.statut, ti.competence_requise INTO v_statut, v_competence
    FROM ticket t JOIN type_incident ti ON ti.code = t.type_code WHERE t.id = NEW.ticket_id;
    IF TG_OP = 'INSERT' AND v_statut NOT IN ('ouvert', 'assigne', 'en_cours') THEN
        RAISE EXCEPTION 'RG5 : impossible de planifier une intervention sur un ticket %', v_statut;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM technicien WHERE id = NEW.technicien_id AND actif) THEN
        RAISE EXCEPTION 'RG6 : le technicien % est inactif ou inconnu', NEW.technicien_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM technicien_competence
                   WHERE technicien_id = NEW.technicien_id AND competence_code = v_competence) THEN
        RAISE EXCEPTION 'RG6 : le technicien % n''a pas la compétence % requise', NEW.technicien_id, v_competence;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER t_intervention_controle BEFORE INSERT OR UPDATE OF technicien_id ON intervention
    FOR EACH ROW EXECUTE FUNCTION intervention_controle();

-- Le ticket suit l'avancement de ses interventions
CREATE FUNCTION intervention_avancement() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE ticket SET statut = 'assigne' WHERE id = NEW.ticket_id AND statut = 'ouvert';
    ELSIF NEW.debut IS NOT NULL AND OLD.debut IS NULL THEN
        UPDATE ticket SET statut = 'en_cours' WHERE id = NEW.ticket_id AND statut = 'assigne';
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER t_intervention_avancement AFTER INSERT OR UPDATE OF debut ON intervention
    FOR EACH ROW EXECUTE FUNCTION intervention_avancement();

-- RG13 : un technicien ne peut pas être sur deux interventions en même temps
ALTER TABLE intervention ADD CONSTRAINT intervention_technicien_disponible EXCLUDE USING gist (
    technicien_id WITH =, tstzrange(debut, fin, '[)') WITH &&
) WHERE (debut IS NOT NULL AND fin IS NOT NULL);

-- ------------------------------------------------------------------ stock

-- RG7 : la consommation d'une pièce décrémente le stock, qui ne peut devenir négatif
CREATE FUNCTION piece_mouvement() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_ref VARCHAR;
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        BEGIN
            UPDATE piece SET stock = stock - NEW.quantite + CASE WHEN TG_OP = 'UPDATE' THEN OLD.quantite ELSE 0 END
            WHERE id = NEW.piece_id;
        EXCEPTION WHEN check_violation THEN
            SELECT reference INTO v_ref FROM piece WHERE id = NEW.piece_id;
            RAISE EXCEPTION 'RG7 : stock insuffisant pour la pièce %', v_ref;
        END;
    ELSE
        UPDATE piece SET stock = stock + OLD.quantite WHERE id = OLD.piece_id;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER t_piece_mouvement AFTER INSERT OR UPDATE OF quantite OR DELETE ON intervention_piece
    FOR EACH ROW EXECUTE FUNCTION piece_mouvement();

-- ------------------------------------------------------------------ parc

-- RG1 : seul un capteur en stock (ou déjà en service, en cas de déplacement) peut être posé
CREATE FUNCTION installation_controle() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_statut VARCHAR;
BEGIN
    SELECT statut INTO v_statut FROM capteur WHERE id = NEW.capteur_id;
    IF TG_OP = 'INSERT' AND v_statut IN ('hors_service', 'reforme') THEN
        RAISE EXCEPTION 'RG1 : le capteur % est %, il ne peut pas être installé', NEW.capteur_id, v_statut;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER t_installation_controle BEFORE INSERT ON installation
    FOR EACH ROW EXECUTE FUNCTION installation_controle();

CREATE FUNCTION installation_apres() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.date_depose IS NULL THEN
        UPDATE capteur SET statut = 'en_service' WHERE id = NEW.capteur_id AND statut = 'en_stock';
    ELSIF NOT EXISTS (SELECT 1 FROM installation WHERE capteur_id = NEW.capteur_id AND date_depose IS NULL) THEN
        UPDATE capteur SET statut = 'en_stock' WHERE id = NEW.capteur_id AND statut IN ('en_service', 'en_maintenance');
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER t_installation_apres AFTER INSERT OR UPDATE OF date_depose ON installation
    FOR EACH ROW EXECUTE FUNCTION installation_apres();

-- ------------------------------------------------------------------ étalonnage

-- RG10 : un étalonnage est conforme si la régression capteur / station de référence est
-- fiable (R² >= 0,75) et la pente proche de 1 (0,7 à 1,3). Sinon un ticket « dérive » est ouvert.
CREATE FUNCTION etalonnage_conformite() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.conforme := NEW.r2 >= 0.75 AND NEW.pente BETWEEN 0.7 AND 1.3;
    IF NOT NEW.conforme THEN
        INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le)
        VALUES (NEW.capteur_id, 'derive', 'auto', 'Étalonnage non conforme',
                format('Pente %s, R² %s contre %s (%s)', NEW.pente, NEW.r2, NEW.station_reference, NEW.polluant),
                horodatage())
        ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER t_etalonnage_conformite BEFORE INSERT ON etalonnage
    FOR EACH ROW EXECUTE FUNCTION etalonnage_conformite();

-- ------------------------------------------------------------------ automatismes (appelés par l'application)

-- RG14 : supervision. Capteur en service muet depuis plus d'une heure -> ticket « silence » (P1) ;
-- plus de 5 % de messages rejetés (sur au moins 100) -> ticket « dérive » (P2). Sans doublon (RG3).
CREATE FUNCTION detecter_anomalies(p_maintenant TIMESTAMPTZ DEFAULT horodatage()) RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE
    n1 INTEGER;
    n2 INTEGER;
BEGIN
    INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le)
    SELECT c.id, 'silence', 'auto', 'Aucune mesure reçue',
           format('Dernière communication : %s', coalesce(to_char(c.derniere_communication AT TIME ZONE 'Europe/Paris', 'DD/MM/YYYY HH24:MI'), 'jamais')),
           p_maintenant
    FROM capteur c
    WHERE c.statut = 'en_service'
      AND (c.derniere_communication IS NULL OR c.derniere_communication < p_maintenant - INTERVAL '1 hour')
    ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
    GET DIAGNOSTICS n1 = ROW_COUNT;

    INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le)
    SELECT c.id, 'derive', 'auto', 'Taux de rejet anormal',
           format('%s %% des messages rejetés (%s / %s)',
                  round(100.0 * c.messages_rejetes / c.messages_recus, 1), c.messages_rejetes, c.messages_recus),
           p_maintenant
    FROM capteur c
    WHERE c.statut = 'en_service' AND c.messages_recus >= 100
      AND c.messages_rejetes > 0.05 * c.messages_recus
    ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
    GET DIAGNOSTICS n2 = ROW_COUNT;
    RETURN n1 + n2;
END $$;

-- Échéance du prochain étalonnage : dernier étalonnage conforme (ou première pose) + périodicité du modèle
CREATE FUNCTION prochain_etalonnage(p_capteur VARCHAR) RETURNS DATE LANGUAGE sql STABLE AS $$
    SELECT (coalesce(
                (SELECT max(e.fin) FROM etalonnage e WHERE e.capteur_id = c.id AND e.conforme),
                (SELECT min(i.date_pose)::date FROM installation i WHERE i.capteur_id = c.id))
            + m.periodicite_etalonnage_j)::date
    FROM capteur c JOIN modele_capteur m ON m.id = c.modele_id
    WHERE c.id = p_capteur
$$;

-- RG15 : maintenance préventive. Ticket d'étalonnage ouvert 7 jours avant l'échéance.
CREATE FUNCTION planifier_etalonnages(p_maintenant TIMESTAMPTZ DEFAULT horodatage()) RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE
    n INTEGER;
BEGIN
    INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le)
    SELECT c.id, 'etalonnage', 'preventif', 'Étalonnage périodique',
           format('Échéance : %s', to_char(prochain_etalonnage(c.id), 'DD/MM/YYYY')), p_maintenant
    FROM capteur c
    WHERE c.statut IN ('en_service', 'en_maintenance')
      AND prochain_etalonnage(c.id) <= (p_maintenant + INTERVAL '7 days')::date
    ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;

-- Aide à l'affectation : techniciens éligibles (RG6) classés par zone, niveau et charge en cours
CREATE FUNCTION proposer_techniciens(p_ticket INTEGER)
RETURNS TABLE (technicien_id INTEGER, nom_complet TEXT, meme_zone BOOLEAN, niveau SMALLINT, charge INTEGER)
LANGUAGE sql STABLE AS $$
    WITH t AS (
        SELECT tk.id, ti.competence_requise, s.zone_code
        FROM ticket tk
        JOIN type_incident ti ON ti.code = tk.type_code
        LEFT JOIN installation i ON i.capteur_id = tk.capteur_id AND i.date_depose IS NULL
        LEFT JOIN site s ON s.id = i.site_id
        WHERE tk.id = p_ticket
    )
    SELECT te.id, te.prenom || ' ' || te.nom, te.zone_code = t.zone_code, tc.niveau,
           (SELECT count(*)::int FROM intervention iv WHERE iv.technicien_id = te.id AND iv.fin IS NULL)
    FROM t
    JOIN technicien_competence tc ON tc.competence_code = t.competence_requise
    JOIN technicien te ON te.id = tc.technicien_id AND te.actif
    ORDER BY (te.zone_code = t.zone_code) DESC NULLS LAST,
             (SELECT count(*) FROM intervention iv WHERE iv.technicien_id = te.id AND iv.fin IS NULL),
             tc.niveau DESC, te.id
$$;
