import { spawnSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

function required(name, pattern) {
    const value = process.env[name] ?? '';
    if (value !== value.trim() || !pattern.test(value)) {
        throw new Error(`환경변수 형식 오류: ${name}`);
    }
    return value;
}

function aws(args, allowMissing = false) {
    const result = spawnSync('aws', [
        ...args, '--region', 'ap-northeast-2', '--output', 'json',
        '--no-cli-pager', '--cli-connect-timeout', '10', '--cli-read-timeout', '30',
    ], { encoding: 'utf8', timeout: 60000, maxBuffer: 1024 * 1024 });
    if (result.error) throw result.error;
    if (result.status !== 0) {
        if (allowMissing && result.stderr.includes('InvocationDoesNotExist')) return null;
        throw new Error(`AWS 명령 실패: ${result.stderr.trim()}`);
    }
    return JSON.parse(result.stdout);
}

async function main() {
    required('AWS_REGION', /^ap-northeast-2$/);
    const instance = required('EC2_INSTANCE_ID', /^i-06a6741c6bd94dc87$/);
    const image = required('DADOT_IMAGE', /^ghcr\.io\/team-dadot\/dadot-server@sha256:[a-f0-9]{64}$/);
    const runId = required('GITHUB_RUN_ID', /^[0-9]+$/);
    const attempt = required('GITHUB_RUN_ATTEMPT', /^[0-9]+$/);
    const sha = required('RELEASE_SHA', /^[a-f0-9]{40}$/);
    const release = `/home/ubuntu/dadot-deploy/releases/${runId}-${attempt}`;

    const archive = spawnSync('tar', [
        '-czf', '-', 'compose.bootstrap.yaml', 'deploy/configure.sh',
        'deploy/env.required', 'deploy/start.sh', 'deploy/release.sh',
    ], { maxBuffer: 1024 * 1024 });
    if (archive.error) throw archive.error;
    if (archive.status !== 0) throw new Error('배포 설정 압축에 실패했습니다.');
    const bundle = archive.stdout.toString('base64');
    const commands = [
        'set -eu',
        'umask 077',
        `install -d -o root -g root -m 0700 '${release}'`,
        `printf '%s' '${bundle}' | base64 --decode | tar -xzf - -C '${release}'`,
        `exec bash '${release}/deploy/release.sh' '${image}'`,
    ];
    const request = {
        DocumentName: 'AWS-RunShellScript',
        InstanceIds: [instance],
        TimeoutSeconds: 60,
        Comment: `dadot ${sha} run ${runId}-${attempt}`,
        Parameters: { commands, executionTimeout: ['900'] },
    };
    if (Buffer.byteLength(JSON.stringify(request)) > 28000) {
        throw new Error('SSM 배포 설정이 너무 큽니다. 전송 방식을 확인하세요.');
    }

    const sent = aws(['ssm', 'send-command', '--cli-input-json', JSON.stringify(request)]);
    const commandId = sent.Command?.CommandId;
    if (!/^[a-f0-9-]{36}$/.test(commandId ?? '')) throw new Error('SSM 명령 ID를 확인할 수 없습니다.');
    console.log(`SSM CommandId: ${commandId}`);
    if (process.env.GITHUB_STEP_SUMMARY) {
        appendFileSync(process.env.GITHUB_STEP_SUMMARY,
            `커밋: ${sha}\n\n이미지: ${image}\n\nSSM 명령: ${commandId}\n`);
    }

    const deadline = Date.now() + 1200000;
    while (Date.now() < deadline) {
        const result = aws([
            'ssm', 'get-command-invocation', '--command-id', commandId,
            '--instance-id', instance,
        ], true);
        if (result && !['Pending', 'InProgress', 'Delayed', 'Cancelling'].includes(result.Status)) {
            if (result.StandardOutputContent) console.log(result.StandardOutputContent);
            if (result.StandardErrorContent) console.error(result.StandardErrorContent);
            if (result.Status === 'Success' && result.ResponseCode === 0) {
                console.log('EC2 배포와 상태 확인이 완료됐습니다.');
                return;
            }
            throw new Error(`EC2 배포 실패: ${result.Status}, exit=${result.ResponseCode}`);
        }
        await sleep(10000);
    }
    throw new Error(`상태 조회 시간 초과. SSM에서 명령 ${commandId}의 실제 상태를 확인하세요.`);
}

main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
});