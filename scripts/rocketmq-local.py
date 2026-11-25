#!/usr/bin/env python3
"""Start the existing RocketMQ distribution using project-owned logs, data and PIDs."""
import argparse, os, pathlib, shutil, signal, socket, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'runtime' / 'rocketmq'
parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['start', 'stop', 'status'])
parser.add_argument('--integration-topic', action='store_true', help='Also create the isolated integration-test topic')
parser.add_argument('--rocketmq-home', default=os.environ.get('ROCKETMQ_HOME', ''))
args = parser.parse_args()
if not args.rocketmq_home: raise SystemExit('Set ROCKETMQ_HOME or pass --rocketmq-home')
MQ_HOME = pathlib.Path(args.rocketmq_home).resolve()
RUNTIME.mkdir(parents=True, exist_ok=True)

def alive(name):
    pidfile = RUNTIME / (name + '.pid')
    if not pidfile.exists(): return None
    pid = int(pidfile.read_text())
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True)
    return pid if '-Drpan.rocketmq.local=true' in result.stdout and str(RUNTIME) in result.stdout else None

def port_up(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=.3): return True
    except OSError: return False

if args.action == 'status':
    for name, port in [('nameserver', 9876), ('broker', 10911)]: print(name, 'pid=', alive(name), 'port=', port, 'listening=', port_up(port))
    raise SystemExit()
if args.action == 'stop':
    for name in ['broker', 'nameserver']:
        pid = alive(name)
        if pid: os.kill(pid, signal.SIGTERM); print('Stopped', name, pid)
    raise SystemExit()
if not (MQ_HOME / 'lib').is_dir(): raise SystemExit('Specify --rocketmq-home pointing to an extracted RocketMQ distribution')
java_home = os.environ.get('JAVA_HOME')
java = str(pathlib.Path(java_home) / 'bin/java') if java_home else shutil.which('java')
if not java: raise SystemExit('Set JAVA_HOME or add java to PATH')
(RUNTIME / 'home').mkdir(exist_ok=True)
base = [java, '-XX:ActiveProcessorCount=2', '-Drpan.rocketmq.local=true', '-Duser.home=' + str(RUNTIME / 'home'), '-Drocketmq.home.dir=' + str(MQ_HOME), '-Drocketmq.client.logRoot=' + str(RUNTIME / 'logs'), '-cp', str(MQ_HOME / 'lib' / '*')]
conf = RUNTIME / 'broker.conf'
conf.write_text('''brokerClusterName=RPanLocal
brokerName=rpan-local
brokerId=0
brokerIP1=127.0.0.1
listenPort=10911
namesrvAddr=127.0.0.1:9876
brokerRole=ASYNC_MASTER
flushDiskType=SYNC_FLUSH
autoCreateTopicEnable=false
autoCreateSubscriptionGroup=true
mappedFileSizeCommitLog=16777216
storePathRootDir=''' + str(RUNTIME / 'store') + '\nstorePathCommitLog=' + str(RUNTIME / 'store' / 'commitlog') + '\n')

def start(name, port, heap, main, options):
    if alive(name): print(name, 'already running'); return
    if port_up(port): raise SystemExit('Port %s occupied by another process; no changes made to it' % port)
    with (RUNTIME / (name + '.log')).open('ab') as log:
        proc = subprocess.Popen(base[:1] + ['-Xms64m', '-Xmx' + heap] + base[1:] + [main] + options, stdout=log, stderr=log, start_new_session=True)
    (RUNTIME / (name + '.pid')).write_text(str(proc.pid))
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if proc.poll() is not None: raise SystemExit(name + ' exited; inspect ' + str(RUNTIME / (name + '.log')))
        if port_up(port): print('Started', name, proc.pid); return
        time.sleep(.3)
    raise SystemExit(name + ' startup timeout; inspect project runtime logs')

start('nameserver', 9876, '128m', 'org.apache.rocketmq.namesrv.NamesrvStartup', [])
start('broker', 10911, '256m', 'org.apache.rocketmq.broker.BrokerStartup', ['-c', str(conf)])
for topic in ['rpan-file-cleanup'] + (['rpan-cleanup-integration'] if args.integration_topic else []):
    with (RUNTIME / 'admin.log').open('ab') as log:
        result = subprocess.run(base + ['org.apache.rocketmq.tools.command.MQAdminStartup', 'updateTopic', '-n', '127.0.0.1:9876', '-b', '127.0.0.1:10911', '-t', topic, '-r', '1', '-w', '1'], stdout=log, stderr=log, timeout=20)
    if result.returncode: raise SystemExit('Topic creation failed; inspect runtime/rocketmq/admin.log')
print('Ready: NameServer 127.0.0.1:9876, Broker 127.0.0.1:10911, topic rpan-file-cleanup')
