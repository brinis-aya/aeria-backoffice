-- =====================================================================================
-- Jeu de démonstration : le parc du Havre et une année d'exploitation simulée.
-- L'historique est rejoué jour par jour À TRAVERS les règles de gestion (triggers) :
-- il ne contient donc aucun état que l'application ne pourrait pas produire.
-- Chargé uniquement avec le profil « demo » (spring.flyway.locations += classpath:db/demo).
-- Comptes de démonstration (à ne jamais utiliser en production) : voir README.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- crypt()/gen_salt('bf') : hachage BCrypt des mots de passe de démo

INSERT INTO zone (code, libelle) VALUES
    ('LH-CENTRE', 'Le Havre centre'),
    ('LH-PORT',   'Zone portuaire et industrielle'),
    ('LH-NORD',   'Plateau nord et communes limitrophes');

INSERT INTO site (code, nom, adresse, commune, latitude, longitude, environnement, zone_code) VALUES
    ('S01', 'Hôtel de Ville',          'Place de l''Hôtel de Ville',  'Le Havre',            49.49380, 0.10770, 'urbain',     'LH-CENTRE'),
    ('S02', 'Boulevard de Strasbourg', 'Bd de Strasbourg',            'Le Havre',            49.49200, 0.11800, 'trafic',     'LH-CENTRE'),
    ('S03', 'Port - Quai de l''Europe', 'Quai de l''Europe',          'Le Havre',            49.47500, 0.13000, 'industriel', 'LH-PORT'),
    ('S04', 'Zone industrielle',       'Route industrielle',          'Le Havre',            49.46600, 0.19000, 'industriel', 'LH-PORT'),
    ('S05', 'Sainte-Adresse',          'Rue Reine Elisabeth',         'Sainte-Adresse',      49.50900, 0.08400, 'urbain',     'LH-NORD'),
    ('S06', 'Montivilliers',           'Place François Mitterrand',   'Montivilliers',       49.54500, 0.18800, 'rural',      'LH-NORD'),
    ('S07', 'Gare',                    'Cours de la République',      'Le Havre',            49.49250, 0.12550, 'trafic',     'LH-CENTRE'),
    ('S08', 'Harfleur',                'Place Victor Hugo',           'Harfleur',            49.50600, 0.19800, 'urbain',     'LH-PORT'),
    ('S09', 'Gonfreville-l''Orcher',   'Rue Maurice Thorez',          'Gonfreville-l''Orcher', 49.48000, 0.23300, 'industriel', 'LH-PORT'),
    ('S10', 'Campus Lebon',            'Rue Philippe Lebon',          'Le Havre',            49.49800, 0.12900, 'urbain',     'LH-CENTRE'),
    ('S11', 'Octeville-sur-Mer',       'Route de Cauville',           'Octeville-sur-Mer',   49.55600, 0.11500, 'rural',      'LH-NORD'),
    ('S12', 'Caucriauville',           'Rue de Verdun',               'Le Havre',            49.51500, 0.16500, 'urbain',     'LH-NORD');

INSERT INTO modele_capteur (fabricant, nom, polluants, periodicite_etalonnage_j, duree_vie_mois, prix_unitaire) VALUES
    ('Aeria', 'Station v1 (ESP32, SPS30, NO2-B43F, OX-B431)', 'pm25,pm10,no2,o3', 90,  36, 420.00),
    ('Aeria', 'Mini (ESP32, SDS011)',                         'pm25,pm10',        180, 24,  90.00),
    ('Aeria', 'Station v2 (ESP32-S3, SPS30, NO2-B43F)',       'pm25,pm10,no2',    120, 48, 380.00);

INSERT INTO capteur (id, numero_serie, modele_id, date_achat, version_firmware) VALUES
    ('LH-001', 'AE1-2025-0001', 1, '2025-10-20', '1.4.2'), ('LH-002', 'AE1-2025-0002', 1, '2025-10-20', '1.4.2'),
    ('LH-003', 'AE1-2025-0003', 1, '2025-10-20', '1.4.2'), ('LH-004', 'AE1-2025-0004', 1, '2025-10-20', '1.4.2'),
    ('LH-005', 'AE1-2025-0005', 1, '2025-10-20', '1.4.2'), ('LH-006', 'AE1-2025-0006', 1, '2025-10-20', '1.4.2'),
    ('LH-007', 'AEM-2025-0101', 2, '2025-11-28', '0.9.8'), ('LH-008', 'AEM-2025-0102', 2, '2025-11-28', '0.9.8'),
    ('LH-009', 'AEM-2025-0103', 2, '2025-11-28', '0.9.8'), ('LH-010', 'AEM-2025-0104', 2, '2025-11-28', '0.9.8'),
    ('LH-011', 'AEM-2025-0105', 2, '2025-11-28', '0.9.8'), ('LH-012', 'AEM-2025-0106', 2, '2025-11-28', '0.9.8'),
    ('LH-013', 'AE2-2026-0201', 3, '2026-04-15', '2.0.1'), ('LH-014', 'AE2-2026-0202', 3, '2026-04-15', '2.0.1'),
    ('LH-015', 'AE2-2026-0203', 3, '2026-09-30', '2.1.0'), ('LH-016', 'AE2-2026-0204', 3, '2026-09-30', '2.1.0');

INSERT INTO technicien (matricule, nom, prenom, email, telephone, zone_code, actif, date_embauche) VALUES
    ('T001', 'Lefèvre',  'Camille', 'camille.lefevre@aeria.example',  '02 35 00 00 01', 'LH-CENTRE', TRUE,  '2024-03-01'),
    ('T002', 'Benali',   'Yanis',   'yanis.benali@aeria.example',     '02 35 00 00 02', 'LH-CENTRE', TRUE,  '2025-01-06'),
    ('T003', 'Morel',    'Inès',    'ines.morel@aeria.example',       '02 35 00 00 03', 'LH-PORT',   TRUE,  '2023-09-11'),
    ('T004', 'Duval',    'Hugo',    'hugo.duval@aeria.example',       '02 35 00 00 04', 'LH-PORT',   TRUE,  '2025-06-02'),
    ('T005', 'Nguyen',   'Léa',     'lea.nguyen@aeria.example',       '02 35 00 00 05', 'LH-NORD',   TRUE,  '2024-11-18'),
    ('T006', 'Girard',   'Thomas',  'thomas.girard@aeria.example',    '02 35 00 00 06', 'LH-NORD',   TRUE,  '2025-09-01'),
    ('T007', 'Martin',   'Julie',   'julie.martin@aeria.example',     '02 35 00 00 07', 'LH-PORT',   FALSE, '2022-02-14');

INSERT INTO technicien_competence (technicien_id, competence_code, niveau) VALUES
    (1, 'ELEC', 3), (1, 'RESEAU', 2), (1, 'METRO', 2),
    (2, 'RESEAU', 3), (2, 'HAUTEUR', 2),
    (3, 'ELEC', 2), (3, 'METRO', 3), (3, 'HAUTEUR', 3),
    (4, 'RESEAU', 2), (4, 'ELEC', 1), (4, 'HAUTEUR', 2),
    (5, 'METRO', 2), (5, 'RESEAU', 2), (5, 'ELEC', 2),
    (6, 'HAUTEUR', 3), (6, 'ELEC', 1), (6, 'RESEAU', 1),
    (7, 'METRO', 3), (7, 'ELEC', 3);

INSERT INTO piece (reference, libelle, prix_unitaire, stock, seuil_reappro) VALUES
    ('PM-SPS30',   'Module particules Sensirion SPS30',     38.50, 8, 3),
    ('PM-SDS011',  'Module particules Nova SDS011',         19.90, 10, 4),
    ('GZ-NO2B43F', 'Cellule NO2 Alphasense B43F',          165.00, 4, 2),
    ('GZ-OXB431',  'Cellule O3 Alphasense OX-B431',        172.00, 4, 2),
    ('CT-ESP32',   'Carte ESP32 (contrôleur)',               9.80, 15, 5),
    ('AL-12V',     'Alimentation 12 V étanche',             24.00, 10, 4),
    ('AL-BATT',    'Batterie tampon Li-ion',                32.00, 8, 3),
    ('BX-IP65',    'Boîtier ventilé IP65',                  41.00, 6, 2),
    ('RF-ANT',     'Antenne Wi-Fi / LoRa',                   7.50, 12, 4),
    ('MC-FIX',     'Kit de fixation mât',                   15.00, 10, 3);

INSERT INTO utilisateur (login, mot_de_passe, role, technicien_id) VALUES
    ('responsable', crypt('demo-responsable', gen_salt('bf', 10)), 'RESPONSABLE', NULL),
    ('clefevre',    crypt('demo-technicien',  gen_salt('bf', 10)), 'TECHNICIEN', 1),
    ('ybenali',     crypt('demo-technicien',  gen_salt('bf', 10)), 'TECHNICIEN', 2),
    ('imorel',      crypt('demo-technicien',  gen_salt('bf', 10)), 'TECHNICIEN', 3),
    ('hduval',      crypt('demo-technicien',  gen_salt('bf', 10)), 'TECHNICIEN', 4),
    ('lnguyen',     crypt('demo-technicien',  gen_salt('bf', 10)), 'TECHNICIEN', 5),
    ('tgirard',     crypt('demo-technicien',  gen_salt('bf', 10)), 'TECHNICIEN', 6);

-- ------------------------------------------------------------------ une année d'exploitation

CREATE FUNCTION pg_temp.a_l_heure(p TIMESTAMPTZ) RETURNS VOID LANGUAGE sql AS $$
    SELECT set_config('aeria.horodatage', p::text, true)
$$;

-- Traite un ticket ouvert de bout en bout (ou partiellement s'il est récent)
CREATE FUNCTION pg_temp.traiter(p_ticket INTEGER, p_fin_simulation TIMESTAMPTZ) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    tk RECORD;
    v_tech INTEGER;
    v_iv INTEGER;
    v_plan TIMESTAMPTZ;
    v_debut TIMESTAMPTZ;
    v_fin TIMESTAMPTZ;
    v_ok BOOLEAN;
    v_nature VARCHAR;
    v_cr TEXT;
    v_essai INTEGER := 0;
BEGIN
    SELECT t.*, m.polluants INTO tk FROM ticket t JOIN capteur c ON c.id = t.capteur_id
        JOIN modele_capteur m ON m.id = c.modele_id WHERE t.id = p_ticket;
    v_plan := tk.ouvert_le + CASE tk.priorite
        WHEN 1 THEN make_interval(mins => (15 + random() * 120)::int)
        WHEN 2 THEN make_interval(mins => (90 + random() * 1500)::int)
        ELSE make_interval(hours => (20 + random() * 120)::int) END;
    v_nature := CASE WHEN tk.origine = 'preventif' THEN 'preventive'
                     WHEN tk.type_code = 'installation' THEN 'installation' ELSE 'corrective' END;
    LOOP
        v_essai := v_essai + 1;
        IF v_plan > p_fin_simulation THEN
            RETURN;  -- ticket récent : reste ouvert
        END IF;
        SELECT technicien_id INTO v_tech FROM proposer_techniciens(p_ticket)
            OFFSET CASE WHEN random() < 0.25 THEN 1 ELSE 0 END LIMIT 1;
        IF v_tech IS NULL THEN
            SELECT technicien_id INTO v_tech FROM proposer_techniciens(p_ticket) LIMIT 1;
        END IF;
        PERFORM pg_temp.a_l_heure(v_plan - INTERVAL '15 minutes');
        INSERT INTO intervention (ticket_id, technicien_id, nature, planifiee_le)
            VALUES (p_ticket, v_tech, v_nature, v_plan) RETURNING id INTO v_iv;

        v_debut := v_plan + make_interval(mins => (random() * 30)::int);
        IF v_debut > p_fin_simulation THEN
            RETURN;  -- assigné, pas encore commencé
        END IF;
        PERFORM pg_temp.a_l_heure(v_debut);
        UPDATE intervention SET debut = v_debut WHERE id = v_iv;

        v_fin := v_debut + make_interval(mins => (CASE WHEN tk.priorite = 1 THEN 30 + random() * 90
                                                     ELSE 45 + random() * 150 END)::int);
        IF v_fin > p_fin_simulation THEN
            RETURN;  -- en cours
        END IF;
        v_ok := random() < 0.88 OR v_essai >= 2;
        v_cr := CASE tk.type_code
            WHEN 'silence'    THEN CASE WHEN random() < 0.5 THEN 'Box Wi-Fi du site redémarrée, reconnexion MQTT vérifiée.'
                                        ELSE 'Alimentation défaillante remplacée, émission rétablie.' END
            WHEN 'panne'      THEN 'Module défectueux remplacé, mesures de contrôle cohérentes.'
            WHEN 'vandalisme' THEN 'Boîtier endommagé remplacé et fixation renforcée.'
            WHEN 'derive'     THEN 'Nettoyage du flux d''air et contrôle du capteur, dérive corrigée.'
            WHEN 'etalonnage' THEN 'Colocalisation avec la station de référence et calcul des coefficients.'
            ELSE 'Intervention réalisée.' END;
        IF NOT v_ok THEN
            v_cr := 'Échec : pièce manquante ou accès impossible, nouvelle intervention à planifier.';
        END IF;
        PERFORM pg_temp.a_l_heure(v_fin);
        UPDATE intervention SET fin = v_fin, resultat = CASE WHEN v_ok THEN 'reussie' ELSE 'echec' END,
               compte_rendu = v_cr WHERE id = v_iv;

        IF v_ok THEN
            -- pièces consommées selon la nature de la panne
            IF tk.type_code = 'panne' THEN
                INSERT INTO intervention_piece (intervention_id, piece_id, quantite)
                SELECT v_iv, p.id, 1 FROM piece p
                WHERE p.reference = (ARRAY['PM-SPS30', 'PM-SDS011', 'CT-ESP32', 'AL-12V', 'GZ-NO2B43F'])[1 + floor(random() * 5)::int];
            ELSIF tk.type_code = 'silence' AND v_cr LIKE 'Alimentation%' THEN
                INSERT INTO intervention_piece (intervention_id, piece_id, quantite)
                SELECT v_iv, id, 1 FROM piece WHERE reference = 'AL-12V';
            ELSIF tk.type_code = 'vandalisme' THEN
                INSERT INTO intervention_piece (intervention_id, piece_id, quantite)
                SELECT v_iv, id, 1 FROM piece WHERE reference IN ('BX-IP65', 'MC-FIX');
            ELSIF tk.type_code = 'etalonnage' THEN
                INSERT INTO etalonnage (capteur_id, intervention_id, station_reference, polluant, debut, fin, pente, ordonnee, r2, conforme)
                VALUES (tk.capteur_id, v_iv, CASE WHEN random() < 0.5 THEN 'FR05089' ELSE 'FR05090' END,
                        CASE WHEN tk.polluants LIKE '%no2%' AND random() < 0.4 THEN 'no2' ELSE 'pm25' END,
                        (v_fin - INTERVAL '7 days')::date, v_fin::date,
                        round((1 + (random() - 0.5) * 0.5)::numeric, 3), round(((random() - 0.5) * 6)::numeric, 2),
                        round((0.72 + random() * 0.26)::numeric, 3), TRUE);
            END IF;
            PERFORM pg_temp.a_l_heure(v_fin + INTERVAL '5 minutes');
            UPDATE ticket SET statut = 'resolu' WHERE id = p_ticket;
            v_fin := v_fin + make_interval(hours => (2 + random() * 46)::int);
            IF v_fin <= p_fin_simulation THEN
                PERFORM pg_temp.a_l_heure(v_fin);
                PERFORM set_config('aeria.utilisateur', 'responsable', true);
                UPDATE ticket SET statut = 'cloture' WHERE id = p_ticket;
                PERFORM set_config('aeria.utilisateur', '', true);
            END IF;
            RETURN;
        END IF;
        -- échec : replanification le lendemain
        PERFORM pg_temp.a_l_heure(v_fin + INTERVAL '10 minutes');
        UPDATE ticket SET statut = 'assigne' WHERE id = p_ticket;
        v_plan := v_fin + INTERVAL '18 hours';
    END LOOP;
END $$;

DO $$
DECLARE
    v_fin_simulation CONSTANT TIMESTAMPTZ := date_trunc('hour', now()) - INTERVAL '1 hour';
    v_jour DATE;
    v_moment TIMESTAMPTZ;
    v_type VARCHAR;
    r RECORD;
    v_traites INTEGER := 0;
    v_ignores INTEGER := 0;
BEGIN
    PERFORM setseed(0.42);

    -- mise en service du parc
    PERFORM pg_temp.a_l_heure('2025-11-17 09:00+01');
    INSERT INTO installation (capteur_id, site_id, date_pose)
    SELECT 'LH-00' || n, n, TIMESTAMPTZ '2025-11-17 09:00+01' + (n - 1) * INTERVAL '1 day' FROM generate_series(1, 6) n;
    INSERT INTO installation (capteur_id, site_id, date_pose)
    SELECT 'LH-' || lpad(n::text, 3, '0'), n, TIMESTAMPTZ '2025-12-08 10:00+01' + (n - 7) * INTERVAL '2 days'
    FROM generate_series(7, 12) n;

    v_jour := DATE '2025-11-18';
    WHILE v_jour <= v_fin_simulation::date LOOP
        v_moment := v_jour + TIME '06:00';

        -- maintenance préventive : échéances d'étalonnage
        PERFORM pg_temp.a_l_heure(v_moment);
        PERFORM planifier_etalonnages(v_moment);

        -- incidents aléatoires (≈ 1 par capteur tous les 45 jours)
        FOR r IN SELECT c.id FROM capteur c WHERE c.statut IN ('en_service', 'en_maintenance') ORDER BY c.id LOOP
            IF random() < 1.0 / 45 THEN
                v_type := (ARRAY['silence', 'silence', 'silence', 'panne', 'panne', 'derive', 'derive', 'vandalisme'])[1 + floor(random() * 8)::int];
                v_moment := v_jour + make_interval(hours => 7 + floor(random() * 14)::int, mins => floor(random() * 60)::int);
                IF v_moment <= v_fin_simulation THEN
                    PERFORM pg_temp.a_l_heure(v_moment);
                    INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le, ouvert_par)
                    VALUES (r.id, v_type, CASE WHEN v_type IN ('silence', 'derive') THEN 'auto' ELSE 'manuel' END,
                            CASE v_type WHEN 'silence' THEN 'Aucune mesure reçue'
                                        WHEN 'panne' THEN 'Capteur en panne signalé'
                                        WHEN 'derive' THEN 'Mesures incohérentes'
                                        ELSE 'Capteur dégradé' END,
                            NULL, v_moment, CASE WHEN v_type IN ('silence', 'derive') THEN 'systeme' ELSE 'responsable' END)
                    ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
                END IF;
            END IF;
        END LOOP;

        -- incident marquant : vol du capteur de Gonfreville (S09) en mai, remplacé le lendemain par un modèle v2
        IF v_jour = DATE '2026-05-20' THEN
            PERFORM pg_temp.a_l_heure('2026-05-20 08:10+02');
            INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le, ouvert_par)
            VALUES ('LH-009', 'vandalisme', 'manuel', 'Capteur arraché',
                    'Signalé par la mairie de Gonfreville : boîtier et capteur volés.', '2026-05-20 08:10+02', 'responsable')
            ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
        ELSIF v_jour = DATE '2026-05-21' THEN
            PERFORM pg_temp.a_l_heure('2026-05-21 10:00+02');
            UPDATE installation SET date_depose = '2026-05-21 10:00+02' WHERE capteur_id = 'LH-009' AND date_depose IS NULL;
            UPDATE capteur SET statut = 'reforme' WHERE id = 'LH-009';
            INSERT INTO installation (capteur_id, site_id, date_pose) VALUES ('LH-013', 9, '2026-05-21 11:30+02');
        END IF;

        -- traitement des tickets ouverts du jour (y compris ceux créés par les triggers)
        FOR r IN SELECT id FROM ticket WHERE statut = 'ouvert' AND ouvert_le < v_jour + 1 ORDER BY ouvert_le LOOP
            BEGIN
                PERFORM pg_temp.traiter(r.id, v_fin_simulation);
                v_traites := v_traites + 1;
            EXCEPTION WHEN exclusion_violation OR raise_exception THEN
                v_ignores := v_ignores + 1;  -- conflit d'agenda (RG13) : le ticket reste à replanifier
            END;
        END LOOP;

        -- réapprovisionnement mensuel (sauf le dernier mois, pour montrer les alertes de stock)
        IF extract(day FROM v_jour) = 1 AND v_jour < date_trunc('month', v_fin_simulation)::date THEN
            UPDATE piece SET stock = stock + 2 * seuil_reappro WHERE stock <= seuil_reappro;
        END IF;

        v_jour := v_jour + 1;
    END LOOP;

    -- télémétrie actuelle (normalement alimentée par la supervision du module 1)
    UPDATE capteur c SET derniere_communication = v_fin_simulation + make_interval(mins => 50 + floor(random() * 10)::int),
                         messages_recus = 96 * greatest(1, (v_fin_simulation::date - i.date_pose::date)),
                         messages_rejetes = (96 * greatest(1, (v_fin_simulation::date - i.date_pose::date)) * random() * 0.02)::bigint
    FROM installation i WHERE i.capteur_id = c.id AND i.date_depose IS NULL;
    -- deux situations que la supervision détectera au premier passage
    UPDATE capteur SET derniere_communication = v_fin_simulation - INTERVAL '3 hours' WHERE id = 'LH-011';
    UPDATE capteur SET messages_rejetes = (messages_recus * 0.071)::bigint WHERE id = 'LH-004';

    -- travail en cours au moment de la démonstration
    PERFORM pg_temp.a_l_heure(v_fin_simulation - INTERVAL '5 hours');
    INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le, ouvert_par)
    VALUES ('LH-002', 'panne', 'manuel', 'Ventilateur du capteur bruyant puis arrêté',
            'Signalé par un riverain via le formulaire de contact.', v_fin_simulation - INTERVAL '5 hours', 'responsable')
    ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
    PERFORM pg_temp.a_l_heure(v_fin_simulation - INTERVAL '26 hours');
    INSERT INTO ticket (capteur_id, type_code, origine, titre, description, ouvert_le, ouvert_par)
    VALUES ('LH-012', 'vandalisme', 'manuel', 'Boîtier tagué, grille d''aération obstruée', NULL,
            v_fin_simulation - INTERVAL '26 hours', 'responsable')
    ON CONFLICT (capteur_id, type_code) WHERE statut IN ('ouvert', 'assigne', 'en_cours') DO NOTHING;
    INSERT INTO intervention (ticket_id, technicien_id, nature, planifiee_le)
    SELECT t.id, 6, 'corrective', date_trunc('day', v_fin_simulation) + INTERVAL '1 day 9 hours'
    FROM ticket t WHERE t.capteur_id = 'LH-012' AND t.statut = 'ouvert';
    PERFORM pg_temp.a_l_heure(v_fin_simulation);
    PERFORM planifier_etalonnages(v_fin_simulation);

    RAISE NOTICE 'Démo : % tickets traités, % reportés (conflit d''agenda)', v_traites, v_ignores;
END $$;
