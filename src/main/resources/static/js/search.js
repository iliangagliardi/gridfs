/**
 * search.js — the search bar, debounced autocomplete with keyboard navigation,
 * fuzzy toggle, facet pills, result rendering, and the pipeline explain panel.
 *
 * State lives in `state` below; every path funnels through runSearch() so the
 * request sent to the server always reflects exactly what is on screen.
 */

import * as api from './api.js';
import {
  $, $$, el, icon, num, bytesStr, relTime, debounce,
  sanitizeSnippet, categoryIcon, CATEGORIES, pretty,
} from './util.js';
import { toastApiError, emptyState } from './ui.js';

const PAGE_SIZE = 20;

const state = {
  query: '',
  categories: new Set(),
  fuzzy: false,
  page: 0,
  total: 0,
  loaded: 0,
};

/** Opens the viewer; injected by app.js to avoid a circular import. */
let openViewer = () => {};

/* ── Explain panel ───────────────────────────────────────────────────────── */

/**
 * Light-touch syntax colouring for the pretty-printed pipeline.
 *
 * We escape only the characters that would break out of HTML text (& < >) and
 * deliberately leave quotes intact, because the token regex keys off them.
 */
function highlightPipeline(src) {
  const escaped = String(src)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  return escaped.replace(
    /("(?:[^"\\]|\\.)*")(\s*:)?|(-?\b\d+(?:\.\d+)?\b)|\b(true|false|null)\b/g,
    (match, str, colon, number, bool) => {
      if (str) {
        if (colon) {
          // A key. Keys beginning with $ are aggregation stages/operators.
          const cls = /^"\$/.test(str) ? 'tok-stage' : 'tok-key';
          return `<span class="${cls}">${str}</span><span class="tok-punct">${colon}</span>`;
        }
        return `<span class="tok-str">${str}</span>`;
      }
      if (number) return `<span class="tok-num">${number}</span>`;
      if (bool) return `<span class="tok-bool">${bool}</span>`;
      return match;
    }
  );
}

function renderExplain(explain, mode) {
  const panel = $('#explain-panel');
  const code = $('#explain-code');
  if (!explain) { panel.hidden = true; return; }

  panel.hidden = false;
  code.innerHTML = highlightPipeline(explain);

  // Name the engine on the summary so the panel reads as a feature.
  const hint = panel.querySelector('.explain__hint');
  if (hint) hint.textContent = mode === 'ATLAS_SEARCH' ? '$search' : '$match + $regex';
}

/* ── Facets ──────────────────────────────────────────────────────────────── */

function renderFacets(facets = {}) {
  const host = $('#facets');
  host.innerHTML = '';

  // Always show a pill for a selected category, even if its count dropped to 0,
  // so the user can always switch the filter back off.
  const shown = CATEGORIES.filter(
    (c) => Number(facets[c] || 0) > 0 || state.categories.has(c)
  );
  if (!shown.length) return;

  for (const cat of shown) {
    const on = state.categories.has(cat);
    const pill = el('button', {
      type: 'button',
      class: 'pill',
      'aria-pressed': String(on),
    }, [
      icon(categoryIcon(cat), 'icon'),
      pretty(cat),
      el('span', { class: 'pill__n', text: num(facets[cat] || 0) }),
    ]);
    pill.querySelector('.icon').style.width = '13px';
    pill.querySelector('.icon').style.height = '13px';

    pill.addEventListener('click', () => {
      if (state.categories.has(cat)) state.categories.delete(cat);
      else state.categories.add(cat);
      state.page = 0;
      runSearch();
    });

    host.append(pill);
  }
}

/* ── Results ─────────────────────────────────────────────────────────────── */

function resultRow(f) {
  const meta = el('div', { class: 'result__meta' }, [
    el('span', { text: bytesStr(f.length) }),
    el('span', { text: relTime(f.uploadDate) }),
    f.contentType ? el('span', { text: f.contentType }) : null,
    f.textLength ? el('span', { text: `${num(f.textLength)} chars indexed` }) : null,
  ]);

  const main = el('div', { class: 'result__main' }, [
    el('div', { class: 'result__name', text: f.filename }),
    meta,
  ]);

  // The snippet arrives with <mark> tags from Atlas Search highlighting and is
  // rendered as HTML, with everything except <mark> escaped. See util.js.
  if (f.snippet) {
    main.append(el('p', { class: 'result__snippet', html: sanitizeSnippet(f.snippet) }));
  }

  const row = el('div', {
    class: 'result', role: 'button', tabindex: '0',
    'data-id': f.id,
    'aria-label': `Open ${f.filename}`,
  }, [
    el('div', { class: 'result__icon' }, [icon(categoryIcon(f.category))]),
    main,
    f.score != null ? el('span', {
      class: 'result__score',
      title: 'Atlas Search relevance score',
      text: Number(f.score).toFixed(2),
    }) : null,
  ]);

  const open = () => openViewer(f.id);
  row.addEventListener('click', open);
  row.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
  });

  return row;
}

function renderStatus(res) {
  const host = $('#search-status');
  host.innerHTML = '';
  if (!res) return;

  host.append(el('span', {}, [
    el('b', { text: num(res.total) }),
    res.total === 1 ? ' match' : ' matches',
  ]));
  host.append(el('span', { class: 'took', text: `${num(res.tookMillis)} ms` }));
  host.append(el('span', { text: res.mode === 'ATLAS_SEARCH' ? '$search' : 'regex fallback' }));
}

/* ── The search request ──────────────────────────────────────────────────── */

/** True when the user has actually asked for something. */
function isActive() {
  return state.query.trim().length > 0 || state.categories.size > 0;
}

/**
 * Run a search and paint everything that depends on it.
 * @param {boolean} append - true when loading the next page
 */
export async function runSearch(append = false) {
  const results = $('#results');
  const active = isActive();

  if (!append) state.page = 0;

  try {
    const res = await api.search({
      query: state.query,
      categories: Array.from(state.categories),
      fuzzy: state.fuzzy,
      page: state.page,
      size: PAGE_SIZE,
    });

    // Facet counts come from the same aggregation, so they are always in sync.
    renderFacets(res.facets);

    if (!active) {
      // Browse mode: the file grid below already lists everything, so the
      // results area stays quiet and only the facet counts are kept warm.
      results.innerHTML = '';
      $('#search-status').innerHTML = '';
      $('#explain-panel').hidden = true;
      state.total = 0;
      state.loaded = 0;
      return res;
    }

    renderStatus(res);
    renderExplain(res.explain, res.mode);

    if (!append) { results.innerHTML = ''; state.loaded = 0; }

    state.total = res.total;
    state.loaded += res.results.length;

    if (!res.results.length && !append) {
      results.append(emptyState(
        'No matches',
        state.fuzzy
          ? 'Try a shorter term, or clear the category filters.'
          : 'Turn on Fuzzy to tolerate typos, or clear the category filters.'
      ));
      return res;
    }

    for (const f of res.results) results.append(resultRow(f));

    // Offer the next page inline rather than paginating a relevance ranking.
    if (state.loaded < state.total) {
      const more = el('button', {
        type: 'button', class: 'btn',
        text: `Show more (${num(state.total - state.loaded)} left)`,
        style: 'align-self:center',
      });
      more.addEventListener('click', () => {
        more.remove();
        state.page += 1;
        runSearch(true);
      });
      results.append(more);
    }

    return res;
  } catch (err) {
    toastApiError('Search failed', err);
    return null;
  }
}

/* ── Autocomplete ────────────────────────────────────────────────────────── */

const ac = {
  items: [],
  index: -1,
  controller: null,
};

function closeAc() {
  const list = $('#ac-list');
  list.hidden = true;
  list.innerHTML = '';
  ac.items = [];
  ac.index = -1;
  $('#search-input').setAttribute('aria-expanded', 'false');
  $('#search-input').removeAttribute('aria-activedescendant');
}

function highlightAc() {
  const nodes = $$('.ac__item', $('#ac-list'));
  nodes.forEach((n, i) => n.setAttribute('aria-selected', String(i === ac.index)));
  const current = nodes[ac.index];
  if (current) {
    current.scrollIntoView({ block: 'nearest' });
    $('#search-input').setAttribute('aria-activedescendant', current.id);
  }
}

function choose(value) {
  const input = $('#search-input');
  input.value = value;
  state.query = value;
  closeAc();
  runSearch();
}

function renderAc(suggestions) {
  const list = $('#ac-list');
  list.innerHTML = '';

  if (!suggestions.length) { closeAc(); return; }

  ac.items = suggestions;
  ac.index = -1;

  suggestions.forEach((s, i) => {
    const item = el('li', {
      class: 'ac__item',
      id: `ac-opt-${i}`,
      role: 'option',
      'aria-selected': 'false',
    }, [icon('i-search'), s]);

    // mousedown, not click: it fires before the input's blur closes the list.
    item.addEventListener('mousedown', (e) => { e.preventDefault(); choose(s); });
    item.addEventListener('mouseenter', () => { ac.index = i; highlightAc(); });
    list.append(item);
  });

  list.hidden = false;
  $('#search-input').setAttribute('aria-expanded', 'true');
}

const fetchSuggestions = debounce(async (q) => {
  if (q.trim().length < 2) { closeAc(); return; }

  // Cancel the in-flight request so out-of-order responses cannot win.
  ac.controller?.abort();
  ac.controller = new AbortController();

  try {
    const suggestions = await api.autocomplete(q, 8, ac.controller.signal);
    renderAc(Array.isArray(suggestions) ? suggestions : []);
  } catch (err) {
    if (err.name !== 'AbortError') closeAc();
  }
}, 180);

/* ── Wiring ──────────────────────────────────────────────────────────────── */

const debouncedSearch = debounce(() => runSearch(), 260);

function initSearchBar() {
  const input = $('#search-input');

  input.addEventListener('input', () => {
    state.query = input.value;
    fetchSuggestions(input.value);
    debouncedSearch();
  });

  input.addEventListener('keydown', (e) => {
    const open = !$('#ac-list').hidden;

    switch (e.key) {
      case 'ArrowDown':
        if (!open) return;
        e.preventDefault();
        ac.index = (ac.index + 1) % ac.items.length;
        highlightAc();
        break;

      case 'ArrowUp':
        if (!open) return;
        e.preventDefault();
        ac.index = ac.index <= 0 ? ac.items.length - 1 : ac.index - 1;
        highlightAc();
        break;

      case 'Enter':
        e.preventDefault();
        if (open && ac.index >= 0) choose(ac.items[ac.index]);
        else { closeAc(); debouncedSearch.cancel(); runSearch(); }
        break;

      case 'Escape':
        if (open) { e.preventDefault(); closeAc(); }
        else if (input.value) {
          input.value = '';
          state.query = '';
          runSearch();
        }
        break;
    }
  });

  input.addEventListener('blur', () => setTimeout(closeAc, 120));

  $('#fuzzy-toggle').addEventListener('change', (e) => {
    state.fuzzy = e.target.checked;
    runSearch();
  });

  // Slash focuses search, the convention for search-led tools.
  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && !/^(INPUT|TEXTAREA)$/.test(document.activeElement?.tagName)) {
      e.preventDefault();
      input.focus();
    }
  });
}

export function initSearch({ onOpenFile } = {}) {
  openViewer = onOpenFile || (() => {});
  initSearchBar();
  // Prime the facet counts without showing a result list.
  runSearch();
}

/** Re-run whatever is currently on screen (after an upload or delete). */
export function refreshSearch() {
  return runSearch();
}

/**
 * Reflect a renamed file in the result list without re-running the query.
 *
 * Deliberately patches the row rather than rebuilding it: the snippet and the
 * relevance score belong to the search response, not to the StoredFile that
 * PATCH /metadata returns, and rebuilding would throw both away.
 */
export function updateSearchResult(f) {
  const row = $(`.result[data-id="${CSS.escape(f.id)}"]`);
  if (!row) return;
  const name = row.querySelector('.result__name');
  if (name) name.textContent = f.filename;
  row.setAttribute('aria-label', `Open ${f.filename}`);
}
