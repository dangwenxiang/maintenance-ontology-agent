"""Start the Java business service and official DSH host; credentials stay in process environment."""
import argparse
import json
import os
import pathlib
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=int(os.environ.get('PORT', '8087')))
parser.add_argument('--runtime-port', type=int, default=int(os.environ.get('DSH_PORT', '8117')))
parser.add_argument('--build', action='store_true')
args = parser.parse_args()
env = os.environ.copy()
def java_binary():
    return str(pathlib.Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
def java_major(binary):
    try:
        result = subprocess.run([binary, '-version'], capture_output=True, text=True)
        match = re.search(r'version "(\d+)(?:\.(\d+))?', result.stderr + result.stdout)
        return (int(match[2]) if match[1] == '1' else int(match[1])) if match else 0
    except OSError:
        return 0
if java_major(java_binary()) < 21 and sys.platform == 'darwin':
    result = subprocess.run(['/usr/libexec/java_home', '-v', '21+'], capture_output=True, text=True)
    if result.returncode == 0:
        env['JAVA_HOME'] = result.stdout.strip()
java = java_binary()
if java_major(java) < 21:
    raise SystemExit('需要JDK >=21，请安装并设置JAVA_HOME。启动脚本不会修改系统Java配置。')
node, npm, mvn = shutil.which('node'), shutil.which('npm'), shutil.which('mvn')
if not node or not npm or not mvn:
    raise SystemExit('需要 Node.js >=22.19、npm、Maven 和 JDK >=21')
for port in [args.port, args.runtime_port]:
    with socket.socket() as sock:
        try:
            sock.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'端口 {port} 已被占用。停止旧的本项目服务，或使用 --port / --runtime-port。')
jar = root / 'target/maintenance-ontology-agent-0.2.0.jar'
sources = [root / 'pom.xml', *list((root / 'src').rglob('*'))]
if args.build or not jar.exists() or any(p.is_file() and p.stat().st_mtime > jar.stat().st_mtime for p in sources):
    subprocess.run([mvn, '-s', '.mvn/settings.xml', '-gs', '.mvn/settings.xml', 'verify', '-q'], cwd=root, env=env, check=True)
if not (root / 'runtime/node_modules').exists():
    subprocess.run([npm, 'ci', '--ignore-scripts'], cwd=root / 'runtime', env=env, check=True)
env.update({'PORT': str(args.port), 'BIND_ADDRESS': '127.0.0.1', 'DSH_ENABLED': 'true',
            'DSH_RUNTIME_URL': f'http://127.0.0.1:{args.runtime_port}', 'DSH_PORT': str(args.runtime_port),
            'DSH_BACKEND_URL': f'http://127.0.0.1:{args.port}',
            'DSH_RUNTIME_TOKEN': secrets.token_hex(32), 'DSH_TOOL_TOKEN': secrets.token_hex(32)})
processes = []
try:
    processes.append(subprocess.Popen([java, '-jar', str(jar)], cwd=root, env=env))
    processes.append(subprocess.Popen([node, 'src/server.mjs'], cwd=root / 'runtime', env=env))
    for _ in range(150):
        if any(p.poll() is not None for p in processes):
            raise RuntimeError('服务启动失败，请查看上述服务日志')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/api/health', timeout=2) as response:
                health = json.load(response)
            if health.get('runtime', {}).get('status') == 'ok':
                print(f'工作台已就绪：http://127.0.0.1:{args.port}  /  DSH模式：{health["runtime"]["mode"]}', flush=True)
                break
        except OSError:
            pass
        time.sleep(0.2)
    else:
        raise RuntimeError('服务健康检查超时')
    while all(p.poll() is None for p in processes):
        time.sleep(0.5)
except KeyboardInterrupt:
    pass
finally:
    for process in processes:
        if process.poll() is None:
            process.terminate()
    for process in processes:
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
