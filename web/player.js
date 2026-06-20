let currentGame = null;
let selectedPlayer = null;
let autoRefreshTimer = null;

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

async function loadGameStatus() {
    currentGame = await api('/api/game');
    if (!currentGame.started) {
        $('gameStatus').textContent = '法官还没有开局。请等法官发牌后再进入。';
        $('loadPlayerBtn').disabled = true;
        return;
    }
    $('loadPlayerBtn').disabled = false;
    $('myNumber').max = currentGame.playerCount;
    $('gameStatus').textContent = `本局已开始：${currentGame.playerCount} 人。请输入 1-${currentGame.playerCount} 之间的自己的号码。`;
}

function getNumberFromUrl() {
    const params = new URLSearchParams(location.search);
    const value = Number(params.get('n') || params.get('number') || 0);
    return Number.isInteger(value) && value > 0 ? value : 0;
}

async function loadPlayer() {
    try {
        await loadGameStatus();
        if (!currentGame.started) return;
        const n = Number($('myNumber').value);
        if (!Number.isInteger(n) || n < 1 || n > currentGame.playerCount) {
            alert(`请输入 1-${currentGame.playerCount} 之间的号码。`);
            return;
        }
        selectedPlayer = await api('/api/player/' + n);
        history.replaceState(null, '', '/player.html?n=' + n);
        renderPlayer(selectedPlayer);
        startAutoRefresh();
    } catch (e) {
        alert(e.message);
    }
}

function renderPlayer(player) {
    const wolfInfo = '';
    const nightInfo = renderNightInfo(player);

    $('enterCard').classList.add('hidden');
    $('mineCard').classList.remove('hidden');
    $('playerView').innerHTML = `
        <h2>${player.number}号玩家</h2>
        <div class="role-secret own-role">
            <p class="hint">你的身份底牌</p>
            <div class="role-name ${teamClass(player.team)}">${escapeHtml(player.role)}</div>
            <div>${escapeHtml(player.team)}</div>
            <p>${escapeHtml(player.description)}</p>
            ${wolfInfo}
            ${(player.notes || []).map(n => `<p class="hint">${escapeHtml(n)}</p>`).join('')}
        </div>
        ${nightInfo}
        <div class="action-box">
            <h3>第一晚操作</h3>
            <div id="actionChoices"></div>
            <div id="actionResult"></div>
        </div>
    `;
    renderActionChoices(player);
}

function renderNightInfo(player) {
    const info = player.nightInfo || {};
    if (player.role === '女巫') {
        return `<div class="current-step-box compact private-info">
            <h3>法官信息</h3>
            <p><strong>${escapeHtml(info.wolfKillText || '今晚暂未记录狼刀目标。')}</strong></p>
        </div>`;
    }
    if (player.role === '猎人') {
        return `<div class="current-step-box compact private-info">
            <h3>开枪状态</h3>
            <p><strong>${escapeHtml(info.hunterStatusText || '当前开枪状态：可以开枪。')}</strong></p>
        </div>`;
    }
    return '';
}

function renderActionChoices(player) {
    if (!player.myTurn) {
        $('actionChoices').innerHTML = '<p class="log-empty">暂时没有需要提交的操作，请闭眼等待。</p>';
        return;
    }
    if (!player.actionChoices || !player.actionChoices.length) {
        $('actionChoices').innerHTML = '<p class="log-empty">当前没有需要提交的操作。</p>';
        return;
    }
    $('actionChoices').innerHTML = player.actionChoices.map(choice => `
        <div class="log-item action-choice" data-action-type="${escapeHtml(choice.type)}">
            <div><strong>${escapeHtml(choice.label)}</strong>${choice.optional ? '<span class="badge">可空过</span>' : ''}</div>
            ${renderTargets(choice.targetCount)}
            <label>备注，可不填
                <input id="extra-${escapeHtml(choice.type)}" placeholder="可不填" />
            </label>
            <div class="actions">
                <button onclick="submitAction('${escapeHtml(choice.type)}', ${choice.targetCount})">提交确认</button>
                ${choice.optional ? `<button class="secondary" onclick="submitSkip('${escapeHtml(choice.type)}')">空过/无操作</button>` : ''}
            </div>
        </div>
    `).join('');
}

function renderTargets(count) {
    if (count <= 0) return '<p class="hint">此操作不需要选择目标。</p>';
    const total = currentGame && currentGame.playerCount ? currentGame.playerCount : 12;
    let buttons = '';
    for (let i = 1; i <= total; i++) {
        buttons += `<button type="button" data-target="${i}" onclick="toggleTarget(this, ${count})">${i}</button>`;
    }
    return `<p class="hint">需要选择 ${count} 个目标。</p><div class="target-row">${buttons}</div>`;
}

function toggleTarget(button, maxCount) {
    const container = button.parentElement;
    const selected = Array.from(container.querySelectorAll('button.selected'));
    if (button.classList.contains('selected')) {
        button.classList.remove('selected');
        return;
    }
    if (selected.length >= maxCount) {
        selected[0].classList.remove('selected');
    }
    button.classList.add('selected');
}

async function submitAction(type, targetCount) {
    if (!selectedPlayer) return;
    const currentCard = document.querySelector(`.action-choice[data-action-type="${type}"]`);
    const selected = currentCard
        ? Array.from(currentCard.querySelectorAll('button.selected')).map(b => Number(b.dataset.target))
        : [];
    if (selected.length !== targetCount) {
        alert(`需要选择 ${targetCount} 个目标，目前选择了 ${selected.length} 个。`);
        return;
    }
    await doSubmit(type, selected);
}

async function submitSkip(type) {
    if (!selectedPlayer) return;
    await doSubmit(type, []);
}

async function doSubmit(type, targets) {
    try {
        const extraInput = $('extra-' + type);
        const extra = extraInput ? extraInput.value : '';
        const buttons = Array.from(document.querySelectorAll('button'));
        buttons.forEach(b => b.disabled = true);
        const res = await api('/api/action', {
            method: 'POST',
            body: JSON.stringify({
                actorNumber: selectedPlayer.number,
                actionType: type,
                targets,
                extra
            })
        });
        const resultHtml = `<div class="result"><strong>${escapeHtml(actionTypeLabel(type))}</strong><br>${escapeHtml(res.result || '已提交。')}<br><span class="hint">已提交并自动进入下一步。请闭眼等待。</span></div>`;
        const n = selectedPlayer.number;
        await loadGameStatus();
        selectedPlayer = await api('/api/player/' + n);
        renderPlayer(selectedPlayer);
        $('actionResult').innerHTML = resultHtml;
    } catch (e) {
        Array.from(document.querySelectorAll('button')).forEach(b => b.disabled = false);
        alert(e.message);
    }
}


function startAutoRefresh() {
    if (autoRefreshTimer) return;
    autoRefreshTimer = setInterval(async () => {
        if (!selectedPlayer || document.hidden) return;
        try {
            const next = await api('/api/player/' + selectedPlayer.number);
            if (next.currentStepIndex !== selectedPlayer.currentStepIndex || next.myTurn !== selectedPlayer.myTurn) {
                selectedPlayer = next;
                await loadGameStatus();
                renderPlayer(selectedPlayer);
            }
        } catch (_) {}
    }, 3000);
}

function stopAutoRefresh() {
    if (autoRefreshTimer) {
        clearInterval(autoRefreshTimer);
        autoRefreshTimer = null;
    }
}

function backToEnter() {
    selectedPlayer = null;
    stopAutoRefresh();
    $('mineCard').classList.add('hidden');
    $('enterCard').classList.remove('hidden');
    history.replaceState(null, '', '/player.html');
}

async function refreshMine() {
    if (selectedPlayer) {
        $('myNumber').value = selectedPlayer.number;
        const n = selectedPlayer.number;
        await loadGameStatus();
        selectedPlayer = await api('/api/player/' + n);
        renderPlayer(selectedPlayer);
        startAutoRefresh();
    } else {
        await loadGameStatus();
    }
}

async function init() {
    await loadGameStatus();
    const n = getNumberFromUrl();
    if (n) {
        $('myNumber').value = n;
        await loadPlayer();
    }
    $('loadPlayerBtn').addEventListener('click', loadPlayer);
    $('backBtn').addEventListener('click', backToEnter);
    $('refreshBtn').addEventListener('click', refreshMine);
    $('myNumber').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') loadPlayer();
    });
}

init().catch(e => alert(e.message));
