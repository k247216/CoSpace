#!/usr/bin/env python3
"""Bounded API measurements using an isolated account and workspace; never seed SQL directly."""
import argparse
import collections
import concurrent.futures
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import secrets
import threading
import time
from urllib.parse import urlsplit
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] if ordered else None


class Api:
    def __init__(self, base, token=None):
        self.base = base.rstrip('/')
        self.token = token

    def request(self, path, method='GET', data=None, raw=False, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if self.token:
            headers['Authorization'] = self.token
        body = data if isinstance(data, bytes) or data is None else json.dumps(data).encode()
        with urlopen(Request(self.base + path, body, headers, method=method), timeout=30) as response:
            payload = response.read()
        if raw:
            return payload
        result = json.loads(payload)
        if result['status'] != 0:
            raise RuntimeError(f'{method} {path}: business status {result["status"]}')
        return result.get('data')


def save_fixture(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(data, stream)


def prepare(args):
    if args.fixture.exists():
        raise RuntimeError('Fixture exists; reuse it for measure/verify or clean it first')
    api = Api(args.base)
    account = {'username': 'bench' + secrets.token_hex(4), 'password': secrets.token_hex(8)}
    api.request('/user/register', 'POST', {**account, 'question': 'benchmark fixture?', 'answer': 'yes'})
    api.token = api.request('/user/login', 'POST', account)
    space = api.request('/workspaces', 'POST', {'name': 'Isolated benchmark fixture'})
    fixture = {'base': args.base, 'account': account, 'token': api.token, 'workspace': space['id'], 'items': args.items}
    save_fixture(args.fixture, fixture)
    for index in range(args.items - 1):
        api.request('/workspaces/' + space['id'] + '/files/folders', 'POST',
                    {'parentId': space['id'], 'name': f'bench-directory-{index:04d}'})
    payload = b'CoSpace benchmark fixture\n' * 41
    request = {'requestId': str(uuid.uuid4()), 'name': 'bench-payload.txt', 'size': len(payload),
               'md5': hashlib.md5(payload).hexdigest(), 'parentId': space['id'], 'directUpload': False}
    task = api.request('/workspaces/' + space['id'] + '/files/uploads', 'POST', request)
    fixture.update(upload=task['uploadId'], request=request, bytes=len(payload))
    save_fixture(args.fixture, fixture)
    boundary = 'cospace-' + secrets.token_hex(12)
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="chunk.part"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + payload + f'\r\n--{boundary}--\r\n'.encode()
    api.request('/file/upload/tasks/' + task['uploadId'] + '/chunks/0', 'POST', body,
                content_type='multipart/form-data; boundary=' + boundary)
    done = api.request('/file/upload/tasks/' + task['uploadId'] + '/complete', 'POST')
    fixture['file'] = done['userFileId']
    save_fixture(args.fixture, fixture)
    received = api.request('/workspaces/' + space['id'] + '/files/' + fixture['file'] + '/download', raw=True)
    if received != payload:
        raise RuntimeError('Fixture download differs from uploaded bytes')
    return {'prepared_items': args.items, 'folders': args.items - 1, 'files': 1, 'payload_bytes': len(payload)}


def load(args):
    fixture = json.loads(args.fixture.read_text())
    if fixture['base'] != args.base:
        raise RuntimeError('Base URL differs from the private fixture; refusing to send its token')
    return fixture, Api(args.base, fixture['token'])


def measure(args):
    fixture, api = load(args)
    root = '/workspaces/' + fixture['workspace'] + '/files'
    paths = {'tree': root + '?parentId=' + fixture['workspace'], 'search': root + '/search?keyword=bench',
             'workspaces': '/workspaces'}
    path = paths[args.workload]
    expected = {'tree': fixture['items'], 'search': min(fixture['items'], 100), 'workspaces': 1}[args.workload]
    for _ in range(5):
        if len(api.request(path)) != expected:
            raise RuntimeError('Unexpected fixture cardinality')
    address = urlsplit(args.base)
    local = threading.local()
    runs = []
    for concurrency in args.concurrency:
        connections = []
        guard = threading.Lock()

        def request(_):
            if not hasattr(local, 'connection'):
                factory = http.client.HTTPSConnection if address.scheme == 'https' else http.client.HTTPConnection
                local.connection = factory(address.hostname, address.port, timeout=10)
                with guard:
                    connections.append(local.connection)
            start = time.perf_counter_ns()
            try:
                local.connection.request('GET', address.path.rstrip('/') + path,
                                         headers={'Authorization': api.token})
                response = local.connection.getresponse()
                content = response.read()
                result = json.loads(content)
                success = response.status == 200 and result.get('status') == 0 and len(result.get('data', [])) == expected
                return (time.perf_counter_ns() - start) / 1e6, success, len(content)
            except Exception:
                return (time.perf_counter_ns() - start) / 1e6, False, 0

        start = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            samples = list(executor.map(request, range(args.requests)))
        duration = time.perf_counter() - start
        for connection in connections:
            connection.close()
        latencies = [sample[0] for sample in samples if sample[1]]
        errors = len(samples) - len(latencies)
        runs.append({'concurrency': concurrency, 'requests': len(samples), 'errors': errors,
                     'duration_seconds': round(duration, 4), 'successful_rps': round(len(latencies) / duration, 2),
                     'p50_ms': percentile(latencies, .5), 'p95_ms': percentile(latencies, .95),
                     'p99_ms': percentile(latencies, .99), 'response_bytes': sum(sample[2] for sample in samples)})
        if errors:
            break
    return {'mode': 'closed-loop, keep-alive; client and server placement recorded separately',
            'workload': args.workload, 'dataset_items': fixture['items'], 'warmup_requests': 5,
            'percentile_method': 'nearest rank', 'runs': runs}


def verify(args):
    fixture, api = load(args)
    root = '/workspaces/' + fixture['workspace']
    def initialize(_):
        try:
            return api.request(root + '/files/uploads', 'POST', fixture['request']), 200
        except HTTPError as error:
            if error.code != 429:
                raise
            return None, 429
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
        outcomes = list(executor.map(initialize, range(16)))
        completed = list(executor.map(lambda _: api.request('/file/upload/tasks/' + fixture['upload'] + '/complete', 'POST'), range(16)))
    initialized = [result for result, status in outcomes if status == 200]
    assert initialized and {task['uploadId'] for task in initialized} == {fixture['upload']}
    # Initialization admission control also applies to replay requests. Count 429s rather than hiding them.
    for result, status in outcomes:
        if status == 429:
            time.sleep(1.1)
            retry = api.request(root + '/files/uploads', 'POST', fixture['request'])
            assert retry['uploadId'] == fixture['upload']
    assert {task['userFileId'] for task in completed} == {fixture['file']}
    space = next(item for item in api.request('/workspaces') if item['id'] == fixture['workspace'])
    assert space['usedBytes'] == fixture['bytes'] and space['reservedBytes'] == 0
    files = api.request(root + '/files?parentId=' + fixture['workspace'])
    assert sum(not item['folder'] for item in files) == 1
    return {'completed_task_initialize_replays': 16, 'initialization_http_statuses': dict(collections.Counter(status for _, status in outcomes)), 'completed_task_complete_replays': 16,
            'logical_files': 1, 'used_bytes': space['usedBytes'], 'reserved_bytes': 0, 'passed': True}


def cleanup(args):
    fixture, api = load(args)
    root = '/workspaces/' + fixture['workspace'] + '/files'
    if fixture.get('upload'):
        task = api.request('/file/upload/tasks/' + fixture['upload'])
        if task['state'] not in ('COMPLETED', 'CANCELLED', 'EXPIRED'):
            api.request('/file/upload/tasks/' + fixture['upload'] + '/cancel', 'POST')
    items = api.request(root + '?parentId=' + fixture['workspace'])
    for item in items:
        api.request(root + '/' + item['id'], 'DELETE')
        api.request(root + '/' + item['id'] + '?purge=true', 'DELETE')
    space = next(item for item in api.request('/workspaces') if item['id'] == fixture['workspace'])
    assert space['usedBytes'] == 0 and space['reservedBytes'] == 0
    args.fixture.unlink()
    return {'cleaned_items': len(items), 'used_bytes': 0, 'reserved_bytes': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'measure', 'verify', 'cleanup'])
    parser.add_argument('--base', default='http://127.0.0.1:18080/api')
    parser.add_argument('--fixture', type=Path, default=Path('runtime/benchmark-fixture.json'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--items', type=int, default=200)
    parser.add_argument('--requests', type=int, default=300)
    parser.add_argument('--concurrency', type=int, nargs='+', default=[1, 4, 8, 16])
    parser.add_argument('--workload', choices=['tree', 'search', 'workspaces'], default='tree')
    args = parser.parse_args()
    if not 1 <= args.items <= 1000 or not 1 <= args.requests <= 5000 or any(not 1 <= c <= 32 for c in args.concurrency):
        parser.error('Bounded fixture: 1..1000 items, 1..5000 requests, concurrency 1..32')
    result = globals()[args.action](args)
    result['recorded_at_utc'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if any(run['errors'] for run in result.get('runs', [])):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
