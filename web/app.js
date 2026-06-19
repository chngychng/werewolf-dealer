let modes = [];
let currentGame = null;
let judgeRefreshTimer = null;

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
    const res = await fetch(path, {
        headers: { 'Content-Type': 'application/json' },
        ...options
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '请求失败');
    return data;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

function teamClass(team) {
    return team === '狼人阵营' ? 'team-wolf' : 'team-good';
}

function actionTypeLabel(type) {
    const map = {
        'choose-idol': '混血儿选择榜样',
        'seer-check': '预言家查验',
        'mirror-check': '魔镜少女查验',
        'guard': '守卫守护',
        'witch-save': '女巫解药',
        'witch-poison': '女巫毒药',
        'witch-skip': '女巫不救不毒',
        'hunter-status': '猎人开枪状态',
        'wolf-kill': '狼人击杀',
        'charm': '狼美人魅惑',
        'imitate': '觉醒隐狼学习',
        'dance': '舞者舞池',
        'mask': '假面面具',
        'status-confirm': '身份/状态确认',
        'none': '确认无操作'
    };
    return map[type] || type;
}

async function loadModes() {
    modes = await api('/api/modes');
    const select = $('modeSelect');
    select.innerHTML = modes.map(m => `<option value="${escapeHtml(m.id)}">${escapeHtml(m.name)}</option>`).join('');
    select.addEventListener('change', updateModeNote);
    updateModeNote();
}

function updateModeNote() {
    const mode = modes.find(m => m.id === $('modeSelect').value);
    $('modeNote').textContent = mode ? mode.note : '';
}

async function startGame() {
    try {
        const modeId = $('modeSelect').value;
        const playerCount = Number($('playerCount').value);
        currentGame = await api('/api/game/start', {
            method: 'POST',
            body: JSON.stringify({ modeId, playerCount })
        });
        renderGame(currentGame);
        alert('已随机发牌。把“玩家专用链接”发给玩家；玩家提交自己的操作后，流程会自动进入下一步。');
    } catch (e) {
        alert(e.message);
    }
}

async function resetGame() {
    if (!confirm('确定重置当前牌局？')) return;
    await api('/api/game/reset', { method: 'POST', body: JSON.stringify({}) });
    currentGame = await api('/api/game');
    renderGame(currentGame);
}

async function refreshGame() {
    currentGame = await api('/api/judge');
    renderGame(currentGame);
}

async function setStep(index) {
    try {
        currentGame = await api('/api/night/step', {
            method: 'POST',
            body: JSON.stringify({ index })
        });
        renderGame(currentGame);
    } catch (e) {
        alert(e.message);
    }
}

function renderGame(game) {
    renderLinks(game.playerLinks || []);
    renderJudge(game);
}

function withCurrentOriginLink(links) {
    const current = location.origin + '/player.html';
    return Array.from(new Set([current, ...(links || [])]));
}

function renderLinks(links) {
    const safeLinks = withCurrentOriginLink(links);
    $('links').innerHTML = safeLinks.map((link, idx) => `
        <div class="link-item">
            <div>
                <div><strong>${idx === 0 ? '当前页面对应玩家链接' : '可尝试链接'}</strong></div>
                <code>${escapeHtml(link)}</code>
            </div>
            <button class="secondary" onclick="copyText('${escapeHtml(link)}')">复制</button>
        </div>
    `).join('');
}

async function copyText(text) {
    const plain = text.replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&#39;', "'");
    try {
        await navigator.clipboard.writeText(plain);
        alert('已复制: ' + plain);
    } catch (_) {
        prompt('复制这个链接:', plain);
    }
}

function renderJudge(game) {
    if (!game.started) {
        $('gameSummary').innerHTML = '<p class="hint">还没有开局。先选择模式和人数，然后点击“随机发牌 / 开局”。</p>';
        $('roleList').innerHTML = '';
        $('nightSteps').innerHTML = '';
        $('nightResult').innerHTML = '<p class="log-empty">暂无结果。</p>';
        $('actionsLog').innerHTML = '<p class="log-empty">暂无操作。</p>';
        return;
    }
    const mode = modes.find(m => m.id === game.modeId);
    $('gameSummary').innerHTML = `
        <p>当前模式：<strong>${escapeHtml(mode ? mode.name : game.modeId)}</strong><span class="badge">${game.playerCount} 人</span><span class="badge">自动推进</span></p>
        <p class="hint">开局时间：${escapeHtml(game.createdAt || '-')}</p>
        <p class="hint">玩家端不会显示本法官面板，也不会显示全部身份表。轮到对应身份时，玩家提交操作后系统会自动进入下一步；无夜间操作身份不会被唤醒。</p>
    `;
    $('roleList').innerHTML = game.players.map(p => `
        <div class="role-item">
            <div><span class="role-num">${p.number}号</span></div>
            <div class="${teamClass(p.team)}">${escapeHtml(p.role)}</div>
            <div class="hint">${escapeHtml(p.team)}</div>
        </div>
    `).join('');
    renderNightSteps(game);
    renderNightResult(game.nightResult);
    renderActions(game.actions || []);
}

function renderNightSteps(game) {
    const steps = game.nightSteps || [];
    const currentIndex = game.currentStepIndex || 0;
    const current = game.currentStep;
    const currentBox = current ? `
        <div class="current-step-box">
            <div class="hint">当前步骤 ${currentIndex + 1}/${steps.length}</div>
            <h3>${escapeHtml(current.title)}</h3>
            <p>${escapeHtml(current.instruction)}</p>
            <p class="hint">轮到身份：${current.roleNames && current.roleNames.length ? current.roleNames.map(escapeHtml).join('、') : '流程结束 / 无玩家操作'}</p>
            <div class="actions">
                <button class="secondary" onclick="setStep(${Math.max(0, currentIndex - 1)})" ${currentIndex <= 0 ? 'disabled' : ''}>纠错：上一步</button>
                <button class="secondary" onclick="setStep(${Math.min(steps.length - 1, currentIndex + 1)})" ${currentIndex >= steps.length - 1 ? 'disabled' : ''}>纠错：下一步</button>
                <button class="secondary" onclick="refreshGame()">刷新操作记录</button>
            </div>
            <p class="hint">正常流程不需要人盯着法官端。每个步骤由对应玩家在手机端提交；狼人轮次任意一名可见狼人提交刀口即可自动进入下一步；白痴、骑士等无首夜操作身份不会被唤醒。这里的按钮只用于误操作后纠错。</p>
        </div>
    ` : '';

    const list = steps.map((s, idx) => {
        const cls = idx === currentIndex ? 'step-current' : (idx < currentIndex ? 'step-done' : 'step-future');
        return `<li class="step-item ${cls}">
            <div><strong>${escapeHtml(s.title || s)}</strong>${idx === currentIndex ? '<span class="badge">当前</span>' : ''}</div>
            ${s.instruction ? `<div class="hint">${escapeHtml(s.instruction)}</div>` : ''}
        </li>`;
    }).join('');

    $('nightSteps').innerHTML = currentBox + `<ol class="step-list">${list}</ol>`;
}


function renderNightResult(result) {
    if (!result) {
        $('nightResult').innerHTML = '<p class="log-empty">暂无结果。</p>';
        return;
    }
    const boxClass = result.finished ? 'current-step-box' : 'result';
    $('nightResult').innerHTML = `
        <div class="${boxClass}">
            <h3>${escapeHtml(result.finished ? '第一晚最终结果' : '第一晚临时结果')}</h3>
            <p><strong>${escapeHtml(result.summary || '-')}</strong></p>
        </div>
    `;
}

function renderActions(actions) {
    if (!actions.length) {
        $('actionsLog').innerHTML = '<p class="log-empty">暂无操作。</p>';
        return;
    }
    $('actionsLog').innerHTML = actions.slice().reverse().map(a => `
        <div class="log-item">
            <div><strong>${a.actorNumber}号 ${escapeHtml(a.actorRole)}</strong>：${escapeHtml(actionTypeLabel(a.actionType))}</div>
            <div class="hint">目标：${a.targets && a.targets.length ? a.targets.join(', ') + ' 号' : '无'} ${a.extra ? '｜备注：' + escapeHtml(a.extra) : ''}</div>
            ${a.result ? `<div class="result">${escapeHtml(a.result)}</div>` : ''}
        </div>
    `).join('');
}

async function init() {
    await loadModes();
    currentGame = await api('/api/judge');
    renderGame(currentGame);
    $('startBtn').addEventListener('click', startGame);
    $('resetBtn').addEventListener('click', resetGame);
    $('refreshBtn').addEventListener('click', refreshGame);
    judgeRefreshTimer = setInterval(async () => {
        if (document.hidden) return;
        try { await refreshGame(); } catch (_) {}
    }, 3000);
}

init().catch(e => alert(e.message));
