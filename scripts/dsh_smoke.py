"""Exercise the packaged Java service, official DSH host, feedback and session restart recovery."""
import json
import os
import pathlib
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
jar = root / 'target/maintenance-ontology-agent-0.2.0.jar'
def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]
port, runtime_port = free_port(), free_port()
admin = secrets.token_hex(24)
env = {**os.environ, 'PORT': str(port), 'BIND_ADDRESS': '127.0.0.1', 'ADMIN_TOKEN': admin,
       'DSH_PORT': str(runtime_port), 'DSH_RUNTIME_URL': f'http://127.0.0.1:{runtime_port}',
       'DSH_BACKEND_URL': f'http://127.0.0.1:{port}', 'DSH_ENABLED': 'true',
       'DSH_RUNTIME_TOKEN': secrets.token_hex(32), 'DSH_TOOL_TOKEN': secrets.token_hex(32),
       'MODEL_BASE_URL': '', 'MODEL_NAME': '', 'MODEL_API_KEY': ''}
def request(path, body=None, review=False):
    headers = {'Content-Type': 'application/json'}
    if review:
        headers['X-Admin-Token'] = admin
    req = urllib.request.Request(f'http://127.0.0.1:{port}/api{path}',
        data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)
def wait(run_id):
    for _ in range(150):
        value = request('/runs/' + run_id)
        if value['status'] not in ['running', 'cancelling']:
            return value
        time.sleep(0.05)
    raise AssertionError('DSH run timed out')
def stop(process):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()

with tempfile.TemporaryDirectory(prefix='maintenance-dsh-smoke-') as work:
    env['DB_URL'] = 'jdbc:h2:file:' + str(pathlib.Path(work) / 'business') + ';DB_CLOSE_ON_EXIT=FALSE'
    env['DSH_DATA_DIR'] = str(pathlib.Path(work) / 'runtime')
    processes = []
    with open(pathlib.Path(work) / 'services.log', 'w+') as log:
        def start_runtime():
            process = subprocess.Popen(['node', 'src/server.mjs'], cwd=root / 'runtime', env=env, stdout=log, stderr=log)
            processes.append(process)
            return process
        def ready():
            for _ in range(100):
                if any(p.poll() is not None for p in processes):
                    raise AssertionError('A service exited')
                try:
                    health = request('/health')
                    if health.get('runtime', {}).get('status') == 'ok':
                        return
                except OSError:
                    pass
                time.sleep(0.1)
            raise AssertionError('Stack startup timed out')
        try:
            backend = subprocess.Popen([java, '-jar', str(jar)], cwd=work, env=env, stdout=log, stderr=log)
            processes.append(backend)
            runtime = start_runtime()
            ready()
            assert request('/health')['runtime']['kernel'] == 'dsh'
            run = request('/runs', {'question': '同型号扭矩不合格的历史原因', 'deviceId': 'D-DEMO-01'})
            completed = wait(run['id'])
            assert completed['status'] == 'completed', completed
            assert completed['response']['kernel'] == 'dsh'
            assert [s['tool'] for s in completed['response']['trace']] == ['get_device', 'search_cases', 'search_documents']
            assert {c['id'] for c in completed['response']['cases']} == {'C-DEMO-001', 'C-DEMO-002'}
            case_id = request('/feedback', {'traceId': run['id'], 'cause': 'DSH合成反馈原因', 'action': '合成处理', 'result': '合成结果'})['caseId']
            assert not any(c['id'] == case_id for c in request('/cases'))
            request('/review/' + case_id, {'approve': True, 'reviewer': '烟测审核者'}, True)
            assert any(c['id'] == case_id for c in request('/cases'))
            stop(runtime)
            processes.remove(runtime)
            runtime = start_runtime()
            ready()
            resumed = request('/runs', {'question': '继续核对历史原因', 'deviceId': 'D-DEMO-01', 'sessionId': run['sessionId']})
            done = wait(resumed['id'])
            assert done['status'] == 'completed', done
            assert done['sessionId'] == run['sessionId']
            assert any(c['id'] == case_id for c in done['response']['cases'])
            assert any(e['type'] == 'tool/result' for e in done['events'])
            assert request('/traces/' + run['id'])['id'] == run['id']
            cancel_run = request('/runs', {'question': '继续查证', 'deviceId': 'D-DEMO-01', 'sessionId': run['sessionId']})
            request('/runs/' + cancel_run['id'] + '/cancel', {})
            assert wait(cancel_run['id'])['status'] == 'cancelled'
            print('PASS: Java + official DSH, native tools, feedback approval, restart resume, cancellation')
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    stop(process)
