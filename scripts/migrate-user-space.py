#!/usr/bin/env python3
"""Local MySQL one-time quota migration. Dry-run by default; never deletes business data."""
import argparse
import os
import re
import socket
import subprocess
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'backend/r-pan-api-services/src/main/resources'


def migrate(config_path, apply=False):
    config = {}
    for line in config_path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            config[key.strip()] = value.strip()
    url = urllib.parse.urlparse(config['spring.datasource.url'].removeprefix('jdbc:'))
    if url.hostname not in ('localhost', '127.0.0.1'):
        raise RuntimeError('This helper only supports local MySQL')
    database = url.path.lstrip('/')
    if not re.fullmatch(r'[a-zA-Z0-9_]+', database):
        raise RuntimeError('Invalid database identifier')
    env = os.environ.copy()
    env['MYSQL_PWD'] = config.get('spring.datasource.password', '')
    command = ['mysql', '--no-defaults', '--protocol=TCP', '-h', url.hostname, '-P', str(url.port or 3306),
               '-u', config['spring.datasource.username'], '--connect-timeout=5', '--batch', '--skip-column-names', database]

    def query(sql):
        result = subprocess.run(command, input=sql, text=True, env=env, capture_output=True, check=True)
        return result.stdout.strip()

    invalid = int(query("SELECT COUNT(*) FROM r_pan_user_file u LEFT JOIN r_pan_file p ON p.file_id=u.real_file_id "
                        "WHERE u.folder_flag=0 AND (p.file_id IS NULL OR p.file_size NOT REGEXP '^[0-9]{1,18}$')"))
    if invalid:
        raise RuntimeError(f'{invalid} logical files have missing or invalid physical sizes; repair before migrating')
    invalid_tasks = int(query("SELECT COUNT(*) FROM r_pan_upload_task WHERE file_size<0 OR task_state NOT IN "
                              "('UPLOADING','FINALIZING','COMPLETED','FAILED','EXPIRED')"))
    if invalid_tasks:
        raise RuntimeError('Invalid upload tasks; repair before migrating')
    column = int(query("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                       "AND table_name='r_pan_user_file' AND column_name='space_bytes'"))
    tables = int(query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                       "AND table_name IN ('r_pan_user_space','r_pan_upload_reservation')"))
    if (column, tables) not in ((0, 0), (1, 2)):
        raise RuntimeError('Partially applied schema; inspect before retrying')
    print('Validated file sizes; schema:', 'present' if column else 'not applied')
    if not apply:
        print('Dry run only. Stop application writers, then run with --apply.')
        return
    # Refuse mutation while the configured backend may still be using the old write paths.
    port = int(config.get('server.port', '8080'))
    with socket.socket() as probe:
        probe.settimeout(0.3)
        if probe.connect_ex(('127.0.0.1', port)) == 0:
            raise RuntimeError(f'Backend port {port} is listening; stop writers before applying migration')
    if not column:
        query((RESOURCES / 'sql/migrations/20261117_user_space_schema.sql').read_text())
    count = int(query('SELECT COUNT(*) FROM r_pan_user_space'))
    reservations = int(query('SELECT COUNT(*) FROM r_pan_upload_reservation'))
    if count == 0 and reservations == 0:
        total = int(config.get('rpan.space.default-total-bytes', '10737418240'))
        if not 1 <= total <= 1125899906842624:
            raise RuntimeError('Invalid configured default capacity')
        backfill = (RESOURCES / 'sql/migrations/20261117_user_space_backfill.sql').read_text().replace('10737418240', str(total))
        query('START TRANSACTION;\n' + backfill + '\nCOMMIT;')
    elif count == 0:
        raise RuntimeError('Reservations exist without accounts; inspect migration state')
    mismatch = int(query("SELECT COUNT(*) FROM r_pan_user_space a WHERE used_bytes<>(SELECT COALESCE(SUM(space_bytes),0) "
                         "FROM r_pan_user_file u WHERE u.user_id=a.user_id) OR reserved_bytes<>(SELECT COALESCE(SUM(bytes),0) "
                         "FROM r_pan_upload_reservation r WHERE r.user_id=a.user_id AND r.state='RESERVED') "
                         "OR used_bytes<0 OR reserved_bytes<0 OR total_bytes-used_bytes-reserved_bytes<0"))
    missing = int(query("SELECT COUNT(*) FROM (SELECT user_id FROM r_pan_user UNION SELECT user_id FROM r_pan_user_file "
                        "UNION SELECT user_id FROM r_pan_upload_task) owners LEFT JOIN r_pan_user_space a ON a.user_id=owners.user_id "
                        "WHERE a.user_id IS NULL"))
    if mismatch or missing:
        raise RuntimeError('Space account reconciliation failed; do not start quota writers')
    print('Migration and reconciliation passed. Accounts:', query('SELECT COUNT(*) FROM r_pan_user_space'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=RESOURCES / 'config/application.properties')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    migrate(args.config, args.apply)
