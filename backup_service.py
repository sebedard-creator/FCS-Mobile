"""FCS Mobile — local library server. Run from any directory."""
import argparse
import threading
import os
import socket
from fcs_mobile.server import create_app
from fcs_mobile.paths import prepare_process_storage
from fcs_mobile.previews import warm_thumbnails


def create_listener(host='0.0.0.0', port=5001):
    """Windows must refuse a second server, including an older detached instance."""
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if os.name == 'nt':
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((host, port))
        listener.listen(socket.SOMAXCONN)
        return listener
    except OSError:
        listener.close()
        raise


def main():
    parser = argparse.ArgumentParser(description='FCS Mobile — bibliothèque locale')
    parser.add_argument('--import-folder', help='Copier un dossier existant sans modifier les sources')
    args = parser.parse_args()
    prepare_process_storage()
    app = create_app()
    library = app.extensions['library']
    if args.import_folder:
        print(library.import_folder(args.import_folder))
        return

    def housekeeping():
        while True:
            try:
                app.extensions['library'].purge_expired()
            except Exception:
                app.logger.exception('Échec du nettoyage de la corbeille')
            threading.Event().wait(3600)

    with create_listener() as listener:
        stop_previews = threading.Event()
        threading.Thread(target=warm_thumbnails, args=(lambda: app.extensions['library'], stop_previews, app.logger),
                         name='fcs-thumbnails', daemon=True).start()
        threading.Thread(target=housekeeping, name='fcs-trash', daemon=True).start()
        print('FCS Mobile — serveur local')
        print(f'Bibliothèque : {library.root}')
        print('Administration : http://127.0.0.1:5001 (sur ce PC uniquement)')
        print("La clé de connexion du téléphone est disponible dans l'administration.")
        from waitress import serve
        try:
            serve(app, sockets=[listener], threads=8)
        finally:
            stop_previews.set()


if __name__ == '__main__':
    main()
