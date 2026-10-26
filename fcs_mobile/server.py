"""Authenticated LAN API; localhost-only server administration."""
import hmac
import ipaddress
import json
import os
import secrets
import tempfile
from pathlib import Path
from urllib.parse import urlsplit
from flask import Flask, Request, abort, current_app, jsonify, render_template_string, request, send_file
from werkzeug.exceptions import HTTPException
from .library import Library
from .previews import preview
from .paths import DATA_ROOT, storage_path


class LocalUploadRequest(Request):
    def _get_file_stream(self, total_content_length, content_type, filename=None, content_length=None):
        return tempfile.SpooledTemporaryFile(max_size=512 * 1024, mode="rb+", dir=current_app.config["UPLOAD_TEMP_PATH"])

ADMIN_HTML = """<!doctype html><html lang="fr"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>FCS Mobile — Serveur</title>
<style>body{font:16px system-ui;background:#111827;color:#e5e7eb;max-width:760px;margin:48px auto;padding:24px}section{background:#1f2937;padding:24px;border-radius:16px;margin:20px 0}input{box-sizing:border-box;width:100%;padding:12px;margin:12px 0}button{padding:12px;background:#a7f3d0;border:0;border-radius:8px}code{overflow-wrap:anywhere}h1{color:#a7f3d0}</style>
<h1>FCS Mobile</h1><p>Administration du serveur local · La galerie est dans l'application Android.</p>
<section><h2>Connexion du téléphone</h2><p>Serveur : adresse IPv4 locale de ce PC, port 5001.</p><p>Clé de connexion :</p><code>{{ token }}</code><p>À saisir dans les réglages de FCS Mobile sur le téléphone.</p></section>
<section><h2>Bibliothèque</h2><p>{{ stats.media_count }} médias · {{ stats.trash_count }} dans la corbeille</p><p>Les médias restent conservés après leur retrait du téléphone. Corbeille : 30 jours.</p><label>Dossier de la bibliothèque</label><input id="path" value="{{ path }}"><button onclick="save()">Enregistrer</button><p id="message"></p></section>
<script>async function save(){try{let r=await fetch('/admin/config',{method:'POST',headers:{'Content-Type':'application/json','X-FCS-Admin':{{ csrf|tojson }}},body:JSON.stringify({backup_path:document.getElementById('path').value})});let d=await r.json();document.getElementById('message').textContent=r.ok?'Dossier enregistré.':d.error;}catch(e){document.getElementById('message').textContent='Serveur inaccessible.'}}</script></html>"""


def private_address(value):
    try:
        address = ipaddress.ip_address(value)
        return address.is_loopback or any(address in network for network in (
            ipaddress.ip_network("10.0.0.0/8"), ipaddress.ip_network("172.16.0.0/12"),
            ipaddress.ip_network("192.168.0.0/16"), ipaddress.ip_network("fc00::/7")))
    except ValueError:
        return False


def create_app(config_dir=None, library_path=None):
    app = Flask(__name__)
    config_dir = storage_path(config_dir or os.environ.get("FCS_MOBILE_CONFIG_DIR", str(DATA_ROOT / "config")))
    config_dir.mkdir(parents=True, exist_ok=True)
    config_file = config_dir / "config.json"
    config = json.loads(config_file.read_text(encoding="utf-8")) if config_file.exists() else {
        "backup_path": "library", "api_key": secrets.token_urlsafe(32)}
    if library_path is not None:
        config["backup_path"] = str(library_path)
    config.setdefault("api_key", secrets.token_urlsafe(32))
    library_root = storage_path(config["backup_path"], config_dir.parent)
    config["backup_path"] = library_root.relative_to(config_dir.parent).as_posix() if library_root.is_relative_to(config_dir.parent) else str(library_root)
    upload_temp = storage_path(config_dir.parent / "tmp")
    upload_temp.mkdir(parents=True, exist_ok=True)
    app.request_class = LocalUploadRequest
    app.config["UPLOAD_TEMP_PATH"] = str(upload_temp)

    def save_config():
        tmp = config_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(config, indent=2), encoding="utf-8")
        os.replace(tmp, config_file)

    save_config()
    app.extensions["library"] = Library(library_root)
    app.config.update(MAX_FORM_MEMORY_SIZE=1024 * 1024)
    admin_csrf = secrets.token_urlsafe(32)

    def library():
        return app.extensions["library"]

    @app.before_request
    def restrict_access():
        if not private_address(request.remote_addr or ""):
            abort(403, "Réseau local uniquement")
        if request.path.startswith("/api/"):
            supplied = request.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied, "Bearer " + config["api_key"]):
                abort(401, "Clé de connexion incorrecte")
        elif request.path == "/" or request.path.startswith("/admin/"):
            if not ipaddress.ip_address(request.remote_addr).is_loopback:
                abort(403, "Administration sur le PC uniquement")
            if urlsplit("http://" + request.host).hostname not in {"127.0.0.1", "localhost", "::1"}:
                abort(403, "Utiliser localhost pour l'administration")
            if request.method != "GET" and not hmac.compare_digest(request.headers.get("X-FCS-Admin", ""), admin_csrf):
                abort(403, "Requête d'administration invalide")

    @app.after_request
    def no_cache(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    @app.errorhandler(Exception)
    def failure(error):
        if isinstance(error, HTTPException):
            return jsonify(error=error.description), error.code
        if isinstance(error, LookupError):
            return jsonify(error=str(error)), 404
        if isinstance(error, (ValueError, TypeError, KeyError)):
            return jsonify(error=str(error)), 400
        app.logger.exception("Erreur serveur")
        return jsonify(error="Erreur serveur : consulter le journal du PC"), 500

    @app.get("/")
    def admin():
        return render_template_string(ADMIN_HTML, token=config["api_key"], path=str(library().root), stats=library().stats(), csrf=admin_csrf)

    @app.post("/admin/config")
    def admin_config():
        data = request.get_json()
        if not isinstance(data, dict) or not isinstance(data.get("backup_path"), str) or not data["backup_path"].strip():
            raise ValueError("Dossier requis")
        path = Path(data["backup_path"]).expanduser()
        if not path.is_absolute():
            raise ValueError("Choisir un chemin absolu")
        path = storage_path(path)
        if path.resolve() != library().root:
            with library().db() as db:
                if db.execute("SELECT count(*) FROM media").fetchone()[0]:
                    raise ValueError("La bibliothèque contient déjà des médias. Déplacer le dossier complet serveur arrêté, puis modifier config.json.")
            replacement = Library(path)
            config["backup_path"] = replacement.root.relative_to(config_dir.parent).as_posix() if replacement.root.is_relative_to(config_dir.parent) else str(replacement.root)
            save_config()
            app.extensions["library"] = replacement
        return jsonify(success=True)

    @app.get("/health")
    def health():
        return jsonify(service="FCS Mobile", protocol=2)

    @app.get("/api/status")
    def status():
        return jsonify(service="FCS Mobile", protocol=2, **library().stats())

    @app.post("/api/check")
    def check():
        data = request.get_json()
        if not isinstance(data, dict) or not isinstance(data.get("sha256"), list) or len(data["sha256"]) > 500:
            raise ValueError("Liste de 500 empreintes maximum requise")
        return jsonify(files=library().states(data["sha256"]))

    @app.post("/api/upload")
    def upload():
        file = request.files.get("file")
        if file is None:
            raise ValueError("Fichier requis")
        metadata = json.loads(request.form.get("metadata", "null"))
        if not isinstance(metadata, dict):
            raise ValueError("Métadonnées requises")
        return jsonify(library().save(file.stream, metadata))

    @app.get("/api/media")
    def media():
        offset = int(request.args.get("offset", 0))
        limit = int(request.args.get("limit", 120))
        if offset < 0 or not 1 <= limit <= 200:
            raise ValueError("Pagination invalide")
        return jsonify(library().listing(request.args.get("trash") == "1", offset, limit))

    @app.get("/api/media/<media_id>/neighbors")
    def neighbors(media_id):
        return jsonify(library().neighbors(media_id, request.args.get("trash") == "1"))

    @app.get("/api/media/<media_id>/original")
    def original(media_id):
        row = library().get(media_id, include_trash=True)
        return send_file(library().path(row), mimetype=row["mime"], download_name=row["name"], conditional=True)

    @app.get("/api/media/<media_id>/thumbnail")
    @app.get("/api/media/<media_id>/preview")
    def thumbnail(media_id):
        row = library().get(media_id, include_trash=True)
        return send_file(preview(library(), row, request.path.endswith("/preview")), mimetype="image/jpeg", conditional=True)

    @app.post("/api/media/<media_id>/trash")
    def trash(media_id):
        library().trash(media_id)
        return jsonify(success=True)

    @app.post("/api/media/<media_id>/restore")
    def restore(media_id):
        library().restore(media_id)
        return jsonify(success=True)

    return app
