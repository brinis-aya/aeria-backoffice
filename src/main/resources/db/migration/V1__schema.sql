-- =====================================================================================
-- Aeria back-office - modèle physique de données (MPD), PostgreSQL 15+
-- Issu du MLD (docs/merise.md). Les règles de gestion (RG) sont rappelées en commentaire.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;  -- contrainte d'exclusion sur les installations (RG2)

-- ------------------------------------------------------------------ référentiels

CREATE TABLE zone (
    code        VARCHAR(16) PRIMARY KEY,
    libelle     VARCHAR(80) NOT NULL
);

CREATE TABLE site (
    id            SERIAL PRIMARY KEY,
    code          VARCHAR(16) NOT NULL UNIQUE,
    nom           VARCHAR(120) NOT NULL,
    adresse       VARCHAR(200),
    commune       VARCHAR(80) NOT NULL,
    latitude      NUMERIC(8,5) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude     NUMERIC(8,5) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    environnement VARCHAR(12) NOT NULL CHECK (environnement IN ('urbain', 'trafic', 'industriel', 'rural')),
    zone_code     VARCHAR(16) NOT NULL REFERENCES zone(code)
);

CREATE TABLE modele_capteur (
    id                         SERIAL PRIMARY KEY,
    fabricant                  VARCHAR(60) NOT NULL,
    nom                        VARCHAR(60) NOT NULL,
    polluants                  VARCHAR(60) NOT NULL,            -- ex. 'pm25,pm10'
    periodicite_etalonnage_j   SMALLINT NOT NULL CHECK (periodicite_etalonnage_j > 0),
    duree_vie_mois             SMALLINT NOT NULL CHECK (duree_vie_mois > 0),
    prix_unitaire              NUMERIC(10,2) NOT NULL CHECK (prix_unitaire >= 0),
    UNIQUE (fabricant, nom)
);

CREATE TABLE capteur (
    id                       VARCHAR(32) PRIMARY KEY,           -- identifiant MQTT (module 1), ex. LH-001
    numero_serie             VARCHAR(40) NOT NULL UNIQUE,
    modele_id                INTEGER NOT NULL REFERENCES modele_capteur(id),
    statut                   VARCHAR(16) NOT NULL DEFAULT 'en_stock'
                             CHECK (statut IN ('en_stock', 'en_service', 'en_maintenance', 'hors_service', 'reforme')),
    date_achat               DATE NOT NULL,
    version_firmware         VARCHAR(20),
    derniere_communication   TIMESTAMPTZ,                       -- alimentés par la supervision (module 1)
    messages_recus           BIGINT NOT NULL DEFAULT 0 CHECK (messages_recus >= 0),
    messages_rejetes         BIGINT NOT NULL DEFAULT 0 CHECK (messages_rejetes >= 0)
);

-- ------------------------------------------------------------------ personnes et compétences

CREATE TABLE competence (
    code     VARCHAR(12) PRIMARY KEY,
    libelle  VARCHAR(80) NOT NULL
);

CREATE TABLE technicien (
    id             SERIAL PRIMARY KEY,
    matricule      VARCHAR(12) NOT NULL UNIQUE,
    nom            VARCHAR(60) NOT NULL,
    prenom         VARCHAR(60) NOT NULL,
    email          VARCHAR(120) NOT NULL UNIQUE,
    telephone      VARCHAR(20),
    zone_code      VARCHAR(16) NOT NULL REFERENCES zone(code),
    actif          BOOLEAN NOT NULL DEFAULT TRUE,
    date_embauche  DATE NOT NULL
);

-- Association « maîtriser » (technicien 0,n — 0,n compétence) porteuse du niveau
CREATE TABLE technicien_competence (
    technicien_id    INTEGER NOT NULL REFERENCES technicien(id) ON DELETE CASCADE,
    competence_code  VARCHAR(12) NOT NULL REFERENCES competence(code),
    niveau           SMALLINT NOT NULL CHECK (niveau BETWEEN 1 AND 3),
    PRIMARY KEY (technicien_id, competence_code)
);

-- ------------------------------------------------------------------ parc : installations

-- Association « installer » (capteur 0,n — 0,n site) historisée
CREATE TABLE installation (
    id           SERIAL PRIMARY KEY,
    capteur_id   VARCHAR(32) NOT NULL REFERENCES capteur(id),
    site_id      INTEGER NOT NULL REFERENCES site(id),
    date_pose    TIMESTAMPTZ NOT NULL,
    date_depose  TIMESTAMPTZ,
    CHECK (date_depose IS NULL OR date_depose > date_pose),
    -- RG2 : un capteur n'est installé que sur un seul site à la fois (périodes disjointes)
    CONSTRAINT installation_sans_chevauchement EXCLUDE USING gist (
        capteur_id WITH =,
        tstzrange(date_pose, coalesce(date_depose, 'infinity'), '[)') WITH &&
    )
);
CREATE INDEX ix_installation_site ON installation (site_id) WHERE date_depose IS NULL;

-- ------------------------------------------------------------------ maintenance

CREATE TABLE type_incident (
    code                VARCHAR(16) PRIMARY KEY,
    libelle             VARCHAR(80) NOT NULL,
    priorite_defaut     SMALLINT NOT NULL CHECK (priorite_defaut BETWEEN 1 AND 3),
    competence_requise  VARCHAR(12) NOT NULL REFERENCES competence(code)
);

CREATE TABLE ticket (
    id              SERIAL PRIMARY KEY,
    capteur_id      VARCHAR(32) NOT NULL REFERENCES capteur(id),
    type_code       VARCHAR(16) NOT NULL REFERENCES type_incident(code),
    priorite        SMALLINT CHECK (priorite BETWEEN 1 AND 3),       -- 1 = urgente ; défaut : type (RG4)
    statut          VARCHAR(10) NOT NULL DEFAULT 'ouvert'
                    CHECK (statut IN ('ouvert', 'assigne', 'en_cours', 'resolu', 'cloture', 'annule')),
    origine         VARCHAR(10) NOT NULL CHECK (origine IN ('auto', 'manuel', 'preventif')),
    titre           VARCHAR(160) NOT NULL,
    description     TEXT,
    ouvert_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    echeance_sla    TIMESTAMPTZ,                                       -- calculée (RG4)
    resolu_le       TIMESTAMPTZ,
    cloture_le      TIMESTAMPTZ,
    ouvert_par      VARCHAR(40) NOT NULL DEFAULT 'systeme'
);
-- RG3 : au plus un ticket non terminé par capteur et par type (pas de doublon d'alerte)
CREATE UNIQUE INDEX ux_ticket_actif ON ticket (capteur_id, type_code)
    WHERE statut IN ('ouvert', 'assigne', 'en_cours');
CREATE INDEX ix_ticket_statut ON ticket (statut, priorite);

CREATE TABLE intervention (
    id             SERIAL PRIMARY KEY,
    ticket_id      INTEGER NOT NULL REFERENCES ticket(id),
    technicien_id  INTEGER NOT NULL REFERENCES technicien(id),
    nature         VARCHAR(12) NOT NULL CHECK (nature IN ('corrective', 'preventive', 'installation', 'depose')),
    planifiee_le   TIMESTAMPTZ NOT NULL,
    debut          TIMESTAMPTZ,
    fin            TIMESTAMPTZ,
    compte_rendu   TEXT,
    resultat       VARCHAR(10) CHECK (resultat IN ('reussie', 'partielle', 'echec')),
    CHECK (fin IS NULL OR (debut IS NOT NULL AND fin > debut)),
    CHECK (resultat IS NULL OR fin IS NOT NULL)
);
CREATE INDEX ix_intervention_technicien ON intervention (technicien_id) WHERE fin IS NULL;

CREATE TABLE piece (
    id              SERIAL PRIMARY KEY,
    reference       VARCHAR(30) NOT NULL UNIQUE,
    libelle         VARCHAR(120) NOT NULL,
    prix_unitaire   NUMERIC(10,2) NOT NULL CHECK (prix_unitaire >= 0),
    stock           INTEGER NOT NULL CHECK (stock >= 0),              -- RG7 : jamais négatif
    seuil_reappro   INTEGER NOT NULL CHECK (seuil_reappro >= 0)
);

-- Association « consommer » (intervention 0,n — 0,n pièce) porteuse de la quantité
CREATE TABLE intervention_piece (
    intervention_id  INTEGER NOT NULL REFERENCES intervention(id) ON DELETE CASCADE,
    piece_id         INTEGER NOT NULL REFERENCES piece(id),
    quantite         INTEGER NOT NULL CHECK (quantite > 0),
    PRIMARY KEY (intervention_id, piece_id)
);

-- Étalonnage par colocalisation avec une station de référence : valeur_corrigée = pente × brute + ordonnée
CREATE TABLE etalonnage (
    id                 SERIAL PRIMARY KEY,
    capteur_id         VARCHAR(32) NOT NULL REFERENCES capteur(id),
    intervention_id    INTEGER REFERENCES intervention(id),
    station_reference  VARCHAR(16) NOT NULL,                         -- code LCSQA, ex. FR05089
    polluant           VARCHAR(8) NOT NULL CHECK (polluant IN ('pm25', 'pm10', 'no2', 'o3')),
    debut              DATE NOT NULL,
    fin                DATE NOT NULL CHECK (fin > debut),
    pente              NUMERIC(6,3) NOT NULL,
    ordonnee           NUMERIC(6,2) NOT NULL,
    r2                 NUMERIC(4,3) NOT NULL CHECK (r2 BETWEEN 0 AND 1),
    conforme           BOOLEAN NOT NULL                              -- RG10
);
CREATE INDEX ix_etalonnage_capteur ON etalonnage (capteur_id, fin DESC);

-- Traçabilité (RG12)
CREATE TABLE ticket_historique (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       INTEGER NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    ancien_statut   VARCHAR(10),
    nouveau_statut  VARCHAR(10) NOT NULL,
    change_le       TIMESTAMPTZ NOT NULL,
    par             VARCHAR(40) NOT NULL
);

-- ------------------------------------------------------------------ comptes applicatifs

CREATE TABLE utilisateur (
    login          VARCHAR(40) PRIMARY KEY,
    mot_de_passe   VARCHAR(100) NOT NULL,                            -- BCrypt
    role           VARCHAR(12) NOT NULL CHECK (role IN ('RESPONSABLE', 'TECHNICIEN')),
    technicien_id  INTEGER UNIQUE REFERENCES technicien(id),
    actif          BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK ((role = 'TECHNICIEN') = (technicien_id IS NOT NULL))
);

-- ------------------------------------------------------------------ données de référence

INSERT INTO competence (code, libelle) VALUES
    ('ELEC',    'Électronique et alimentation'),
    ('RESEAU',  'Connectivité (Wi-Fi, LoRa, MQTT)'),
    ('METRO',   'Métrologie et étalonnage'),
    ('HAUTEUR', 'Travail en hauteur (mâts, façades)');

INSERT INTO type_incident (code, libelle, priorite_defaut, competence_requise) VALUES
    ('silence',     'Capteur muet (aucune mesure reçue)',   1, 'RESEAU'),
    ('panne',       'Panne matérielle',                     1, 'ELEC'),
    ('vandalisme',  'Dégradation ou vol',                   1, 'HAUTEUR'),
    ('derive',      'Mesures incohérentes ou rejetées',     2, 'METRO'),
    ('etalonnage',  'Étalonnage périodique',                3, 'METRO'),
    ('installation','Pose ou déplacement d''un capteur',    3, 'HAUTEUR');
