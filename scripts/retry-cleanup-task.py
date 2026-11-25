#!/usr/bin/env python3
"""Explicit local operator recovery of one MANUAL cleanup task; never reactivates a file."""
import argparse, os, pathlib, subprocess, urllib.parse
p=argparse.ArgumentParser();p.add_argument('task_id',type=int);p.add_argument('--config',type=pathlib.Path);a=p.parse_args()
if a.task_id<=0:raise SystemExit('task_id must be positive')
root=pathlib.Path(__file__).resolve().parents[1]
config={}
config_path=a.config or root/'backend/r-pan-api-services/src/main/resources/config/application.properties'
for line in config_path.read_text().splitlines():
    if line and not line.lstrip().startswith('#') and '=' in line:
        k,v=line.split('=',1);config[k.strip()]=v.strip()
u=urllib.parse.urlparse(config['spring.datasource.url'].removeprefix('jdbc:'))
if u.hostname not in ('127.0.0.1','localhost'):raise SystemExit('This learning operator script only permits a local database')
env=os.environ.copy();env['MYSQL_PWD']=config['spring.datasource.password']
cmd=['mysql','--no-defaults','--protocol=TCP','-h',u.hostname,'-P',str(u.port or 3306),'-u',config['spring.datasource.username'],'--connect-timeout=5','--batch','--skip-column-names',u.path.lstrip('/')]
def sql(value):
    result=subprocess.run(cmd,input=value,env=env,text=True,capture_output=True)
    if result.returncode:raise SystemExit(result.stderr)
    return result.stdout.strip()
hint=sql('SELECT real_file_id FROM r_pan_file_cleanup_task WHERE task_id='+str(a.task_id))
if not hint:raise SystemExit('Task not found')
file_id=int(hint)
result=sql(f'''START TRANSACTION;
SELECT file_id FROM r_pan_file WHERE file_id={file_id} FOR UPDATE;
SELECT task_id FROM r_pan_file_cleanup_task WHERE task_id={a.task_id} FOR UPDATE;
UPDATE r_pan_file_cleanup_task t
SET t.execution_state='RETRY',t.delivery_state='PENDING',t.execution_attempts=0,
 t.next_delivery_at=CURRENT_TIMESTAMP(3),t.last_error=NULL,t.lease_owner=NULL,t.lease_until=NULL,t.updated_at=CURRENT_TIMESTAMP(3)
WHERE t.task_id={a.task_id} AND t.execution_state='MANUAL'
 AND EXISTS(SELECT 1 FROM r_pan_file f WHERE f.file_id=t.real_file_id AND f.lifecycle_state='DELETING' AND f.real_path=t.storage_locator
            AND (f.storage_type IS NULL OR f.storage_type=t.storage_type))
 AND NOT EXISTS(SELECT 1 FROM r_pan_user_file uf WHERE uf.real_file_id=t.real_file_id);
SELECT ROW_COUNT();
COMMIT;''')
print('Task queued for retry' if result.splitlines()[-1]=='1' else 'No change: task must be MANUAL, file DELETING, locator unchanged and no remaining references')
