#!/usr/bin/env python3
"""Create private, randomly generated runtime settings; never overwrite an existing file."""
import argparse
import base64
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=Path('.env'))
parser.add_argument('--port', type=int, default=18081)
args = parser.parse_args()
settings = {
 'COSPACE_MYSQL_PASSWORD': secrets.token_hex(20),
 'COSPACE_MYSQL_ROOT_PASSWORD': secrets.token_hex(20),
 'COSPACE_REDIS_PASSWORD': secrets.token_hex(20),
 'COSPACE_JWT_SECRET': base64.b64encode(secrets.token_bytes(32)).decode(),
 'COSPACE_HTTP_PORT': str(args.port),
 'COSPACE_SHARE_PREFIX': f'http://127.0.0.1:{args.port}/share/',
 'COSPACE_OSS_ENABLED': 'false',
 'COSPACE_UPLOAD_STORAGE_TYPE': 'com.rubin.rpan.storage.local.LocalStorageProcessor',
 'COSPACE_OSS_DIRECT_ENABLED': 'false',
 'COSPACE_WORKSPACE_ENABLED': 'true',
 'COSPACE_PERSONAL_QUOTA_BYTES': '104857600',
 'COSPACE_TEAM_QUOTA_BYTES': '209715200',
 'COSPACE_MAX_FILE_BYTES': '104857600',
}
args.output.parent.mkdir(parents=True, exist_ok=True)
fd=os.open(args.output,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as stream:
 for key,value in settings.items():stream.write(key+'='+value+'\n')
print('Created private runtime configuration:',args.output)
