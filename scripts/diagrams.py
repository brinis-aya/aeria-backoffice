"""Generate the modelling diagrams of the back-office.

* docs/figures/mcd.svg          Merise conceptual data model (entities, associations, cardinalities)
* docs/bpmn/<process>.bpmn      BPMN 2.0 XML with diagram interchange (opens in Camunda Modeler / bpmn.io)
* docs/bpmn/<process>.svg       rendering of the same layout (for the README and the report)

    python scripts/diagrams.py
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
INK, INK2, LINE, FILL, FILL2, ACCENT = "#1f2328", "#57606a", "#57606a", "#ffffff", "#f6f8fa", "#2a78d6"
FONT = "font-family:Segoe UI,Helvetica,Arial,sans-serif"


def esc(s: str) -> str:
    return escape(s, {'"': "&quot;"})


# =================================================================================== MCD

@dataclass
class Entity:
    name: str
    col: float
    row: float
    ident: str
    attrs: list[str]


@dataclass
class Assoc:
    name: str
    col: float
    row: float
    links: list[tuple[str, str]]          # (entity, cardinality)
    attrs: list[str] = field(default_factory=list)


ENTITIES = [
    Entity("UTILISATEUR", 0, 2.35, "login", ["mot_de_passe", "role", "actif"]),
    Entity("ZONE", 1, 0, "code_zone", ["libelle_zone"]),
    Entity("TECHNICIEN", 1, 1, "matricule", ["nom", "prenom", "email", "telephone", "actif", "date_embauche"]),
    Entity("COMPETENCE", 1, 4, "code_competence", ["libelle_competence"]),
    Entity("ETALONNAGE", 2, 1, "id_etalonnage", ["station_reference", "polluant", "debut", "fin", "pente",
                                                 "ordonnee", "r2", "conforme"]),
    Entity("INTERVENTION", 2, 2.35, "id_intervention", ["nature", "planifiee_le", "debut", "fin",
                                                        "compte_rendu", "resultat"]),
    Entity("PIECE", 2, 3.55, "reference", ["libelle_piece", "prix_unitaire", "stock", "seuil_reappro"]),
    Entity("SITE", 3, 0, "code_site", ["nom_site", "adresse", "commune", "latitude", "longitude", "environnement"]),
    Entity("CAPTEUR", 3, 1.15, "id_capteur", ["numero_serie", "statut", "date_achat", "version_firmware",
                                              "derniere_communication", "messages_recus", "messages_rejetes"]),
    Entity("TICKET", 3, 2.35, "id_ticket", ["priorite", "statut", "origine", "titre", "description",
                                            "ouvert_le", "echeance_sla", "resolu_le", "cloture_le"]),
    Entity("TYPE_INCIDENT", 3, 4, "code_type", ["libelle_type", "priorite_defaut"]),
    Entity("HISTORIQUE", 4, 2.35, "id_historique", ["ancien_statut", "nouveau_statut", "change_le", "par"]),
    Entity("MODELE_CAPTEUR", 5, 1.15, "id_modele", ["fabricant", "nom_modele", "polluants",
                                                    "periodicite_etalonnage_j", "duree_vie_mois", "prix"]),
]

ASSOCS = [
    Assoc("CORRESPONDRE", 0.25, 2.0, [("UTILISATEUR", "0,1"), ("TECHNICIEN", "0,1")]),
    Assoc("APPARTENIR", 2.05, 0.2, [("SITE", "1,1"), ("ZONE", "0,n")]),
    Assoc("RATTACHER", 1.25, 0.62, [("TECHNICIEN", "1,1"), ("ZONE", "0,n")]),
    Assoc("INSTALLER", 3.62, 0.98, [("CAPTEUR", "0,n"), ("SITE", "0,n")], ["date_pose", "date_depose"]),
    Assoc("RELEVER", 4.5, 1.45, [("CAPTEUR", "1,1"), ("MODELE_CAPTEUR", "0,n")]),
    Assoc("ETALONNER", 2.72, 1.05, [("ETALONNAGE", "1,1"), ("CAPTEUR", "0,n")]),
    Assoc("PRODUIRE", 2.35, 2.02, [("ETALONNAGE", "0,1"), ("INTERVENTION", "0,n")]),
    Assoc("REALISER", 1.45, 2.2, [("INTERVENTION", "1,1"), ("TECHNICIEN", "0,n")]),
    Assoc("TRAITER", 2.5, 2.62, [("INTERVENTION", "1,1"), ("TICKET", "0,n")]),
    Assoc("CONCERNER", 3.6, 2.05, [("TICKET", "1,1"), ("CAPTEUR", "0,n")]),
    Assoc("QUALIFIER", 3.55, 3.6, [("TICKET", "1,1"), ("TYPE_INCIDENT", "0,n")]),
    Assoc("TRACER", 4.0, 3.45, [("HISTORIQUE", "1,1"), ("TICKET", "0,n")]),
    Assoc("CONSOMMER", 2.0, 3.3, [("INTERVENTION", "0,n"), ("PIECE", "0,n")], ["quantite"]),
    Assoc("MAITRISER", 1.2, 3.05, [("TECHNICIEN", "0,n"), ("COMPETENCE", "0,n")], ["niveau"]),
    Assoc("EXIGER", 2.3, 4.5, [("TYPE_INCIDENT", "1,1"), ("COMPETENCE", "0,n")]),
]

PX, PY, X0, Y0, BOX_W, LINE_H = 360, 250, 30, 30, 230, 17


def ent_box(e: Entity) -> tuple[float, float, float, float]:
    h = 32 + LINE_H * (len(e.attrs) + 1) + 10
    return X0 + e.col * PX, Y0 + e.row * PY, BOX_W, h


def assoc_size(a: Assoc) -> tuple[float, float]:
    w = max(110, 8.2 * max([len(a.name)] + [len(x) for x in a.attrs]) + 30)
    return w, 34 + LINE_H * len(a.attrs)


def border_point(box, tx, ty):
    """Point where the segment from the box centre to (tx, ty) leaves the rectangle."""
    x, y, w, h = box
    cx, cy = x + w / 2, y + h / 2
    dx, dy = tx - cx, ty - cy
    if dx == 0 and dy == 0:
        return cx, cy
    s = min((w / 2) / abs(dx) if dx else math.inf, (h / 2) / abs(dy) if dy else math.inf)
    return cx + dx * s, cy + dy * s


def mcd_svg() -> str:
    ents = {e.name: e for e in ENTITIES}
    width = X0 * 2 + 5 * PX + BOX_W
    height = Y0 * 2 + 4.55 * PY + 40
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width:.0f} {height:.0f}" width="{width:.0f}" '
           f'height="{height:.0f}" style="{FONT};background:#fff">',
           f'<rect width="100%" height="100%" fill="#fff"/>']
    # links first (under the shapes)
    for a in ASSOCS:
        aw, ah = assoc_size(a)
        ax, ay = X0 + a.col * PX + BOX_W / 2, Y0 + a.row * PY
        for name, card in a.links:
            box = ent_box(ents[name])
            bx, by = border_point(box, ax, ay)
            out.append(f'<line x1="{ax:.1f}" y1="{ay:.1f}" x2="{bx:.1f}" y2="{by:.1f}" stroke="{LINE}" stroke-width="1.4"/>')
            # cardinality just outside the entity, beside the line (readable even on short links)
            length = math.hypot(ax - bx, ay - by) or 1
            ux, uy = (ax - bx) / length, (ay - by) / length
            if abs(ux) >= abs(uy):   # mostly horizontal link: label above the line
                lx, ly = bx + ux * 16, by - 8
            else:                    # mostly vertical link: label to the right of the line
                lx, ly = bx + 18, by + uy * 16 + 4
            out.append(f'<text x="{lx:.1f}" y="{ly:.1f}" font-size="12" text-anchor="middle" fill="{ACCENT}" '
                       f'font-weight="700">{card}</text>')
    for a in ASSOCS:
        aw, ah = assoc_size(a)
        ax, ay = X0 + a.col * PX + BOX_W / 2, Y0 + a.row * PY
        out.append(f'<ellipse cx="{ax:.1f}" cy="{ay:.1f}" rx="{aw / 2:.1f}" ry="{ah / 2 + 4:.1f}" fill="{FILL2}" '
                   f'stroke="{INK}" stroke-width="1.3"/>')
        top = ay - ah / 2 + 15
        out.append(f'<text x="{ax:.1f}" y="{top + 2:.1f}" font-size="12" font-weight="700" text-anchor="middle" '
                   f'fill="{INK}">{esc(a.name)}</text>')
        for i, at in enumerate(a.attrs):
            out.append(f'<text x="{ax:.1f}" y="{top + 18 + i * LINE_H:.1f}" font-size="11.5" text-anchor="middle" '
                       f'fill="{INK2}">{esc(at)}</text>')
    for e in ENTITIES:
        x, y, w, h = ent_box(e)
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{FILL}" stroke="{INK}" stroke-width="1.4"/>')
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="28" fill="{ACCENT}" stroke="{INK}" stroke-width="1.4"/>')
        out.append(f'<text x="{x + w / 2}" y="{y + 19}" font-size="13" font-weight="700" text-anchor="middle" '
                   f'fill="#fff">{esc(e.name)}</text>')
        out.append(f'<text x="{x + 10}" y="{y + 46}" font-size="12" fill="{INK}" text-decoration="underline" '
                   f'font-weight="600">{esc(e.ident)}</text>')
        for i, at in enumerate(e.attrs):
            out.append(f'<text x="{x + 10}" y="{y + 46 + (i + 1) * LINE_H}" font-size="12" fill="{INK2}">{esc(at)}</text>')
    out.append(f'<text x="{X0}" y="{height - 12:.0f}" font-size="12" fill="{INK2}">MCD Aeria back-office - '
               f'identifiants soulignés, cardinalités (min,max) côté entité.</text>')
    out.append("</svg>")
    return "\n".join(out)


# =================================================================================== BPMN

@dataclass
class Node:
    id: str
    kind: str          # start, start_timer, end, task, user, service, script, rule, manual, xor
    name: str
    lane: str
    col: float
    dy: float = 0
    label: str = "above"   # gateway name position: above | below | left | aboveleft


@dataclass
class Flow:
    src: str
    tgt: str
    name: str = ""
    route: str = "auto"  # auto | below | above


@dataclass
class Process:
    id: str
    name: str
    lanes: list[tuple[str, str]]   # (id, label)
    nodes: list[Node]
    flows: list[Flow]
    boundary: list[tuple[str, str, str]] = field(default_factory=list)  # (id, attached task, label)


COL_W, LANE_H, LANE_X, LANE_LABEL_W, TOP = 150, 170, 20, 34, 60
TASK_W, TASK_H, EVT_R, GW = 118, 64, 18, 46

PROCESSES = [
    Process(
        "maintenance_corrective", "Maintenance corrective d'un capteur",
        [("sup", "Supervision (système)"), ("resp", "Responsable maintenance"), ("tech", "Technicien"),
         ("mag", "Magasin")],
        [
            Node("start", "start", "Anomalie détectée ou signalée", "sup", 0),
            Node("t_create", "service", "Créer le ticket (RG3, RG4)", "sup", 1),
            Node("t_qualify", "user", "Qualifier le ticket (type, priorité)", "resp", 2),
            Node("g_valid", "xor", "Anomalie avérée ?", "resp", 3, label="below"),
            Node("end_cancel", "end", "Ticket annulé", "resp", 4, -52),
            Node("t_assign", "rule", "Choisir un technicien compétent (RG6)", "resp", 4.6, 22),
            Node("t_go", "user", "Intervenir sur site", "tech", 5.8),
            Node("g_part", "xor", "Pièce nécessaire ?", "tech", 6.9),
            Node("t_part", "user", "Fournir la pièce, commander si sous le seuil (RG7)", "mag", 7.9),
            Node("g_merge", "xor", "", "tech", 8.9),
            Node("t_fix", "manual", "Réparer et contrôler les mesures", "tech", 9.9),
            Node("g_ok", "xor", "Mesures conformes ?", "tech", 11),
            Node("t_report", "user", "Saisir le compte rendu", "tech", 12),
            Node("t_resolve", "service", "Passer le ticket « résolu » (RG9)", "sup", 13),
            Node("t_close", "user", "Valider et clôturer", "resp", 14),
            Node("end_ok", "end", "Capteur remis en service (RG11)", "resp", 15),
            Node("t_escalate", "user", "Relancer ou réaffecter", "resp", 6.9, 22),
        ],
        [
            Flow("start", "t_create"), Flow("t_create", "t_qualify"), Flow("t_qualify", "g_valid"),
            Flow("g_valid", "end_cancel", "non", "vfirst"), Flow("g_valid", "t_assign", "oui"), Flow("t_assign", "t_go"),
            Flow("t_go", "g_part"), Flow("g_part", "t_part", "oui"), Flow("g_part", "g_merge", "non"),
            Flow("t_part", "g_merge"), Flow("g_merge", "t_fix"), Flow("t_fix", "g_ok"),
            Flow("g_ok", "t_go", "non", "below"), Flow("g_ok", "t_report", "oui"), Flow("t_report", "t_resolve"),
            Flow("t_resolve", "t_close"), Flow("t_close", "end_ok"),
            Flow("b_sla", "t_escalate", "", "vfirst"), Flow("t_escalate", "t_assign", "", "above"),
        ],
        boundary=[("b_sla", "t_go", "Délai SLA dépassé")],
    ),
    Process(
        "etalonnage_preventif", "Étalonnage préventif par colocalisation",
        [("sys", "Système"), ("resp", "Responsable maintenance"), ("tech", "Technicien")],
        [
            Node("start", "start_timer", "Chaque jour à 6 h", "sys", 0),
            Node("t_due", "service", "Calculer les échéances d'étalonnage", "sys", 1),
            Node("g_due", "xor", "Échéance à moins de 7 jours ?", "sys", 2, label="below"),
            Node("end_none", "end", "Rien à planifier", "sys", 3, -45),
            Node("t_ticket", "service", "Ouvrir un ticket préventif (RG15)", "sys", 3, 30),
            Node("t_plan", "user", "Planifier la colocalisation", "resp", 4),
            Node("t_coloc", "manual", "Placer le capteur près de la station de référence (7 jours)", "tech", 5),
            Node("t_reg", "script", "Régression capteur / référence : pente, ordonnée, R²", "sys", 6),
            Node("g_conf", "xor", "Conforme ? (RG10)", "sys", 7, label="aboveleft"),
            Node("t_coef", "service", "Enregistrer les coefficients de correction", "sys", 8, -40),
            Node("t_drift", "service", "Ouvrir un ticket « dérive »", "sys", 8, 45),
            Node("end_drift", "end", "Maintenance corrective", "sys", 9, 45),
            Node("t_back", "manual", "Réinstaller le capteur sur son site", "tech", 9),
            Node("t_close", "user", "Clôturer le ticket", "resp", 10),
            Node("end_ok", "end", "Capteur étalonné", "resp", 11),
        ],
        [
            Flow("start", "t_due"), Flow("t_due", "g_due"), Flow("g_due", "end_none", "non", "vfirst"),
            Flow("g_due", "t_ticket", "oui"), Flow("t_ticket", "t_plan"), Flow("t_plan", "t_coloc"),
            Flow("t_coloc", "t_reg"), Flow("t_reg", "g_conf"), Flow("g_conf", "t_coef", "oui", "vfirst"),
            Flow("g_conf", "t_drift", "non", "vfirst"), Flow("t_drift", "end_drift"), Flow("t_coef", "t_back"),
            Flow("t_back", "t_close"), Flow("t_close", "end_ok"),
        ],
    ),
    Process(
        "deploiement_capteur", "Déploiement d'un nouveau capteur",
        [("resp", "Responsable maintenance"), ("mag", "Magasin"), ("tech", "Technicien"), ("sys", "Système")],
        [
            Node("start", "start", "Nouveau point de mesure décidé", "resp", 0),
            Node("t_site", "user", "Choisir et créer le site (zone, environnement)", "resp", 1),
            Node("g_stock", "xor", "Capteur en stock ?", "mag", 2, label="left"),
            Node("t_order", "user", "Commander un capteur", "mag", 3, -40),
            Node("g_merge", "xor", "", "mag", 4),
            Node("t_prep", "manual", "Préparer : firmware, identifiants MQTT, ACL", "tech", 5),
            Node("t_pose", "manual", "Poser le capteur (RG1, RG2)", "tech", 6),
            Node("t_record", "service", "Enregistrer l'installation (statut « en service »)", "sys", 7),
            Node("t_check", "service", "Vérifier 24 h de mesures reçues", "sys", 8),
            Node("g_data", "xor", "Données valides ?", "sys", 9, label="below"),
            Node("t_diag", "user", "Diagnostiquer sur site", "tech", 10, 20),
            Node("end_ok", "end", "Capteur en service", "resp", 10.5),
        ],
        [
            Flow("start", "t_site"), Flow("t_site", "g_stock"), Flow("g_stock", "t_order", "non", "vfirst"),
            Flow("g_stock", "g_merge", "oui"), Flow("t_order", "g_merge"), Flow("g_merge", "t_prep"),
            Flow("t_prep", "t_pose"), Flow("t_pose", "t_record"), Flow("t_record", "t_check"),
            Flow("t_check", "g_data"), Flow("g_data", "end_ok", "oui"), Flow("g_data", "t_diag", "non", "z"),
            Flow("t_diag", "t_pose", "", "below"),
        ],
    ),
]

BPMN_TAG = {"start": "startEvent", "start_timer": "startEvent", "end": "endEvent", "task": "task",
            "user": "userTask", "service": "serviceTask", "script": "scriptTask", "rule": "businessRuleTask",
            "manual": "manualTask", "xor": "exclusiveGateway"}


def geometry(p: Process):
    lane_y = {lid: TOP + i * LANE_H for i, (lid, _) in enumerate(p.lanes)}
    shapes = {}
    for n in p.nodes:
        cx = LANE_X + LANE_LABEL_W + 70 + n.col * COL_W
        cy = lane_y[n.lane] + LANE_H / 2 + n.dy
        if n.kind in ("start", "start_timer", "end"):
            w = h = 2 * EVT_R
        elif n.kind == "xor":
            w = h = GW
        else:
            w, h = TASK_W, TASK_H
        shapes[n.id] = (cx - w / 2, cy - h / 2, w, h)
    for bid, task, _ in p.boundary:
        x, y, w, h = shapes[task]
        shapes[bid] = (x + w - 30 - EVT_R, y - EVT_R, 2 * EVT_R, 2 * EVT_R)  # on the top edge
    width = LANE_X + LANE_LABEL_W + 70 + max(n.col for n in p.nodes) * COL_W + 110
    return lane_y, shapes, width


def waypoints(src, tgt, route):
    sx, sy, sw, sh = src
    tx, ty, tw, th = tgt
    scx, scy, tcx, tcy = sx + sw / 2, sy + sh / 2, tx + tw / 2, ty + th / 2
    if route == "below":
        low = max(sy + sh, ty + th) + 30
        return [(scx, sy + sh), (scx, low), (tcx, low), (tcx, ty + th)]
    if route == "above":
        high = min(sy, ty) - 22
        return [(scx, sy), (scx, high), (tcx, high), (tcx, ty)]
    if abs(scx - tcx) < 1:                       # vertically aligned
        return [(scx, sy + sh), (tcx, ty)] if tcy > scy else [(scx, sy), (tcx, ty + th)]
    if abs(scy - tcy) < 1:                       # same row
        return [(sx + sw, scy), (tx, tcy)] if tcx > scx else [(sx, scy), (tx + tw, tcy)]
    if route == "vfirst" or (route != "z" and sw == GW and abs(tcy - scy) > LANE_H / 2):
        # gateway branch to another lane: leave by the top/bottom corner, then go across
        return [(scx, sy + sh if tcy > scy else sy), (scx, tcy), (tx if tcx > scx else tx + tw, tcy)]
    # default "Z" route: out on the right, turn halfway, in on the left
    mid = (sx + sw + tx) / 2 if tcx > scx else (sx + tx + tw) / 2
    if tcx > scx:
        return [(sx + sw, scy), (mid, scy), (mid, tcy), (tx, tcy)]
    return [(sx, scy), (mid, scy), (mid, tcy), (tx + tw, tcy)]


def bpmn_xml(p: Process) -> str:
    lane_y, shapes, width = geometry(p)
    flows = [(f"f{i}", f) for i, f in enumerate(p.flows, 1)]
    incoming = {n.id: [] for n in p.nodes}
    outgoing = {n.id: [] for n in p.nodes}
    for b, _, _ in p.boundary:
        outgoing[b] = []
    for fid, f in flows:
        outgoing[f.src].append(fid)
        incoming.setdefault(f.tgt, []).append(fid)
    x = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
         'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
         'xmlns:di="http://www.omg.org/spec/DD/20100524/DI" '
         f'id="def_{p.id}" targetNamespace="https://github.com/brinis-aya/aeria-backoffice" '
         'exporter="aeria scripts/diagrams.py" exporterVersion="1.0">',
         f'  <bpmn:collaboration id="collab_{p.id}">',
         f'    <bpmn:participant id="pool_{p.id}" name="{esc(p.name)}" processRef="{p.id}"/>',
         '  </bpmn:collaboration>',
         f'  <bpmn:process id="{p.id}" name="{esc(p.name)}" isExecutable="false">',
         '    <bpmn:laneSet id="lanes">']
    for lid, label in p.lanes:
        x.append(f'      <bpmn:lane id="lane_{lid}" name="{esc(label)}">')
        for n in p.nodes:
            if n.lane == lid:
                x.append(f'        <bpmn:flowNodeRef>{n.id}</bpmn:flowNodeRef>')
        for b, task, _ in p.boundary:
            if next(n for n in p.nodes if n.id == task).lane == lid:
                x.append(f'        <bpmn:flowNodeRef>{b}</bpmn:flowNodeRef>')
        x.append('      </bpmn:lane>')
    x.append('    </bpmn:laneSet>')
    for n in p.nodes:
        tag = BPMN_TAG[n.kind]
        inner = [f'      <bpmn:incoming>{f}</bpmn:incoming>' for f in incoming.get(n.id, [])]
        inner += [f'      <bpmn:outgoing>{f}</bpmn:outgoing>' for f in outgoing.get(n.id, [])]
        if n.kind == "start_timer":
            inner.append('      <bpmn:timerEventDefinition id="timer_daily"><bpmn:timeCycle>R/P1D</bpmn:timeCycle>'
                         '</bpmn:timerEventDefinition>')
        x.append(f'    <bpmn:{tag} id="{n.id}" name="{esc(n.name)}">')
        x.extend(inner)
        x.append(f'    </bpmn:{tag}>')
    for b, task, label in p.boundary:
        x.append(f'    <bpmn:boundaryEvent id="{b}" name="{esc(label)}" attachedToRef="{task}" cancelActivity="false">')
        x.extend(f'      <bpmn:outgoing>{f}</bpmn:outgoing>' for f in outgoing[b])
        x.append(f'      <bpmn:timerEventDefinition id="timer_{b}"><bpmn:timeDuration>PT4H</bpmn:timeDuration>'
                 f'</bpmn:timerEventDefinition>')
        x.append('    </bpmn:boundaryEvent>')
    for fid, f in flows:
        name = f' name="{esc(f.name)}"' if f.name else ""
        x.append(f'    <bpmn:sequenceFlow id="{fid}" sourceRef="{f.src}" targetRef="{f.tgt}"{name}/>')
    x.append('  </bpmn:process>')
    # diagram interchange
    pool_h = len(p.lanes) * LANE_H
    x.append(f'  <bpmndi:BPMNDiagram id="dia_{p.id}">')
    x.append(f'    <bpmndi:BPMNPlane id="plane_{p.id}" bpmnElement="collab_{p.id}">')
    x.append(f'      <bpmndi:BPMNShape id="pool_{p.id}_di" bpmnElement="pool_{p.id}" isHorizontal="true">'
             f'<dc:Bounds x="{LANE_X}" y="{TOP}" width="{width - LANE_X}" height="{pool_h}"/></bpmndi:BPMNShape>')
    for lid, _ in p.lanes:
        x.append(f'      <bpmndi:BPMNShape id="lane_{lid}_di" bpmnElement="lane_{lid}" isHorizontal="true">'
                 f'<dc:Bounds x="{LANE_X + LANE_LABEL_W}" y="{lane_y[lid]}" width="{width - LANE_X - LANE_LABEL_W}" '
                 f'height="{LANE_H}"/></bpmndi:BPMNShape>')
    for nid, (sx, sy, sw, sh) in shapes.items():
        marker = ' isMarkerVisible="true"' if nid.startswith("g_") else ""
        x.append(f'      <bpmndi:BPMNShape id="{nid}_di" bpmnElement="{nid}"{marker}>'
                 f'<dc:Bounds x="{sx:.0f}" y="{sy:.0f}" width="{sw:.0f}" height="{sh:.0f}"/></bpmndi:BPMNShape>')
    for fid, f in flows:
        pts = waypoints(shapes[f.src], shapes[f.tgt], f.route)
        x.append(f'      <bpmndi:BPMNEdge id="{fid}_di" bpmnElement="{fid}">')
        x.extend(f'        <di:waypoint x="{px:.0f}" y="{py:.0f}"/>' for px, py in pts)
        x.append('      </bpmndi:BPMNEdge>')
    x += ['    </bpmndi:BPMNPlane>', '  </bpmndi:BPMNDiagram>', '</bpmn:definitions>']
    return "\n".join(x)


def wrap(text: str, width: int) -> list[str]:
    words, lines, cur = text.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width and cur:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    return lines + ([cur] if cur else [])


def icon(kind, x, y):
    c = f'stroke="{INK2}" fill="none" stroke-width="1.1"'
    if kind == "user":
        return (f'<circle cx="{x + 8}" cy="{y + 6}" r="3.2" {c}/>'
                f'<path d="M{x + 2} {y + 16} q6 -9 12 0" {c}/>')
    if kind == "service":
        return (f'<circle cx="{x + 8}" cy="{y + 9}" r="5" {c}/><circle cx="{x + 8}" cy="{y + 9}" r="2" {c}/>'
                + "".join(f'<line x1="{x + 8 + 5 * math.cos(a)}" y1="{y + 9 + 5 * math.sin(a)}" '
                          f'x2="{x + 8 + 7.5 * math.cos(a)}" y2="{y + 9 + 7.5 * math.sin(a)}" {c}/>'
                          for a in [i * math.pi / 4 for i in range(8)]))
    if kind == "script":
        return (f'<rect x="{x + 2}" y="{y + 2}" width="12" height="15" {c}/>'
                + "".join(f'<line x1="{x + 4}" y1="{y + 6 + i * 4}" x2="{x + 12}" y2="{y + 6 + i * 4}" {c}/>'
                          for i in range(3)))
    if kind == "rule":
        return (f'<rect x="{x + 2}" y="{y + 3}" width="14" height="11" {c}/>'
                f'<line x1="{x + 2}" y1="{y + 7}" x2="{x + 16}" y2="{y + 7}" {c}/>'
                f'<line x1="{x + 6}" y1="{y + 7}" x2="{x + 6}" y2="{y + 14}" {c}/>')
    if kind == "manual":
        return f'<path d="M{x + 2} {y + 12} h4 v-7 h3 v7 h2 v-6 h3 v9 h-12 z" {c}/>'
    return ""


def bpmn_svg(p: Process) -> str:
    lane_y, shapes, width = geometry(p)
    pool_h = len(p.lanes) * LANE_H
    height = TOP + pool_h + 40
    nodes = {n.id: n for n in p.nodes}
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width + 20:.0f} {height:.0f}" '
         f'width="{width + 20:.0f}" height="{height:.0f}" style="{FONT}">',
         '<defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" '
         f'orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="{INK}"/></marker></defs>',
         '<rect width="100%" height="100%" fill="#fff"/>',
         f'<text x="{LANE_X}" y="36" font-size="17" font-weight="700" fill="{INK}">{esc(p.name)}</text>',
         f'<rect x="{LANE_X}" y="{TOP}" width="{width - LANE_X}" height="{pool_h}" fill="none" stroke="{INK}" '
         f'stroke-width="1.4"/>']
    for i, (lid, label) in enumerate(p.lanes):
        y = lane_y[lid]
        o.append(f'<rect x="{LANE_X}" y="{y}" width="{width - LANE_X}" height="{LANE_H}" '
                 f'fill="{FILL2 if i % 2 else FILL}" stroke="{INK}" stroke-width="1"/>')
        o.append(f'<line x1="{LANE_X + LANE_LABEL_W}" y1="{y}" x2="{LANE_X + LANE_LABEL_W}" y2="{y + LANE_H}" '
                 f'stroke="{INK}" stroke-width="1"/>')
        cx, cy = LANE_X + LANE_LABEL_W / 2, y + LANE_H / 2
        o.append(f'<text transform="translate({cx + 4} {cy}) rotate(-90)" font-size="12" font-weight="600" '
                 f'text-anchor="middle" fill="{INK}">{esc(label)}</text>')
    for f in p.flows:
        pts = waypoints(shapes[f.src], shapes[f.tgt], f.route)
        d = " ".join(f"{'M' if i == 0 else 'L'}{x:.1f} {y:.1f}" for i, (x, y) in enumerate(pts))
        o.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="1.3" marker-end="url(#arr)"/>')
        if f.name:
            (x1, y1), (x2, y2) = pts[0], pts[1]
            lx, ly = (x1 + x2) / 2, (y1 + y2) / 2
            off = (8, -6) if abs(y2 - y1) < 1 else (6, 4)
            o.append(f'<text x="{lx + off[0]:.1f}" y="{ly + off[1]:.1f}" font-size="11" fill="{ACCENT}" '
                     f'font-weight="600">{esc(f.name)}</text>')
    for nid, (x, y, w, h) in shapes.items():
        if nid in nodes:
            n = nodes[nid]
            kind, name = n.kind, n.name
        else:
            kind, name = "boundary", next(lbl for b, _, lbl in p.boundary if b == nid)
        cx, cy = x + w / 2, y + h / 2
        if kind in ("start", "start_timer", "end", "boundary"):
            sw = 3.2 if kind == "end" else 1.5
            o.append(f'<circle cx="{cx}" cy="{cy}" r="{EVT_R}" fill="#fff" stroke="{INK}" stroke-width="{sw}"/>')
            if kind in ("start_timer", "boundary"):
                o.append(f'<circle cx="{cx}" cy="{cy}" r="{EVT_R - 4}" fill="#fff" stroke="{INK}" stroke-width="1"/>')
                o.append(f'<path d="M{cx} {cy - 8} L{cx} {cy} L{cx + 6} {cy}" stroke="{INK}" fill="none" stroke-width="1.3"/>')
            if kind == "boundary":  # label beside the event, outside the task
                for i, line in enumerate(wrap(name, 14)):
                    o.append(f'<text x="{x + w + 4}" y="{cy - 4 + i * 13}" font-size="11" fill="{INK2}">{esc(line)}</text>')
            else:
                for i, line in enumerate(wrap(name, 22)):
                    o.append(f'<text x="{cx}" y="{y + h + 14 + i * 13}" font-size="11" text-anchor="middle" '
                             f'fill="{INK2}">{esc(line)}</text>')
        elif kind == "xor":
            o.append(f'<path d="M{cx} {y} L{x + w} {cy} L{cx} {y + h} L{x} {cy} Z" fill="#fff" stroke="{INK}" '
                     f'stroke-width="1.4"/>')
            o.append(f'<path d="M{cx - 8} {cy - 8} L{cx + 8} {cy + 8} M{cx + 8} {cy - 8} L{cx - 8} {cy + 8}" '
                     f'stroke="{INK}" stroke-width="2.4"/>')
            lines = wrap(name, 20 if n.label != "left" else 14)
            for i, line in enumerate(lines):
                if n.label == "aboveleft":
                    tx_, ty, anchor = x + 4, y - 6 - (len(lines) - 1 - i) * 13, "end"
                elif n.label == "left":
                    tx_, ty, anchor = x - 6, cy - 2 + (i - (len(lines) - 1) / 2) * 13, "end"
                elif n.label == "below":
                    tx_, ty, anchor = cx, y + h + 14 + i * 13, "middle"
                else:
                    tx_, ty, anchor = cx, y - 8 - (len(lines) - 1 - i) * 13, "middle"
                o.append(f'<text x="{tx_}" y="{ty:.1f}" font-size="11" text-anchor="{anchor}" fill="{INK2}">{esc(line)}</text>')
        else:
            o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="9" fill="#fff" stroke="{INK}" '
                     f'stroke-width="1.4"/>')
            o.append(icon(kind, x + 4, y + 3))
            lines = wrap(name, 19)
            for i, line in enumerate(lines):
                o.append(f'<text x="{cx}" y="{cy + 4 + (i - (len(lines) - 1) / 2) * 13:.1f}" font-size="11" '
                         f'text-anchor="middle" fill="{INK}">{esc(line)}</text>')
    o.append(f'<text x="{LANE_X}" y="{height - 12}" font-size="11" fill="{INK2}">BPMN 2.0 - source : '
             f'docs/bpmn/{p.id}.bpmn (Camunda Modeler, bpmn.io)</text>')
    o.append("</svg>")
    return "\n".join(o)


def main():
    (ROOT / "docs" / "figures").mkdir(parents=True, exist_ok=True)
    (ROOT / "docs" / "bpmn").mkdir(parents=True, exist_ok=True)
    (ROOT / "docs" / "figures" / "mcd.svg").write_text(mcd_svg(), encoding="utf-8")
    for p in PROCESSES:
        (ROOT / "docs" / "bpmn" / f"{p.id}.bpmn").write_text(bpmn_xml(p), encoding="utf-8")
        (ROOT / "docs" / "bpmn" / f"{p.id}.svg").write_text(bpmn_svg(p), encoding="utf-8")
    print("diagrams written")


if __name__ == "__main__":
    main()
