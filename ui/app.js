// Vidloot UI. Talks only to Vidloot's own Rust commands (see src-tauri/src/lib.rs).
'use strict';

// Where the "Get Pro" button sends people: your checkout page (must be https).
// While it's empty, the buy buttons are hidden and only "I have a license key" shows.
const BUY_URL = '';

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const $ = (sel) => document.querySelector(sel);

/** Small DOM helper: h('div', {class: 'x', onclick}, child, 'text'). Text is never parsed as HTML. */
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === false || v == null) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'html') el.innerHTML = v; // only ever used with our own static SVG strings
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

const ICON = {
  video: '<svg viewBox="0 0 24 24"><rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10l5-3v10l-5-3"/></svg>',
  audio: '<svg viewBox="0 0 24 24"><path d="M9 18V6l11-2v12"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/></svg>',
  play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  lock: '<svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>',
  folder: '<svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>',
  download: '<svg viewBox="0 0 24 24"><path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M5 20h14"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path d="M9 5v14M15 5v14"/></svg>',
  resume: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>',
  retry: '<svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>',
  open: '<svg viewBox="0 0 24 24"><path d="M14 4h6v6"/><path d="M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
  info: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v5h1"/></svg>',
  link: '<svg viewBox="0 0 24 24"><path d="M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1"/><path d="M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1"/></svg>',
};
const icon = (name) => h('span', { html: ICON[name], style: 'display:contents' });

// ------------------------------------------------------------------ state

const state = {
  view: 'download',
  settings: null,
  license: null,
  jobs: new Map(),
  filter: 'all',
  info: null,        // last fetched MediaInfo
  choice: null,      // { mode, video, audio, playlist, subtitles, thumb, chapters }
  fetching: false,
  error: null,
  lastClip: '',
};

const ent = () => state.license?.entitlements || { plan: 'Free', maxHeight: 720, playlists: false, subtitles: false, parallelDownloads: 1 };
const heightAllowed = (h) => ent().maxHeight === 0 || h <= ent().maxHeight;

// ------------------------------------------------------------------ helpers

function fmtBytes(n) {
  if (!n || n <= 0) return '';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1000 && i < u.length - 1) { n /= 1000; i++; }
  return `${n >= 100 || i === 0 ? Math.round(n) : n.toFixed(1)} ${u[i]}`;
}
function fmtDuration(s) {
  if (!s) return '';
  s = Math.round(s);
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  return (hh ? `${hh}:${String(mm).padStart(2, '0')}` : `${mm}`) + `:${String(ss).padStart(2, '0')}`;
}
const isUrl = (t) => /^https?:\/\/\S+$/i.test((t || '').trim());

function toast(msg, opts = {}) {
  const el = h('div', { class: 'toast' + (opts.error ? ' err' : '') }, h('span', null, msg),
    opts.action ? h('button', { onclick: () => { opts.action.run(); el.remove(); } }, opts.action.label) : null);
  $('#toasts').append(el);
  setTimeout(() => el.remove(), opts.error ? 7000 : 4000);
}

/** Commands reject with a string; plan limits come back as "PRO:<kind>:<message>". */
function handleError(e) {
  const msg = String(e?.message || e);
  if (msg.startsWith('PRO:')) {
    const [, kind, ...rest] = msg.split(':');
    openUpgrade(rest.join(':') || 'This needs Pro.', kind);
  } else {
    toast(msg, { error: true });
  }
}

// ------------------------------------------------------------------ navigation

function show(view) {
  state.view = view;
  for (const b of document.querySelectorAll('.nav-item')) b.classList.toggle('active', b.dataset.view === view);
  for (const v of ['download', 'queue', 'settings']) $(`#view-${v}`).hidden = v !== view;
  if (view === 'queue') renderQueue();
  if (view === 'settings') renderSettings();
  if (view === 'download') $('#url').focus();
}
for (const b of document.querySelectorAll('.nav-item')) b.addEventListener('click', () => show(b.dataset.view));

// ------------------------------------------------------------------ download view

$('#link-form').addEventListener('submit', (e) => { e.preventDefault(); fetchInfo($('#url').value); });
$('#paste-btn').addEventListener('click', async () => {
  const t = await invoke('read_clipboard');
  if (t) { $('#url').value = t; if (isUrl(t)) fetchInfo(t); }
  else toast('The clipboard is empty.');
});
$('#url').addEventListener('paste', () => setTimeout(() => { const v = $('#url').value; if (isUrl(v)) fetchInfo(v); }, 0));

async function fetchInfo(url) {
  url = (url || '').trim();
  if (!url) { $('#url').focus(); return; }
  $('#clip-banner').hidden = true;
  state.fetching = true; state.error = null; state.info = null;
  renderResult();
  try {
    const info = await invoke('fetch_info', { url });
    const s = state.settings;
    const wanted = Number(s.defaultQuality) || 1080;
    // Pick the preferred quality, or the best one the plan allows below it.
    const allowed = info.videoOptions.filter((o) => o.height === 0 || heightAllowed(o.height));
    const pick = allowed.find((o) => o.height <= wanted) || allowed[allowed.length - 1] || info.videoOptions[0];
    state.info = info;
    state.choice = {
      mode: s.defaultMode,
      video: pick?.id,
      audio: s.defaultAudio,
      playlist: info.isPlaylist,
      subtitles: s.subtitles && ent().subtitles,
      thumb: s.embedThumbnail,
      chapters: s.keepChapters,
    };
  } catch (e) {
    state.error = String(e);
  } finally {
    state.fetching = false;
    renderResult();
  }
}

function renderResult() {
  const root = $('#result');
  root.replaceChildren();
  if (state.fetching) {
    root.append(h('div', { class: 'card' }, h('div', { class: 'skeleton' },
      h('div', { class: 'sk', style: 'width:320px;aspect-ratio:16/9' }),
      h('div', { style: 'flex:1;display:flex;flex-direction:column;gap:12px' },
        h('div', { class: 'sk', style: 'height:22px;width:70%' }), h('div', { class: 'sk', style: 'height:16px;width:40%' }),
        h('div', { class: 'sk', style: 'height:52px;width:90%;margin-top:14px' }), h('div', { class: 'sk', style: 'height:52px;width:60%' })))));
    return;
  }
  if (state.error) {
    root.append(h('div', { class: 'notice err' }, icon('info'), h('span', null, state.error)));
    return;
  }
  if (!state.info) {
    root.append(h('div', { class: 'empty' }, icon('link'), h('b', null, 'Copy a link, then come back here'),
      h('span', null, 'Vidloot spots links on your clipboard. You can also drag a link into the box above.')));
    return;
  }

  const i = state.info, c = state.choice, e = ent();
  const isVideo = c.mode === 'video';

  const thumb = h('div', { class: 'thumb' },
    i.thumbnail && i.thumbnail.startsWith('https://') ? h('img', { src: i.thumbnail, alt: '' }) : h('div', { class: 'play', html: ICON.play }),
    i.isPlaylist ? h('span', { class: 'tag' }, `${i.entryCount ?? '?'} videos`) : i.duration ? h('span', { class: 'tag' }, fmtDuration(i.duration)) : null);

  const meta = [i.uploader, i.site, i.isPlaylist ? 'Playlist' : (i.maxHeight >= 2160 ? '4K available' : i.maxHeight ? `Up to ${i.maxHeight}p` : null)].filter(Boolean).join(' · ');

  const seg = h('div', { class: 'segment', role: 'group', 'aria-label': 'Download type' },
    h('button', { 'aria-pressed': String(isVideo), onclick: () => { c.mode = 'video'; renderResult(); } }, icon('video'), 'Video'),
    h('button', { 'aria-pressed': String(!isVideo), onclick: () => { c.mode = 'audio'; renderResult(); } }, icon('audio'), 'Audio only'));

  const pills = isVideo
    ? i.videoOptions.map((o) => {
        const locked = o.height > 0 && !heightAllowed(o.height);
        return h('button', {
          class: 'pill' + (locked ? ' locked' : ''), 'aria-pressed': String(c.video === o.id),
          onclick: () => locked ? openUpgrade(`${o.label} needs Pro. The ${e.plan} plan downloads up to ${e.maxHeight}p.`, 'quality') : (c.video = o.id, renderResult()),
        }, h('b', null, o.label), h('small', null, o.size ? `≈ ${fmtBytes(o.size)}` : o.height === 2160 ? '2160p' : ' '),
          locked ? h('span', { class: 'lock' }, icon('lock'), 'PRO') : null);
      })
    : i.audioOptions.map((o) => h('button', { class: 'pill', 'aria-pressed': String(c.audio === o.id), onclick: () => { c.audio = o.id; renderResult(); } },
        h('b', null, o.label), h('small', null, [o.note, o.size ? fmtBytes(o.size) : ''].filter(Boolean).join(' · '))));

  const check = (label, key, opts = {}) => h('label', { class: 'check' },
    h('input', { type: 'checkbox', checked: c[key], onchange: (ev) => {
      if (opts.locked) { ev.target.checked = false; openUpgrade(opts.lockMsg, opts.kind); return; }
      c[key] = ev.target.checked; renderResult();
    } }), label, opts.locked ? h('span', { class: 'lock' }, icon('lock'), 'PRO') : null);

  const checks = h('div', { class: 'checks' },
    (i.isPlaylist || i.inPlaylist) ? check(i.isPlaylist ? `Whole playlist (${i.entryCount ?? '?'} videos)` : 'Download the whole playlist', 'playlist',
      { locked: !e.playlists, lockMsg: 'Whole playlists need Pro.', kind: 'playlists' }) : null,
    isVideo ? check(i.hasSubtitles || i.isPlaylist ? 'Subtitles' : 'Subtitles (if available)', 'subtitles', { locked: !e.subtitles, lockMsg: 'Subtitles need Pro.', kind: 'subtitles' }) : null,
    !(c.mode === 'audio' && c.audio === 'wav') ? check(isVideo ? 'Embed thumbnail' : 'Add cover image', 'thumb') : null,
    check('Keep chapters', 'chapters'));

  const playlistBlocked = i.isPlaylist && !e.playlists;
  const chosen = isVideo ? i.videoOptions.find((o) => o.id === c.video) : i.audioOptions.find((o) => o.id === c.audio);
  const what = isVideo ? (chosen?.id === 'best' ? 'MP4' : `${chosen?.label || ''} MP4`) : chosen?.label || '';
  const ctaText = playlistBlocked ? 'Unlock playlists' : `Download ${c.playlist ? 'playlist · ' : ''}${what}`;

  root.append(h('section', { class: 'card', 'aria-label': 'Video details and options' }, h('div', { class: 'result' },
    h('div', { class: 'result-left' }, thumb, h('div', { class: 'r-title' }, i.title), h('div', { class: 'r-meta' }, meta)),
    h('div', { class: 'result-right' },
      h('div', { class: 'group' }, h('span', { class: 'group-label' }, 'What do you want?'), seg),
      h('div', { class: 'group' }, h('span', { class: 'group-label' }, isVideo ? 'Quality' : 'Audio format'), h('div', { class: 'pills' }, pills)),
      checks,
      playlistBlocked ? h('div', { class: 'notice' }, icon('info'), h('span', null, `This link is a playlist. The ${e.plan} plan downloads single videos; paste one video's link, or upgrade.`)) : null,
      h('div', { class: 'foot' },
        h('div', { class: 'save-to' }, icon('folder'), 'Save to', h('strong', { title: state.settings.downloadDir }, shortDir(state.settings.downloadDir)),
          h('button', { class: 'link', onclick: changeFolder }, 'Change')),
        h('button', { class: 'btn btn-primary btn-lg', id: 'dl-btn', onclick: playlistBlocked ? () => openUpgrade('Whole playlists need Pro.', 'playlists') : startDownload },
          icon('download'), ctaText))))));
}

function shortDir(d) {
  if (!d) return '';
  const parts = d.split(/[\\/]/).filter(Boolean);
  return parts.length > 2 ? `…/${parts.slice(-2).join('/')}` : d;
}

async function startDownload() {
  const i = state.info, c = state.choice;
  const btn = $('#dl-btn'); if (btn) btn.disabled = true;
  try {
    await invoke('start_download', { request: {
      url: i.url, title: i.title, thumbnail: i.thumbnail || null,
      mode: c.mode, quality: c.mode === 'video' ? c.video : c.audio,
      playlist: !!c.playlist, subtitles: c.mode === 'video' && !!c.subtitles,
      embedThumbnail: !!c.thumb, chapters: !!c.chapters,
    } });
    toast('Added to your queue', { action: { label: 'View queue', run: () => show('queue') } });
    state.info = null; $('#url').value = '';
    renderResult();
  } catch (e) {
    handleError(e);
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function changeFolder() {
  try {
    const s = await invoke('pick_folder');
    if (s) { state.settings = s; renderResult(); renderSettings(); }
  } catch (e) { handleError(e); }
}

// ---- clipboard watching: offer a copied link when the window gets focus
async function checkClipboard() {
  if (!state.settings?.watchClipboard || state.view !== 'download' || state.fetching) return;
  const t = await invoke('read_clipboard').catch(() => null);
  if (!t || !isUrl(t) || t === state.lastClip || t === state.info?.url || t === $('#url').value.trim()) return;
  state.lastClip = t;
  $('#clip-url').textContent = t;
  $('#clip-banner').hidden = false;
}
$('#clip-use').addEventListener('click', () => { const t = $('#clip-url').textContent; $('#url').value = t; fetchInfo(t); });
$('#clip-dismiss').addEventListener('click', () => { $('#clip-banner').hidden = true; });
window.addEventListener('focus', checkClipboard);

// ------------------------------------------------------------------ queue view

const FINISHED = ['done', 'error', 'canceled'];
const ACTIVE = ['queued', 'downloading', 'processing', 'paused'];

for (const t of document.querySelectorAll('.tab')) {
  t.addEventListener('click', () => {
    state.filter = t.dataset.filter;
    for (const x of document.querySelectorAll('.tab')) x.setAttribute('aria-selected', String(x === t));
    renderQueue();
  });
}
$('#pause-all').addEventListener('click', () => invoke('queue_action', { action: 'pause-all' }).catch(handleError));
$('#clear-finished').addEventListener('click', async () => {
  await invoke('queue_action', { action: 'clear-finished' }).catch(handleError);
  for (const [id, j] of state.jobs) if (FINISHED.includes(j.status)) state.jobs.delete(id);
  renderQueue();
});

const act = (id, action) => invoke('job_action', { id, action }).catch(handleError);

function jobRow(j) {
  const busy = j.status === 'processing' || (j.status === 'downloading' && j.percent === 0);
  const barCls = 'bar' + (j.status === 'done' ? ' done' : j.status === 'error' ? ' err' : j.status === 'paused' || j.status === 'canceled' || j.status === 'queued' ? ' paused' : '') + (busy ? ' busy' : '');
  const stMap = { queued: 'Waiting', downloading: `${j.stage || 'Downloading'} ${Math.floor(j.percent)}%`, processing: j.stage || 'Finishing',
    paused: 'Paused', done: 'Finished', error: 'Failed', canceled: 'Canceled' };
  const detail = [];
  if (j.itemCount > 1) detail.push(`Item ${j.itemIndex} of ${j.itemCount}`);
  if (j.status === 'downloading') {
    if (j.total) detail.push(`${fmtBytes(j.downloaded)} of ${fmtBytes(j.total)}`);
    if (j.speed) detail.push(j.speed);
    if (j.eta) detail.push(`${j.eta} left`);
  }
  if (j.status === 'done' && j.filePath) detail.push(j.filePath.split(/[\\/]/).pop());
  if (j.status === 'error' && j.error) detail.push(j.error);

  const ib = (name, label, fn) => h('button', { class: 'icon-btn', 'aria-label': label, title: label, onclick: fn }, icon(name));
  let actions;
  if (j.status === 'done') {
    actions = [j.filePath && !j.playlist ? ib('play', 'Open', () => invoke('open_file', { path: j.filePath }).catch(handleError)) : null,
      ib('folder', 'Show in folder', () => invoke('show_in_folder', { path: j.filePath || j.dir }).catch(handleError)),
      ib('x', 'Remove from list', () => { act(j.id, 'remove'); state.jobs.delete(j.id); renderQueue(); })];
  } else if (j.status === 'error' || j.status === 'canceled') {
    actions = [j.status === 'error' ? ib('retry', 'Retry', () => act(j.id, 'retry')) : null,
      ib('x', 'Remove from list', () => { act(j.id, 'remove'); state.jobs.delete(j.id); renderQueue(); })];
  } else if (j.status === 'paused') {
    actions = [ib('resume', 'Resume', () => act(j.id, 'resume')), ib('x', 'Cancel', () => act(j.id, 'cancel'))];
  } else {
    actions = [ib('pause', 'Pause', () => act(j.id, 'pause')), ib('x', 'Cancel', () => act(j.id, 'cancel'))];
  }

  return h('div', { class: 'job', 'data-id': j.id },
    h('div', { class: 'thumb' }, j.thumbnail && j.thumbnail.startsWith('https://') ? h('img', { src: j.thumbnail, alt: '' }) : icon(j.mode === 'audio' ? 'audio' : 'play')),
    h('div', { class: 'job-body' },
      h('div', { class: 'job-top' }, h('span', { class: 'job-title', title: j.title }, j.title), h('span', { class: 'fmt' }, j.playlist ? `Playlist · ${j.label}` : j.label)),
      h('div', { class: barCls }, h('div', { style: `width:${j.status === 'done' ? 100 : j.percent.toFixed(1)}%` })),
      h('div', { class: 'job-status' }, h('span', { class: 'st' + (j.status === 'done' ? ' ok' : j.status === 'error' ? ' err' : '') }, stMap[j.status] || j.status),
        ...detail.map((d) => h('span', null, d)))),
    h('div', { class: 'job-actions' }, actions));
}

function sortedJobs() {
  return [...state.jobs.values()].sort((a, b) => {
    const fa = FINISHED.includes(a.status), fb = FINISHED.includes(b.status);
    if (fa !== fb) return fa ? 1 : -1;
    return fa ? (b.finishedAt || 0) - (a.finishedAt || 0) : a.createdAt - b.createdAt;
  });
}

function renderQueue() {
  const all = sortedJobs();
  const active = all.filter((j) => ACTIVE.includes(j.status));
  const running = all.filter((j) => j.status === 'downloading' || j.status === 'processing');
  const badge = $('#queue-badge');
  badge.hidden = active.length === 0; badge.textContent = active.length;
  if (state.view !== 'queue') return;

  const list = state.filter === 'active' ? active : state.filter === 'finished' ? all.filter((j) => FINISHED.includes(j.status)) : all;
  const tabs = document.querySelectorAll('.tab');
  tabs[0].textContent = `All (${all.length})`;
  tabs[1].textContent = `Active (${active.length})`;
  tabs[2].textContent = `Finished (${all.length - active.length})`;
  $('#queue-summary').textContent = running.length ? `${running.length} downloading${active.length > running.length ? ` · ${active.length - running.length} waiting or paused` : ''}` :
    active.length ? `${active.length} waiting or paused` : 'Nothing downloading';
  const root = $('#job-list');
  root.replaceChildren(...(list.length ? list.map(jobRow) : [h('div', { class: 'empty' }, icon('download'), h('b', null, 'No downloads here yet'),
    h('span', null, 'Paste a link on the New download screen to start.'))]));
}

listen('job-updated', (ev) => {
  const j = ev.payload;
  const prev = state.jobs.get(j.id);
  state.jobs.set(j.id, j);
  if (prev && prev.status !== 'done' && j.status === 'done') {
    toast(`Downloaded: ${j.title}`, { action: { label: 'Show', run: () => invoke('show_in_folder', { path: j.filePath || j.dir }) } });
  }
  if (prev && prev.status !== 'error' && j.status === 'error') toast(`Failed: ${j.title}`, { error: true });
  // Patch the one row in place for smooth progress; full render otherwise.
  const row = document.querySelector(`.job[data-id="${CSS.escape(j.id)}"]`);
  if (row && state.view === 'queue' && prev && prev.status === j.status) row.replaceWith(jobRow(j));
  else renderQueue();
});

// ------------------------------------------------------------------ settings view

function renderSettings() {
  const s = state.settings; if (!s) return;
  $('#set-dir').textContent = s.downloadDir;
  $('#set-mode').value = s.defaultMode;
  $('#set-quality').value = s.defaultQuality;
  $('#set-audio').value = s.defaultAudio;
  $('#set-langs').value = s.subtitleLangs;
  $('#set-thumb').checked = s.embedThumbnail;
  $('#set-chapters').checked = s.keepChapters;
  $('#set-subs').checked = s.subtitles;
  $('#set-clip').checked = s.watchClipboard;
  renderLicense();
}

async function saveSettings(patch) {
  try { state.settings = await invoke('save_settings', { settings: { ...state.settings, ...patch } }); }
  catch (e) { handleError(e); }
}
$('#set-mode').addEventListener('change', (e) => saveSettings({ defaultMode: e.target.value }));
$('#set-quality').addEventListener('change', (e) => saveSettings({ defaultQuality: e.target.value }));
$('#set-audio').addEventListener('change', (e) => saveSettings({ defaultAudio: e.target.value }));
$('#set-langs').addEventListener('change', (e) => saveSettings({ subtitleLangs: e.target.value.trim() }));
$('#set-thumb').addEventListener('change', (e) => saveSettings({ embedThumbnail: e.target.checked }));
$('#set-chapters').addEventListener('change', (e) => saveSettings({ keepChapters: e.target.checked }));
$('#set-subs').addEventListener('change', (e) => {
  if (e.target.checked && !ent().subtitles) { e.target.checked = false; openUpgrade('Subtitles need Pro.', 'subtitles'); return; }
  saveSettings({ subtitles: e.target.checked });
});
$('#set-clip').addEventListener('change', (e) => saveSettings({ watchClipboard: e.target.checked }));
$('#set-dir-change').addEventListener('click', changeFolder);
$('#set-dir-open').addEventListener('click', () => invoke('open_folder').catch(handleError));

function renderLicense() {
  const L = state.license; const root = $('#license-body'); root.replaceChildren();
  if (!L) return;
  const e = L.entitlements;
  const badge = { active: ['ok', 'Active'], grace: ['warn', 'Offline grace period'], invalid: ['err', 'Not valid'], free: ['', 'No license'] }[L.status] || ['', L.status];
  root.append(h('div', { class: 'lic-status' }, h('span', { class: `pillbadge ${badge[0]}` }, badge[1]), h('b', null, `${e.plan} plan`),
    L.keyHint ? h('span', { class: 'muted' }, `Key ${L.keyHint}`) : null,
    L.expiresAt ? h('span', { class: 'muted' }, `Renews or expires ${L.expiresAt}`) : null,
    L.updatesUntil ? h('span', { class: 'muted' }, `Updates until ${L.updatesUntil}`) : null));
  if (L.message) root.append(h('div', { class: 'notice' + (L.status === 'invalid' ? ' err' : '') }, icon('info'), h('span', null, L.message)));
  root.append(h('ul', { class: 'ent' },
    h('li', null, `Video quality: up to ${e.maxHeight === 0 ? '4K' : `${e.maxHeight}p`}`),
    h('li', null, `Playlists: ${e.playlists ? 'yes' : 'no'}`),
    h('li', null, `Subtitles: ${e.subtitles ? 'yes' : 'no'}`),
    h('li', null, `Downloads at once: ${e.parallelDownloads}`)));

  if (L.status === 'free' || L.status === 'invalid') {
    const input = h('input', { class: 'key-input', id: 'key-input', placeholder: 'XXXX-XXXX-XXXX-XXXX', 'aria-label': 'License key', spellcheck: 'false' });
    const btn = h('button', { class: 'btn btn-primary', onclick: async () => {
      if (!input.value.trim()) { input.focus(); return; }
      btn.disabled = true; btn.textContent = 'Activating…';
      try { state.license = await invoke('license_activate', { key: input.value.trim() }); applyLicense(); toast(`Welcome to ${state.license.entitlements.plan}!`); }
      catch (err) { handleError(err); }
      finally { btn.disabled = false; btn.textContent = 'Activate'; }
    } }, 'Activate');
    input.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') btn.click(); });
    root.append(h('div', { class: 'lic-row' }, input, btn),
      BUY_URL ? h('button', { class: 'link', style: 'align-self:flex-start', onclick: () => invoke('open_link', { url: BUY_URL }).catch(handleError) }, "Don't have a key? Get Pro") : null);
  } else {
    root.append(h('div', { class: 'lic-row' },
      h('button', { class: 'btn btn-outline', onclick: async () => { state.license = await invoke('license_refresh').catch((e) => (handleError(e), state.license)); applyLicense(); toast('License checked'); } }, 'Check now'),
      h('button', { class: 'btn btn-danger', onclick: async () => {
        try { state.license = await invoke('license_deactivate'); applyLicense(); toast('This computer was deactivated. You can use your key on another one.'); }
        catch (e) { handleError(e); }
      } }, 'Deactivate this computer')));
  }
}

function applyLicense() {
  const L = state.license, e = ent();
  const pro = L && (L.status === 'active' || L.status === 'grace');
  $('#plan-name').textContent = `${e.plan} plan`;
  $('#plan-cta').textContent = pro ? (L.status === 'grace' ? 'Offline — reconnect soon' : 'Manage license') : 'Upgrade to Pro';
  renderLicense();
  if (state.info) renderResult();
}
$('#plan-card').addEventListener('click', () => {
  const pro = state.license && ['active', 'grace'].includes(state.license.status);
  if (pro) show('settings'); else openUpgrade('', 'plan');
});

// ---- engine
async function refreshEngine() {
  try {
    const e = await invoke('engine_info');
    const ok = !!e.version;
    $('#engine-line').replaceChildren(h('span', { class: 'dot ' + (ok ? 'ok' : 'bad') }), h('span', null, ok ? 'Engine ready' : 'Engine missing'));
    $('#engine-detail').textContent = ok ? `yt-dlp ${e.version} · FFmpeg ${e.ffmpeg ? 'included' : 'missing'}` : 'The download engine could not start. Reinstall Vidloot.';
  } catch (err) { $('#engine-detail').textContent = String(err); }
}
$('#engine-update').addEventListener('click', async (ev) => {
  const b = ev.currentTarget; b.disabled = true; b.textContent = 'Updating…';
  try { toast(await invoke('update_engine')); refreshEngine(); }
  catch (e) { handleError(e); }
  finally { b.disabled = false; b.textContent = 'Update engine'; }
});

// ------------------------------------------------------------------ upgrade sheet

function openUpgrade(reason, _kind) {
  $('#up-reason').textContent = reason || `You're on the ${ent().plan} plan.`;
  $('#upgrade').hidden = false;
  $('#up-buy').hidden = !BUY_URL;
  (BUY_URL ? $('#up-buy') : $('#up-key')).focus();
}
const closeUpgrade = () => { $('#upgrade').hidden = true; };
$('#up-close').addEventListener('click', closeUpgrade);
$('#upgrade').addEventListener('click', (e) => { if (e.target.id === 'upgrade') closeUpgrade(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeUpgrade(); });
$('#up-buy').addEventListener('click', () => invoke('open_link', { url: BUY_URL }).catch(handleError));
$('#up-key').addEventListener('click', () => { closeUpgrade(); show('settings'); setTimeout(() => $('#key-input')?.focus(), 50); });

// ------------------------------------------------------------------ start

(async function init() {
  const [settings, license, jobs] = await Promise.all([invoke('get_settings'), invoke('license_status'), invoke('list_jobs')]);
  state.settings = settings; state.license = license;
  for (const j of jobs) state.jobs.set(j.id, j);
  applyLicense();
  renderResult();
  renderQueue();
  show('download');
  refreshEngine();
  checkClipboard();
  // Re-check the license in the background now and then (the SDK decides when the server is actually called).
  setInterval(async () => { const L = await invoke('license_refresh').catch(() => null); if (L) { state.license = L; applyLicense(); } }, 30 * 60 * 1000);
})();
