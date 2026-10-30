// The mock control panel: vanilla JS, no library. Every write carries the X-Mock-Panel header the API requires.
'use strict';

const API = '/api/internal/mock';
const MODES = ['REAL', 'OK', 'ERROR', 'TIMEOUT'];
const MODE_LABELS = {REAL: 'Real', OK: 'OK', ERROR: 'Error', TIMEOUT: 'Timeout'};
const JOURNAL_LIMIT = 300;

const state = {
  accounts: [],
  selected: null,
  provider: 'STEAM',
  /** The search result picked for "Play", resolved again when the platform changes. */
  picked: null,
  /** Live journal entries received while the journal loads, so none falls in between. */
  journalBuffer: null,
};

const $ = (id) => document.getElementById(id);

/** Builds an element; strings become text nodes, so no name or value is ever read as HTML. */
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else if (key in node && typeof value !== 'string') node[key] = value;
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

let toastTimer;
function toast(message, error = false) {
  const node = $('toast');
  node.textContent = message;
  node.classList.toggle('error', error);
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, error ? 6000 : 2500);
}

async function call(method, path, body) {
  const init = {method, headers: {}};
  if (method !== 'GET') {
    init.headers['X-Mock-Panel'] = '1';
  }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const response = await fetch(API + path, init);
  if (!response.ok) {
    let detail = response.status + ' ' + response.statusText;
    try {
      const problem = await response.json();
      if (problem && problem.detail) detail = problem.detail;
    } catch (ignored) {
      // not a problem body
    }
    throw new Error(detail);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

/** Runs a write, says how it went, and refreshes what it may have changed. */
async function act(label, work, refresh = refreshAccount) {
  try {
    await work();
    toast(label);
  } catch (e) {
    toast(label + ' failed: ' + e.message, true);
  }
  if (refresh) await refresh();
}

// ------------------------------------------------------------------ accounts

async function loadAccounts() {
  try {
    state.accounts = await call('GET', '/accounts');
  } catch (e) {
    toast('Could not load the accounts: ' + e.message, true);
    return;
  }
  if (state.selected && !state.accounts.some((a) => a.id === state.selected)) {
    state.selected = null;
  }
  renderAccounts();
  renderModerateChoices($('create-moderates'), [], 'id');
  if (state.selected) await refreshAccount();
  else showAccount(false);
}

function renderAccounts() {
  const list = $('accounts');
  list.replaceChildren(...state.accounts.map((account) => el('li', {
      class: account.id === state.selected ? 'sel' : '',
      onclick: () => select(account.id),
    },
    account.profileImageUrl ? el('img', {class: 'avatar', src: account.profileImageUrl, alt: ''}) : null,
    el('span', {class: 'name', title: account.twitchId}, account.username || account.twitchId),
    account.fictitious ? el('span', {class: 'tag'}, 'fictitious') : el('span', {class: 'tag real'}, 'real'),
    account.converted ? el('span', {class: 'tag'}, 'converted') : null,
    account.admin ? el('span', {class: 'tag'}, 'admin') : null,
    account.botEnabled ? null : el('span', {class: 'tag'}, 'bot off'),
    account.moderates.length ? el('span', {class: 'tag'}, 'mod of ' + account.moderates.join(', ')) : null,
    account.fictitious ? null : el('button', {
      class: 'act', type: 'button', title: 'Make this real account a fictitious one, its configuration kept',
      onclick: (event) => { event.stopPropagation(); convert(account); },
    }, 'convert'),
    account.converted ? el('button', {
      class: 'act', type: 'button', title: 'Make this converted account real again',
      onclick: (event) => { event.stopPropagation(); revert(account); },
    }, 'revert') : null,
    account.fictitious && !account.converted ? el('button', {
      class: 'del', type: 'button', title: 'Delete this fictitious account',
      onclick: (event) => { event.stopPropagation(); remove(account); },
    }, '×') : null)));
}

/** Checkboxes of the other accounts; {@code key} is what a checked box stands for ({@code id} or {@code twitchId}). */
function renderModerateChoices(container, checked, key, exceptId) {
  container.replaceChildren(...state.accounts
    .filter((account) => account.id !== exceptId)
    .map((account) => el('label', {class: 'check'},
      el('input', {type: 'checkbox', value: account[key], checked: checked.includes(account[key])}),
      account.username || account.twitchId)));
}

function checkedValues(container) {
  return [...container.querySelectorAll('input:checked')].map((input) => input.value);
}

async function select(id) {
  state.selected = id;
  renderAccounts();
  await refreshAccount();
  await loadJournal();
}

async function remove(account) {
  if (!confirm('Delete the fictitious account ' + (account.username || account.twitchId) + '?')) return;
  await act('Account deleted', () => call('DELETE', '/accounts/' + account.id), loadAccounts);
}

async function convert(account) {
  if (!confirm('Convert ' + (account.username || account.twitchId) + ' to a fictitious account? Its Twitch token is '
    + 'dropped: once reverted, its member has to sign in again.')) return;
  await act('Account converted', () => call('POST', '/accounts/' + account.id + '/convert'), loadAccounts);
}

async function revert(account) {
  if (!confirm('Make ' + (account.username || account.twitchId) + ' a real account again?')) return;
  await act('Account reverted', () => call('POST', '/accounts/' + account.id + '/revert'), loadAccounts);
}

/** Shows {@code url} in {@code img}, hidden while empty. */
function showAvatar(img, url) {
  img.hidden = !url;
  if (url) img.src = url;
  else img.removeAttribute('src');
}

/** An optional field as the API reads it: null when left empty. */
function optional(input) {
  const value = input.value.trim();
  return value ? value : null;
}

$('prefill-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const login = $('prefill-login').value.trim();
  if (!login) return;
  const form = $('create-form');
  try {
    const user = await call('GET', '/twitch/users?login=' + encodeURIComponent(login));
    form.username.value = user.login;
    form.twitchId.value = 'm' + user.id;
    form.profileImageUrl.value = user.profileImageUrl || '';
    showAvatar($('create-avatar'), user.profileImageUrl);
    toast('Prefilled from ' + user.login);
  } catch (e) {
    toast('Prefill failed: ' + e.message, true);
  }
});

$('create-form').profileImageUrl.addEventListener('input', (event) =>
  showAvatar($('create-avatar'), event.target.value.trim()));

$('create-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const body = {
    username: form.username.value.trim(),
    twitchId: optional(form.twitchId),
    profileImageUrl: optional(form.profileImageUrl),
    steamId: optional(form.steamId),
    admin: form.admin.checked,
    botEnabled: form.botEnabled.checked,
    moderates: checkedValues($('create-moderates')),
  };
  let created = null;
  await act('Account created', async () => { created = await call('POST', '/accounts', body); }, loadAccounts);
  if (created) {
    form.reset();
    $('prefill-login').value = '';
    showAvatar($('create-avatar'), null);
    $('create').open = false;
    await select(created.id);
  }
});

// ------------------------------------------------------------------ selected account

function showAccount(visible) {
  $('account').hidden = !visible;
  $('empty').hidden = visible;
}

async function refreshAccount() {
  const id = state.selected;
  if (!id) return;
  let account;
  let current;
  let settings;
  try {
    [current, settings] = await Promise.all([
      call('GET', '/accounts/' + id + '/state'),
      call('GET', '/accounts/' + id + '/settings'),
    ]);
    account = state.accounts.find((a) => a.id === id);
  } catch (e) {
    toast('Could not load the account: ' + e.message, true);
    return;
  }
  if (state.selected !== id) return;
  showAccount(true);
  renderIdentity(account);
  renderState(account, current);
  renderSettings($('account-settings'), settings, (key, body) =>
    call('PUT', '/accounts/' + id + '/settings/' + key, body));
  renderData(account, current.data);
}

function renderIdentity(account) {
  $('identity-card').hidden = !account || !account.fictitious;
  if (!account || !account.fictitious) return;
  $('identity-username').value = account.username || '';
  $('identity-avatar-url').value = account.profileImageUrl || '';
  $('identity-steam').value = account.steamId || '';
  $('identity-twitch').textContent = 'Twitch id ' + account.twitchId + (account.converted ? ' (converted)' : '');
  showAvatar($('identity-avatar'), account.profileImageUrl);
}

$('identity-avatar-url').addEventListener('input', (event) =>
  showAvatar($('identity-avatar'), event.target.value.trim()));

$('identity-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const steamId = $('identity-steam').value.trim();
  act('Identity saved', () => call('PUT', '/accounts/' + state.selected + '/identity', {
    username: $('identity-username').value.trim(),
    profileImageUrl: $('identity-avatar-url').value.trim(),
    // An empty Steam id is left as it is: the API refuses a blank one
    steamId: steamId ? steamId : null,
  }), loadAccounts);
});

function renderState(account, current) {
  $('state-title').textContent = account ? '— ' + (account.username || account.twitchId) : '';
  $('live').checked = current.data.live;
  $('catapult-live').textContent = current.live ? 'live' : 'offline';
  $('bot').checked = current.botEnabled;
  $('detected').textContent = current.detectedGame
    ? current.detectedGame.name + ' (' + current.detectedGame.sourceType + ' ' + current.detectedGame.sourceId + ')'
    : 'none';
  $('category').textContent = (current.categoryName || '?') + (current.categoryId ? ' (' + current.categoryId + ')' : '');
}

$('live').addEventListener('change', (event) => act(event.target.checked ? 'Live' : 'Offline',
  () => call('POST', '/accounts/' + state.selected + '/live', {live: event.target.checked})));

$('bot').addEventListener('change', (event) => act(event.target.checked ? 'Bot on' : 'Bot off',
  () => call('POST', '/accounts/' + state.selected + '/bot', {enabled: event.target.checked}),
  async () => { await loadAccounts(); }));

$('platform').addEventListener('click', (event) => {
  const button = event.target.closest('button[data-provider]');
  if (!button) return;
  state.provider = button.dataset.provider;
  for (const other of $('platform').querySelectorAll('button')) {
    other.classList.toggle('on', other === button);
  }
  if (state.picked) resolvePicked();
});

/** Asks Catapult for the picked game's id on the selected platform; the id field stays editable as a fallback. */
async function resolvePicked() {
  const picked = state.picked;
  const provider = state.provider;
  const note = $('game-note');
  $('game-name').value = picked;
  $('game-id').value = '';
  note.textContent = 'Resolving…';
  try {
    const resolution = await call('GET', '/games/resolve?provider=' + provider
      + '&name=' + encodeURIComponent(picked));
    if (state.picked !== picked || state.provider !== provider) return;
    $('game-name').value = resolution.name;
    $('game-id').value = resolution.sourceId || '';
    note.textContent = resolution.sourceId
      ? 'Resolved by Catapult.'
      : 'Not resolved: ' + resolution.reason + '. Type the id by hand.';
  } catch (e) {
    note.textContent = 'Could not resolve: ' + e.message + '. Type the id by hand.';
  }
}

$('search-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const q = $('search').value.trim();
  if (!q) return;
  try {
    const games = await call('GET', '/accounts/' + state.selected + '/games?q=' + encodeURIComponent(q));
    $('results').replaceChildren(...(games.length ? games : [{id: '', name: 'No result'}]).map((game) =>
      el('li', {
        title: 'Picks this game for "Play" and fills the manual category',
        onclick: () => {
          if (!game.id) return;
          state.picked = game.name;
          resolvePicked();
          $('category-id').value = game.id;
          $('category-name').value = game.name;
        },
      }, game.name, game.id ? el('span', {class: 'muted'}, ' (category ' + game.id + ')') : null)));
  } catch (e) {
    toast('Search failed: ' + e.message, true);
  }
});

$('play-form').addEventListener('submit', (event) => {
  event.preventDefault();
  act('Playing', () => call('POST', '/accounts/' + state.selected + '/play', {
    provider: state.provider,
    sourceId: $('game-id').value.trim(),
    name: $('game-name').value.trim(),
  }));
});

$('stop').addEventListener('click', () =>
  act('Stopped playing', () => call('POST', '/accounts/' + state.selected + '/stop-playing')));

$('category-form').addEventListener('submit', (event) => {
  event.preventDefault();
  act('Category changed on Twitch', () => call('POST', '/accounts/' + state.selected + '/manual-category', {
    categoryId: $('category-id').value.trim(),
    categoryName: $('category-name').value.trim(),
  }));
});

$('raid-form').addEventListener('submit', (event) => {
  event.preventDefault();
  act('Raid sent', () => call('POST', '/accounts/' + state.selected + '/raid-out', {
    toLogin: $('raid-login').value.trim(),
  }));
});

// ------------------------------------------------------------------ settings, one call at a time

/** One grid per API; each call has its mode, its HTTP status for Error, and its latency. */
function renderSettings(container, settings, save) {
  const byApi = new Map();
  for (const setting of settings) {
    if (!byApi.has(setting.api)) byApi.set(setting.api, []);
    byApi.get(setting.api).push(setting);
  }
  const reload = container.id === 'global-settings' ? loadGlobal : refreshAccount;
  container.replaceChildren(...[...byApi.entries()].map(([api, calls]) => el('div', {},
    el('h3', {}, api),
    el('div', {class: 'settings'}, ...calls.flatMap((setting) => settingRow(setting, save, reload))))));
}

function settingRow(setting, save, reload) {
  const code = el('input', {
    type: 'number', class: 'code', min: 400, max: 599, title: 'HTTP status of the error',
    value: setting.mode === 'ERROR' ? setting.httpStatus : 500,
    hidden: setting.mode !== 'ERROR', disabled: setting.forced,
  });
  const latency = el('input', {
    type: 'number', class: 'latency', min: 0, max: 60000, step: 50, title: 'Latency added to the call (ms)',
    value: setting.latencyMs, disabled: setting.forced,
  });
  const send = (mode) => act(setting.key + ' → ' + MODE_LABELS[mode], () => save(setting.key, {
    mode,
    httpStatus: mode === 'ERROR' ? Number(code.value) : null,
    latencyMs: Number(latency.value) || 0,
  }), reload);
  const seg = el('span', {class: 'seg'}, ...MODES.map((mode) => el('button', {
    type: 'button',
    class: mode === setting.mode ? 'on' : '',
    disabled: setting.forced || (mode === 'REAL' && !setting.realAllowed && setting.mode !== 'REAL'),
    title: mode === 'REAL' && !setting.realAllowed ? 'Needs a token, a link or a key this account lacks' : null,
    onclick: () => send(mode),
  }, MODE_LABELS[mode])));
  code.addEventListener('change', () => send('ERROR'));
  latency.addEventListener('change', () => send(setting.mode));
  const row = [
    el('span', {class: 'call', title: setting.key}, setting.call),
    seg,
    // Wrapped: the hidden input alone would leave the grid and shift the row
    el('span', {}, code),
    el('span', {}, latency, el('span', {class: 'muted'}, ' ms')),
  ];
  if (setting.forced) {
    row.push(el('span', {class: 'hint muted'},
      'Forced Real: a real call of the same token needs it refreshed for real. Set those back to change it.'));
  }
  return row;
}

async function loadGlobal() {
  try {
    const settings = await call('GET', '/global/settings');
    renderSettings($('global-settings'), settings, (key, body) => call('PUT', '/global/settings/' + key, body));
  } catch (e) {
    toast('Could not load the global settings: ' + e.message, true);
  }
}

// ------------------------------------------------------------------ simulated data

function renderData(account, data) {
  $('steam-public').checked = data.steamProfilePublic;
  renderModerateChoices($('data-moderates'), data.moderatedChannelIds, 'twitchId', account && account.id);
  $('ccls').value = data.ccls.join(', ');
  $('installed').value = JSON.stringify(data.installed, null, 2);
  $('active').value = JSON.stringify(data.active, null, 2);
}

$('data-form').addEventListener('submit', (event) => {
  event.preventDefault();
  let installed;
  let active;
  try {
    installed = JSON.parse($('installed').value || '[]');
    active = JSON.parse($('active').value || '{}');
  } catch (e) {
    toast('The extensions are not valid JSON: ' + e.message, true);
    return;
  }
  act('Simulated data saved', () => call('PUT', '/accounts/' + state.selected + '/data', {
    steamProfilePublic: $('steam-public').checked,
    moderatedChannelIds: checkedValues($('data-moderates')),
    ccls: $('ccls').value.split(',').map((s) => s.trim()).filter(Boolean),
    installed,
    active,
  }), async () => { await loadAccounts(); });
});

// ------------------------------------------------------------------ journal

function journalLine(entry) {
  const time = new Date(entry.at).toLocaleTimeString();
  const account = entry.accountId ? state.accounts.find((a) => a.id === entry.accountId) : null;
  const who = entry.accountId ? (account ? account.username || account.twitchId : entry.accountId) : 'global';
  return el('div', {class: 'mode-' + entry.mode},
    time + '  ' + entry.mode.padEnd(8) + ' ' + entry.key + '  ' + entry.request + '  → ' + entry.outcome
    + (entry.latencyMs ? '  +' + entry.latencyMs + ' ms' : '')
    + (who ? '  [' + who + ']' : ''));
}

function journalFilter() {
  return $('journal-mine').checked && state.selected ? state.selected : null;
}

function entryKey(entry) {
  return entry.at + '|' + entry.key + '|' + entry.accountId + '|' + entry.request;
}

function showEntry(entry) {
  const account = journalFilter();
  if (account && entry.accountId !== account) return;
  const journal = $('journal');
  journal.prepend(journalLine(entry));
  while (journal.childElementCount > JOURNAL_LIMIT) journal.lastElementChild.remove();
}

/** The kept entries; what the live stream brings meanwhile is buffered, then added unless already listed. */
async function loadJournal() {
  const account = journalFilter();
  state.journalBuffer = [];
  try {
    const entries = await call('GET', '/journal' + (account ? '?account=' + account : ''));
    $('journal').replaceChildren(...entries.slice(-JOURNAL_LIMIT).reverse().map(journalLine));
    const listed = new Set(entries.map(entryKey));
    for (const entry of state.journalBuffer) {
      if (!listed.has(entryKey(entry))) showEntry(entry);
    }
  } catch (e) {
    toast('Could not load the journal: ' + e.message, true);
  } finally {
    state.journalBuffer = null;
  }
}

let refreshTimer;
/** Opens the live stream; resolves once it is open (or after a few seconds, the journal loads anyway). */
function connectJournal() {
  const source = new EventSource(API + '/journal/stream');
  const opened = new Promise((resolve) => {
    source.addEventListener('open', resolve, {once: true});
    setTimeout(resolve, 3000);
  });
  source.onopen = () => { $('journal-status').textContent = 'live'; };
  source.onerror = () => { $('journal-status').textContent = 'reconnecting…'; };
  source.onmessage = (message) => {
    const entry = JSON.parse(message.data);
    if (state.journalBuffer) state.journalBuffer.push(entry);
    else showEntry(entry);
    // What the selected channel went through may have changed its state: refresh it, once per burst
    if (state.selected && entry.accountId === state.selected) {
      clearTimeout(refreshTimer);
      refreshTimer = setTimeout(refreshAccount, 400);
    }
  };
  return opened;
}

$('journal-mine').addEventListener('change', loadJournal);

// ------------------------------------------------------------------ start

(async () => {
  // The stream opens first, so nothing recorded while the journal loads is missed
  const streaming = connectJournal();
  await loadAccounts();
  await loadGlobal();
  await streaming;
  await loadJournal();
})();
