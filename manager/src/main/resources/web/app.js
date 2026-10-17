'use strict';

// Ключ панели: сервер выдаёт его вместе со страницей и требует в каждом запросе.
// Чужой сайт страницу прочитать не может — значит, и ключа не знает
const TOKEN = document.querySelector('meta[name="virus-token"]').content;

const $ = (id) => document.getElementById(id);

const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
};

async function api(path, body) {
  const response = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-Virus-Token': TOKEN, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(json.error || 'ошибка ' + response.status);
  return json;
}

let toastTimer;
function toast(text, kind = 'good') {
  const box = $('toast');
  box.textContent = text;
  box.className = 'toast ' + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.add('hidden'), 3500);
}

function remember(key, value) {
  try { localStorage.setItem('virus.' + key, value); } catch (e) { /* без памяти тоже работает */ }
}
function recall(key) {
  try { return localStorage.getItem('virus.' + key); } catch (e) { return null; }
}

const STATE_NAMES = {
  RUNNING: 'работает',
  STOPPED: 'остановлен',
  STOPPING: 'останавливается',
  WAITING: 'перезапуск',
  FAILED: 'упал',
};

const state = {
  processes: [],
  view: recall('view') || '',
  logs: {},       // id → { seq, lines[] }
  filter: '',
  level: 'all',
  stick: true,
};

// ---------------------------------------------------------------- состояние

async function refresh() {
  let data;
  try {
    data = await api('state');
  } catch (e) {
    banners([{ kind: 'warn', text: 'Нет связи с V.I.R.U.S.: ' + e.message + '. Если ты его выключил — запусти снова.' }]);
    return;
  }

  state.processes = data.processes;

  const list = [];
  if (data.oldTask) {
    list.push({
      kind: 'warn',
      text: 'Стоит старая задача автозапуска VoiceBridgeBot — она запускает бота мимо V.I.R.U.S., и работали бы две копии. Убери её: в PowerShell от администратора',
      code: 'Unregister-ScheduledTask -TaskName VoiceBridgeBot -Confirm:$false',
    });
  }
  banners(list);

  const auto = $('autostart');
  $('autostart-wrap').classList.toggle('hidden', !data.autostart.supported);
  if (document.activeElement !== auto) auto.checked = data.autostart.enabled;

  renderList();

  if (state.view.startsWith('proc:')) {
    const process = current();
    if (process) renderProcess(process); else show('');
  } else if (state.view === '' && state.processes.length) {
    show('proc:' + state.processes[0].id);
  }
}

function banners(list) {
  const box = $('banners');
  box.replaceChildren();
  for (const item of list) {
    const banner = el('div', 'banner ' + item.kind);
    banner.append(el('span', '', item.text));
    if (item.code) banner.append(el('code', '', item.code));
    box.append(banner);
  }
}

function current() {
  const id = state.view.slice(5);
  return state.processes.find((p) => p.id === id);
}

// ---------------------------------------------------------------- список слева

function uptime(since) {
  if (!since) return '';
  let s = Math.floor((Date.now() - since) / 1000);
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  if (d) return d + ' д ' + h + ' ч';
  if (h) return h + ' ч ' + m + ' мин';
  if (m) return m + ' мин';
  return s + ' с';
}

function subline(p) {
  if (p.state === 'RUNNING') return 'работает ' + uptime(p.startedAt);
  if (p.note) return p.note;
  return STATE_NAMES[p.state] || p.state;
}

function renderList() {
  const list = $('process-list');
  list.replaceChildren();

  for (const p of state.processes) {
    const item = el('li');
    const button = el('button', 'proc' + (state.view === 'proc:' + p.id ? ' active' : ''));
    button.title = STATE_NAMES[p.state] || p.state;
    button.append(el('span', 'dot ' + p.state), el('span', 'proc-name', p.name), el('span', 'proc-sub', subline(p)));
    button.addEventListener('click', () => show('proc:' + p.id));
    item.append(button);
    list.append(item);
  }

  $('open-config').classList.toggle('active', state.view === 'config');
}

// ---------------------------------------------------------------- виды

function show(view) {
  if (state.view === 'config' && view !== 'config' && configDirty() && !confirm('Изменения в конфиге не сохранены. Уйти без сохранения?')) return;

  state.view = view;
  remember('view', view);

  $('process-view').classList.toggle('hidden', !view.startsWith('proc:'));
  $('config-view').classList.toggle('hidden', view !== 'config');
  $('empty-view').classList.toggle('hidden', view !== '');

  renderList();

  if (view.startsWith('proc:')) {
    state.stick = true;
    const process = current();
    if (process) {
      renderProcess(process);
      renderConsole(true);
      pollLog();
    }
  } else if (view === 'config') {
    loadConfig();
  }
}

// ---------------------------------------------------------------- процесс

function renderProcess(p) {
  const name = $('p-name');
  name.replaceChildren(document.createTextNode(p.name), el('span', 'badge ' + p.state, STATE_NAMES[p.state] || p.state));

  const meta = [];
  if (p.pid) meta.push('pid ' + p.pid);
  if (p.state === 'RUNNING') meta.push('работает ' + uptime(p.startedAt));
  if (p.restarts) meta.push('перезапусков: ' + p.restarts);
  if (p.exitCode !== null && p.state !== 'RUNNING') meta.push('код выхода ' + p.exitCode);
  meta.push(p.commandLine);
  $('p-meta').textContent = meta.join('  ·  ');

  const note = $('p-note');
  note.textContent = p.note;
  note.classList.toggle('hidden', !p.note || p.state === 'RUNNING');

  const running = p.state === 'RUNNING';
  $('p-start').disabled = running || p.state === 'STOPPING';
  $('p-stop').disabled = !(running || p.state === 'WAITING');
  $('p-restart').disabled = !running;
}

async function act(action) {
  const p = current();
  if (!p) return;
  try {
    await api('processes/' + p.id + '/' + action, {});
    await refresh();
  } catch (e) {
    toast(e.message, 'bad');
  }
}

// ---------------------------------------------------------------- консоль

// Строка логгера ботов: «12:34:56 [поток] WARN Класс - текст»
const LOG_LINE = /^(\d\d:\d\d:\d\d)\s+\[([^\]]+)\]\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+(.*)$/;

function classify(text) {
  if (text.includes('[V.I.R.U.S.]')) return 'sys';
  const match = LOG_LINE.exec(text);
  if (match) {
    if (match[3] === 'ERROR') return 'error';
    if (match[3] === 'WARN') return 'warn';
    if (match[4].includes('Пародия сказала:')) return 'say';
    return '';
  }
  if (/^\s*(at |Caused by:|Exception in thread|[\w.]+(Exception|Error)\b)/.test(text)) return 'error';
  return '';
}

function lineNode(line) {
  const kind = classify(line.text);
  const node = el('span', 'ln ' + kind);
  const match = LOG_LINE.exec(line.text);

  if (match) {
    node.append(el('span', 't', match[1] + ' '), el('span', 'th', '[' + match[2] + '] '),
      document.createTextNode(match[3] + ' ' + match[4]));
  } else {
    node.textContent = line.text;
  }

  node.append('\n');
  return node;
}

function visible(line) {
  const kind = classify(line.text);
  if (state.level === 'error' && kind !== 'error') return false;
  if (state.level === 'warn' && kind !== 'warn' && kind !== 'error') return false;
  return !state.filter || line.text.toLowerCase().includes(state.filter);
}

function renderConsole(full, fresh = []) {
  const box = $('console');
  const p = current();
  if (!p) return;
  const log = state.logs[p.id] || { seq: 0, lines: [] };

  if (full) {
    box.replaceChildren();
    const shown = log.lines.filter(visible);
    if (!shown.length) {
      box.append(el('span', 'empty-console', log.lines.length ? 'Ничего не подходит под фильтр.' : 'Консоль пуста. Запусти процесс — его вывод появится здесь.'));
    }
    const fragment = document.createDocumentFragment();
    for (const line of shown) fragment.append(lineNode(line));
    box.append(fragment);
  } else {
    const shown = fresh.filter(visible);
    if (shown.length) {
      box.querySelector('.empty-console')?.remove();
      const fragment = document.createDocumentFragment();
      for (const line of shown) fragment.append(lineNode(line));
      box.append(fragment);

      // Не держим в странице больше, чем в памяти сервера
      while (box.childNodes.length > 3000) box.firstChild.remove();
    }
  }

  if (state.stick) box.scrollTop = box.scrollHeight;
}

let polling = false;
async function pollLog() {
  if (polling || !state.view.startsWith('proc:')) return;
  const p = current();
  if (!p) return;
  polling = true;

  try {
    const log = state.logs[p.id] || (state.logs[p.id] = { seq: 0, lines: [] });
    const data = await api('processes/' + p.id + '/log?after=' + log.seq);

    if (data.lines.length) {
      // Номер меньше нашего — консоль очистили или V.I.R.U.S. перезапущен: начинаем сначала
      if (data.lines[0].seq <= log.seq) {
        log.lines = [];
      }
      log.lines.push(...data.lines);
      log.seq = data.lines[data.lines.length - 1].seq;
      if (log.lines.length > 3000) log.lines.splice(0, log.lines.length - 3000);

      if (state.view === 'proc:' + p.id) renderConsole(false, data.lines);
    }
  } catch (e) {
    // связь пропала — следующий опрос попробует снова
  } finally {
    polling = false;
  }
}

$('console').addEventListener('scroll', () => {
  const box = $('console');
  state.stick = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  $('to-bottom').classList.toggle('hidden', state.stick);
});

$('to-bottom').addEventListener('click', () => {
  state.stick = true;
  const box = $('console');
  box.scrollTop = box.scrollHeight;
  $('to-bottom').classList.add('hidden');
});

$('filter').addEventListener('input', (e) => {
  state.filter = e.target.value.trim().toLowerCase();
  renderConsole(true);
});

for (const button of document.querySelectorAll('.seg-btn')) {
  button.addEventListener('click', () => {
    state.level = button.dataset.level;
    for (const other of document.querySelectorAll('.seg-btn')) other.classList.toggle('on', other === button);
    renderConsole(true);
  });
}

$('clear').addEventListener('click', async () => {
  const p = current();
  if (!p) return;
  await act('clear');
  state.logs[p.id] = { seq: state.logs[p.id]?.seq || 0, lines: [] };
  renderConsole(true);
});

$('p-start').addEventListener('click', () => act('start'));
$('p-stop').addEventListener('click', () => act('stop'));
$('p-restart').addEventListener('click', () => act('restart'));

// ---------------------------------------------------------------- конфиг

const config = { fields: [], original: {}, changes: {}, raw: false, rawOriginal: '' };

function configDirty() {
  if (config.raw) return $('c-raw').value !== config.rawOriginal;
  return Object.keys(config.changes).length > 0;
}

async function loadConfig() {
  let data;
  try {
    data = await api('config');
  } catch (e) {
    toast(e.message, 'bad');
    return;
  }

  config.fields = data.fields;
  config.original = Object.fromEntries(data.fields.map((f) => [f.key, f.value]));
  config.changes = {};
  $('c-path').textContent = data.path;

  const box = $('c-banners');
  box.replaceChildren();

  if (!data.exists) {
    const banner = el('div', 'banner warn');
    banner.append(el('span', '', 'Файла настроек ещё нет.'), el('span', 'grow'));
    const create = el('button', 'btn primary', 'Создать из образца');
    create.addEventListener('click', async () => {
      try { await api('config/create', {}); toast('config.properties создан'); loadConfig(); } catch (e) { toast(e.message, 'bad'); }
    });
    banner.append(create);
    box.append(banner);
  } else if (data.missing > 0) {
    $('missing-pill').textContent = data.missing;
    const banner = el('div', 'banner info');
    banner.append(el('span', '', 'В новой версии появились настройки, которых нет в твоём файле: ' + data.missing + '. Они добавятся в конец со значениями по умолчанию и пояснениями.'), el('span', 'grow'));
    const add = el('button', 'btn primary', 'Добавить');
    add.addEventListener('click', async () => {
      try {
        const result = await api('config/add-missing', {});
        toast('Добавлено настроек: ' + result.added);
        loadConfig();
      } catch (e) { toast(e.message, 'bad'); }
    });
    banner.append(add);
    box.append(banner);
  }

  $('missing-pill').classList.toggle('hidden', !(data.exists && data.missing > 0));

  if (config.raw) {
    const raw = await api('config/raw');
    config.rawOriginal = raw.text;
    $('c-raw').value = raw.text;
  }

  renderConfig();
  updateSavebar();
}

function renderConfig() {
  const form = $('c-form');
  const nav = $('c-nav');
  form.replaceChildren();
  nav.replaceChildren();

  const query = $('c-search').value.trim().toLowerCase();
  const sections = new Map();

  for (const field of config.fields) {
    if (query && !field.key.toLowerCase().includes(query) && !field.hint.toLowerCase().includes(query) && !field.section.toLowerCase().includes(query)) continue;
    if (!sections.has(field.section)) sections.set(field.section, []);
    sections.get(field.section).push(field);
  }

  if (!sections.size) {
    form.append(el('p', 'meta', config.fields.length ? 'Ничего не нашлось.' : 'Настроек пока нет.'));
  }

  let index = 0;
  for (const [name, fields] of sections) {
    const id = 'sec-' + index++;
    const section = el('section', 'section');
    section.id = id;
    section.append(el('h2', '', name));
    for (const field of fields) section.append(fieldNode(field));
    form.append(section);

    const link = el('a');
    link.href = '#' + id;
    link.append(el('span', '', name), el('span', 'n', String(fields.length)));
    link.addEventListener('click', (e) => {
      e.preventDefault();
      section.scrollIntoView({ block: 'start' });
    });
    nav.append(link);
  }
}

function fieldNode(field) {
  const row = el('div', 'field');
  row.dataset.key = field.key;

  const left = el('div');
  const key = el('div', 'field-key', field.key);
  left.append(key);
  if (field.hint) left.append(el('div', 'field-hint', field.hint));

  const right = el('div', 'field-input');
  const value = field.key in config.changes ? config.changes[field.key] : field.value;

  const mark = () => {
    const dirty = field.key in config.changes;
    row.classList.toggle('dirty', dirty);
    key.querySelector('.chg')?.remove();
    if (dirty) key.append(el('span', 'chg', '●'));
    updateSavebar();
  };

  const change = (next) => {
    if (next === config.original[field.key]) delete config.changes[field.key];
    else config.changes[field.key] = next;
    mark();
  };

  if (field.kind === 'bool') {
    const label = el('label', 'switch');
    const box = el('input');
    box.type = 'checkbox';
    box.checked = value.trim().toLowerCase() === 'true';
    const track = el('span', 'track');
    track.append(el('span', 'thumb'));
    const text = el('span', 'switch-label', box.checked ? 'включено' : 'выключено');
    box.addEventListener('change', () => {
      text.textContent = box.checked ? 'включено' : 'выключено';
      change(box.checked ? 'true' : 'false');
    });
    label.append(box, track, text);
    right.append(label);
  } else {
    const input = el('input');
    input.type = field.secret ? 'password' : 'text';
    input.value = value;
    input.autocomplete = 'off';
    input.spellcheck = false;
    if (field.kind === 'number') input.inputMode = 'numeric';
    input.addEventListener('input', () => change(input.value));
    right.append(input);

    if (field.secret) {
      const reveal = el('button', 'btn ghost reveal', '👁');
      reveal.type = 'button';
      reveal.title = 'Показать или скрыть';
      reveal.addEventListener('click', () => { input.type = input.type === 'password' ? 'text' : 'password'; });
      right.append(reveal);
    }
  }

  row.append(left, right);
  mark();
  return row;
}

function updateSavebar() {
  const dirty = configDirty();
  $('savebar').classList.toggle('hidden', !dirty);
  const count = Object.keys(config.changes).length;
  $('save-count').textContent = config.raw ? 'Текст изменён' : 'Изменено: ' + count;
}

async function saveConfig(restart) {
  try {
    if (config.raw) {
      await api('config/raw', { text: $('c-raw').value });
      toast('Конфиг сохранён');
    } else {
      const result = await api('config', { values: config.changes });
      toast('Сохранено: ' + result.changed.length);
    }

    if (restart) {
      const running = state.processes.filter((p) => p.state === 'RUNNING');
      for (const p of running) await api('processes/' + p.id + '/restart', {});
      toast(running.length ? 'Сохранено, перезапускаю: ' + running.map((p) => p.name).join(', ') : 'Сохранено. Запущенных процессов нет.');
    }

    await loadConfig();
    refresh();
  } catch (e) {
    toast(e.message, 'bad');
  }
}

$('open-config').addEventListener('click', () => show('config'));
$('c-search').addEventListener('input', renderConfig);
$('save').addEventListener('click', () => saveConfig(false));
$('save-restart').addEventListener('click', () => saveConfig(true));
$('discard').addEventListener('click', () => { config.changes = {}; if (config.raw) $('c-raw').value = config.rawOriginal; renderConfig(); updateSavebar(); });
$('c-raw').addEventListener('input', updateSavebar);

$('c-raw-toggle').addEventListener('click', async () => {
  if (configDirty() && !confirm('Несохранённые изменения пропадут. Переключить?')) return;
  config.raw = !config.raw;
  config.changes = {};
  $('c-raw-toggle').textContent = config.raw ? 'Формой' : 'Текстом';
  $('c-raw-wrap').classList.toggle('hidden', !config.raw);
  $('c-form-wrap').classList.toggle('hidden', config.raw);
  $('c-search').classList.toggle('hidden', config.raw);
  await loadConfig();
});

window.addEventListener('beforeunload', (e) => {
  if (state.view === 'config' && configDirty()) e.preventDefault();
});

// ---------------------------------------------------------------- процессы: добавить и изменить

let editing = null;

function openEditor(p) {
  editing = p;
  const form = $('edit-form');
  $('edit-title').textContent = p ? 'Процесс: ' + p.name : 'Новый процесс';
  form.elements.namedItem('name').value = p ? p.name : '';
  form.elements.namedItem('id').value = p ? p.id : '';
  form.elements.namedItem('id').readOnly = !!p;
  form.elements.namedItem('command').value = p ? p.commandLine : '{java} -jar ';
  form.elements.namedItem('workdir').value = p ? p.workdir : '';
  form.elements.namedItem('autostart').checked = p ? p.autostart : false;
  form.elements.namedItem('restart').checked = p ? p.restart : true;
  form.elements.namedItem('supervised').checked = p ? p.supervised : false;
  $('edit-delete').classList.toggle('hidden', !p);
  $('edit-dialog').showModal();
}

$('add-process').addEventListener('click', () => openEditor(null));
$('p-edit').addEventListener('click', () => openEditor(current()));
$('edit-cancel').addEventListener('click', () => $('edit-dialog').close());

$('edit-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  try {
    await api('processes', {
      id: form.elements.namedItem('id').value.trim(),
      name: form.elements.namedItem('name').value.trim(),
      command: form.elements.namedItem('command').value,
      workdir: form.elements.namedItem('workdir').value.trim(),
      autostart: form.elements.namedItem('autostart').checked,
      restart: form.elements.namedItem('restart').checked,
      supervised: form.elements.namedItem('supervised').checked,
    });
    $('edit-dialog').close();
    toast(editing ? 'Сохранено. Новая команда — со следующего запуска.' : 'Процесс добавлен');
    await refresh();
    if (!editing) show('proc:' + form.elements.namedItem('id').value.trim());
  } catch (err) {
    toast(err.message, 'bad');
  }
});

$('edit-delete').addEventListener('click', async () => {
  if (!editing || !confirm('Удалить «' + editing.name + '»? Если он работает — остановлю.')) return;
  try {
    await api('processes/' + editing.id + '/delete', {});
    $('edit-dialog').close();
    show('');
    refresh();
  } catch (e) {
    toast(e.message, 'bad');
  }
});

// ---------------------------------------------------------------- верхняя полоса

$('autostart').addEventListener('change', async (e) => {
  try {
    await api('autostart', { enabled: e.target.checked });
    toast(e.target.checked ? 'V.I.R.U.S. будет запускаться при входе в Windows' : 'Автозапуск выключен');
  } catch (err) {
    e.target.checked = !e.target.checked;
    toast(err.message, 'bad');
  }
});

$('quit').addEventListener('click', async () => {
  if (!confirm('Остановить все процессы и выключить V.I.R.U.S.?')) return;
  try { await api('quit', {}); } catch (e) { /* уже выключается */ }
  document.body.replaceChildren(el('div', 'view empty'));
  const box = document.body.firstChild;
  box.append(el('div', 'empty-mark', 'V'), el('p', '', 'V.I.R.U.S. выключен. Запустить снова — virus.jar или ярлык.'));
});

// ---------------------------------------------------------------- поехали

show(state.view);
refresh();
setInterval(refresh, 1500);
setInterval(pollLog, 700);
