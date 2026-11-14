/* CrewParty — application state, rendering and event wiring. */
(function () {
  'use strict';

  var THEMES = ['Board Games', 'Co-op Video Games', 'Coworking & Chill', 'Trivia & Social', 'Outdoor Casual'];
  var CITIES = ['Chicago', 'Austin', 'Seattle', 'Washington, D.C.'];
  var VIBE_TAGS = ['Great Wi-Fi', 'Budget Friendly', 'Quiet Enough to Talk', 'Great Table Space', 'Craft Drinks', 'Late Night'];
  var TABS = ['explore', 'friends', 'history', 'venues'];
  var PRO_KEY = 'crewparty.pro';

  var THEME_HINTS = {
    'Great Wi-Fi': 'Coworking & Chill',
    'Budget Friendly': 'Board Games',
    'Quiet Enough to Talk': 'Trivia & Social',
    'Great Table Space': 'Board Games',
    'Craft Drinks': 'Trivia & Social',
    'Late Night': 'Co-op Video Games'
  };

  var state = {
    friends: [],
    venues: [],
    openParties: [],
    visitHistory: [],
    results: { parties: [], players: [], venues: [] },
    filters: { city: 'All Cities', theme: '', maxBudget: 0 },
    venueCity: 'All Cities',
    venueSort: 'rating',
    tab: 'explore',
    pro: false,
    addedIds: {},          // friendId -> true
    drafts: {},            // visitId -> { rating: number, tags: string[] }
    chooser: null,         // { mode: 'join'|'invite', partyId?, friendId? }
    highlightVisitId: null,
    loaded: false
  };

  /* ---------------- helpers ---------------- */

  function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function attr(value) {
    return String(value === null || value === undefined ? '' : value).replace(/["\\]/g, '');
  }

  function el(id) {
    return document.getElementById(id);
  }

  function val(id) {
    var node = el(id);
    return node ? node.value : '';
  }

  function num(value, fallback) {
    var parsed = parseInt(value, 10);
    return isNaN(parsed) ? fallback : parsed;
  }

  function count(n, noun) {
    return n + ' ' + noun + (n === 1 ? '' : 's');
  }

  function initials(name) {
    var parts = String(name || '?').trim().split(/\s+/).slice(0, 2);
    return parts.map(function (p) { return p.charAt(0).toUpperCase(); }).join('');
  }

  function starGlyphs(rating) {
    var out = '';
    for (var i = 1; i <= 5; i++) {
      out += i <= rating ? '★' : '<span class="off">★</span>';
    }
    return out;
  }

  function themeChips(themes) {
    return (themes || []).map(function (t) {
      return '<span class="chip">' + escapeHtml(t) + '</span>';
    }).join('');
  }

  function emptyState(title, text) {
    return '<div class="empty"><span class="empty-title">' + escapeHtml(title) + '</span>' + escapeHtml(text) + '</div>';
  }

  function loading(text) {
    return '<div class="loading">' + escapeHtml(text) + '</div>';
  }

  function findParty(id) {
    var all = state.results.parties.concat(state.openParties);
    for (var i = 0; i < all.length; i++) {
      if (String(all[i].id) === String(id)) return all[i];
    }
    return null;
  }

  function findFriend(id) {
    for (var i = 0; i < state.friends.length; i++) {
      if (String(state.friends[i].id) === String(id)) return state.friends[i];
    }
    return null;
  }

  function friendByName(name) {
    var target = String(name || '').trim().toLowerCase();
    if (!target) return null;
    for (var i = 0; i < state.friends.length; i++) {
      if (String(state.friends[i].name || '').trim().toLowerCase() === target) return state.friends[i];
    }
    return null;
  }

  function venuesForCity(city) {
    if (!city || city === 'All Cities') return state.venues;
    var filtered = state.venues.filter(function (v) { return v.city === city; });
    return filtered.length ? filtered : state.venues;
  }

  /* ---------------- toasts ---------------- */

  function showToast(message, type) {
    var area = el('toast-area');
    if (!area) return;
    var toast = document.createElement('div');
    toast.className = 'toast toast-' + (type || 'info');
    toast.textContent = message;
    area.appendChild(toast);
    var life = type === 'error' ? 6500 : 4200;
    window.setTimeout(function () {
      toast.classList.add('toast-out');
      window.setTimeout(function () { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 320);
    }, life);
  }

  /* ---------------- modals ---------------- */

  function openModal(id) {
    var modal = el(id);
    if (!modal) return;
    modal.classList.add('open');
    document.body.classList.add('modal-open');
    var focusable = modal.querySelector('input, select, button');
    if (focusable) window.setTimeout(function () { focusable.focus(); }, 40);
  }

  function closeModalFrom(node) {
    var modal = node && node.closest ? node.closest('.modal') : null;
    if (modal) modal.classList.remove('open');
    if (!document.querySelector('.modal.open')) document.body.classList.remove('modal-open');
  }

  function closeAllModals() {
    Array.prototype.forEach.call(document.querySelectorAll('.modal.open'), function (m) {
      m.classList.remove('open');
    });
    document.body.classList.remove('modal-open');
  }

  /* ---------------- data loading ---------------- */

  function applyBootstrap(data) {
    state.friends = data.friends || [];
    state.venues = data.venues || [];
    state.openParties = data.openParties || [];
    state.visitHistory = data.visitHistory || [];
    state.results = {
      parties: state.openParties.slice(),
      players: state.friends.filter(function (f) { return f.status === 'DISCOVER'; }),
      venues: state.venues.slice()
    };
  }

  function reloadData() {
    return window.CrewApi.bootstrap().then(function (data) {
      applyBootstrap(data);
      return data;
    });
  }

  function setLoadingPlaceholders() {
    el('party-list').innerHTML = loading('Loading open parties…');
    el('player-list').innerHTML = loading('Loading players…');
    el('venue-mini-list').innerHTML = loading('Loading venues…');
    el('friends-list').innerHTML = loading('Loading your crew…');
    el('history-list').innerHTML = loading('Loading visit history…');
    el('venue-list').innerHTML = loading('Loading venues…');
  }

  function loadBootstrap() {
    setLoadingPlaceholders();
    return reloadData().then(function () {
      state.loaded = true;
      renderAll();
    }).catch(function (err) {
      state.loaded = true;
      renderAll();
      showToast('Could not load CrewParty data: ' + err.message, 'error');
    });
  }

  /* ---------------- rendering: parties ---------------- */

  function partyCard(p) {
    var full = p.filledSlots >= p.totalSlots;
    var fit = Math.max(0, Math.min(100, num(p.fitScore, 0)));
    var members = (p.members || []).map(function (m) {
      return '<span class="chip">' + escapeHtml(m) + '</span>';
    }).join('');
    var icebreakers = (p.icebreakers || []).map(function (i) {
      return '<li>' + escapeHtml(i) + '</li>';
    }).join('');

    return '' +
      '<article class="card party-card" data-party-id="' + escapeHtml(p.id) + '">' +
        '<div class="card-head">' +
          '<h3 class="card-title">' + escapeHtml(p.title) + '</h3>' +
          '<span class="badge badge-theme">' + escapeHtml(p.theme) + '</span>' +
        '</div>' +
        '<div class="meta-row">' +
          '<span>' + escapeHtml(p.city) + ' • ' + escapeHtml(p.neighborhood) + '</span>' +
          '<span class="dot">•</span>' +
          '<span>' + escapeHtml(p.timeLabel) + '</span>' +
        '</div>' +
        '<div class="meta-row">' +
          '<span class="price">$' + escapeHtml(p.budgetMin) + '-$' + escapeHtml(p.budgetMax) + '</span>' +
          '<span class="dot">•</span>' +
          '<span>' + escapeHtml(p.filledSlots) + '/' + escapeHtml(p.totalSlots) + ' players</span>' +
        '</div>' +
        '<div class="fit">' +
          '<div class="fit-label"><span>Match Fit Score</span><strong>' + fit + '%</strong></div>' +
          '<div class="fit-bar"><span style="width:' + fit + '%"></span></div>' +
        '</div>' +
        '<div class="venue-line">Venue: <strong>' + escapeHtml(p.venueName) + '</strong></div>' +
        '<div class="chips">' + members + '</div>' +
        (icebreakers ? '<div class="icebreakers"><div class="icebreakers-label">AI Icebreakers</div><ul>' + icebreakers + '</ul></div>' : '') +
        '<div class="card-actions">' +
          '<button class="btn btn-primary" data-action="join-party" data-party-id="' + escapeHtml(p.id) + '"' + (full ? ' disabled' : '') + '>' +
            (full ? 'Full' : 'Join Party') +
          '</button>' +
          '<button class="btn btn-accent" data-action="complete-party" data-party-id="' + escapeHtml(p.id) + '">Complete &amp; Rate Visit</button>' +
        '</div>' +
      '</article>';
  }

  function renderParties() {
    var host = el('party-list');
    var parties = state.results.parties;
    if (!parties.length) {
      host.innerHTML = emptyState('No open parties yet', 'Create one with "+ Create Party" and your crew will show up here.');
      return;
    }
    host.innerHTML = parties.map(partyCard).join('');
  }

  /* ---------------- rendering: players & friends ---------------- */

  function playerCard(f) {
    var added = !!state.addedIds[f.id];
    return '' +
      '<article class="card player-card" data-friend-id="' + escapeHtml(f.id) + '">' +
        '<div class="avatar">' + escapeHtml(initials(f.name)) + '</div>' +
        '<div class="player-body">' +
          '<div class="player-name">' + escapeHtml(f.name) + (f.verified ? '<span class="verified" title="Verified">✓</span>' : '') + '</div>' +
          '<div class="player-role">' + escapeHtml(f.ageRole) + '</div>' +
          '<div class="player-city">' + escapeHtml(f.city) + '</div>' +
          '<div class="chips">' + themeChips(f.themes) + '</div>' +
        '</div>' +
        '<div class="player-actions">' +
          '<button class="btn btn-primary btn-sm" data-action="add-friend" data-friend-id="' + escapeHtml(f.id) + '"' + (added ? ' disabled' : '') + '>' +
            (added ? 'Added' : 'Add Friend') +
          '</button>' +
          '<button class="btn btn-ghost btn-sm" data-action="invite-player" data-friend-id="' + escapeHtml(f.id) + '">Invite</button>' +
        '</div>' +
      '</article>';
  }

  function visiblePlayers() {
    return state.results.players.filter(function (p) {
      return p.status === 'DISCOVER' || state.addedIds[p.id];
    });
  }

  function renderPlayers() {
    var host = el('player-list');
    var players = visiblePlayers();
    if (!players.length) {
      host.innerHTML = emptyState('No matching players right now', 'Try another theme or city, or hit Reset to see everyone.');
      return;
    }
    host.innerHTML = players.map(playerCard).join('');
  }

  function friendCard(f) {
    var added = !!state.addedIds[f.id] || f.status !== 'DISCOVER';
    var action = f.status === 'DISCOVER'
      ? '<div class="friend-actions"><button class="btn btn-primary btn-sm" data-action="add-friend" data-friend-id="' + escapeHtml(f.id) + '"' + (added ? ' disabled' : '') + '>' + (added ? 'Added' : 'Add Friend') + '</button></div>'
      : '';
    return '' +
      '<article class="card friend-card" data-friend-id="' + escapeHtml(f.id) + '">' +
        '<div class="avatar">' + escapeHtml(initials(f.name)) + '</div>' +
        '<div class="friend-body">' +
          '<div class="friend-name">' + escapeHtml(f.name) + (f.verified ? '<span class="verified" title="Verified">✓</span>' : '') + '</div>' +
          '<div class="friend-role">' + escapeHtml(f.ageRole) + '</div>' +
          '<div class="friend-city">' + escapeHtml(f.city) + ' • ' + escapeHtml(f.status === 'DISCOVER' ? 'Suggested' : 'Saved friend') + '</div>' +
          '<div class="chips">' + themeChips(f.themes) + '</div>' +
        '</div>' +
        action +
      '</article>';
  }

  function renderFriends() {
    var coreHost = el('core-buddy-card');
    var gridHost = el('friends-list');

    var cores = state.friends.filter(function (f) { return f.status === 'CORE_BUDDY'; });
    coreHost.innerHTML = cores.map(function (f) {
      return '<div class="core-label">Your Core Buddy</div>' + friendCard(f);
    }).join('');

    if (!state.friends.length) {
      gridHost.innerHTML = emptyState('Your crew is empty', 'Add players from Explore & Search to start building your crew.');
      return;
    }

    var rest = state.friends.filter(function (f) { return f.status !== 'CORE_BUDDY'; });
    gridHost.innerHTML = rest.length
      ? rest.map(friendCard).join('')
      : emptyState('No saved friends yet', 'Add a player from Explore & Search and they will appear here.');
  }

  /* ---------------- rendering: visit history ---------------- */

  function sortedHistory() {
    return state.visitHistory.slice().sort(function (a, b) {
      var ta = Date.parse(a.visitedDate) || 0;
      var tb = Date.parse(b.visitedDate) || 0;
      return tb - ta;
    });
  }

  function ratingForm(v) {
    var draft = state.drafts[v.id] || { rating: 0, tags: [] };
    var stars = [1, 2, 3, 4, 5].map(function (n) {
      return '<button type="button" class="star' + (n <= draft.rating ? ' is-selected' : '') + '"' +
        ' data-action="star-pick" data-value="' + n + '"' +
        ' role="radio" aria-checked="' + (n === draft.rating) + '"' +
        ' aria-label="' + n + ' star' + (n === 1 ? '' : 's') + '">★</button>';
    }).join('');

    var tags = VIBE_TAGS.map(function (t) {
      var active = draft.tags.indexOf(t) !== -1;
      return '<button type="button" class="chip chip-btn' + (active ? ' chip-active' : '') + '"' +
        ' data-action="toggle-tag" data-tag="' + escapeHtml(t) + '"' +
        ' aria-pressed="' + active + '">' + escapeHtml(t) + '</button>';
    }).join('');

    return '' +
      '<div class="rating-form" data-visit-id="' + escapeHtml(v.id) + '">' +
        '<div class="rating-label">Rate this venue</div>' +
        '<div class="star-row" role="radiogroup" aria-label="Venue rating">' + stars +
          '<span class="star-value">' + (draft.rating ? draft.rating + ' / 5' : 'Not rated') + '</span>' +
        '</div>' +
        '<div class="tag-row">' + tags + '</div>' +
        '<textarea class="textarea" id="comment-' + escapeHtml(v.id) + '" rows="3" placeholder="How was the venue? Any tips for the crew?"></textarea>' +
        '<div class="card-actions">' +
          '<button class="btn btn-primary" data-action="submit-rating" data-visit-id="' + escapeHtml(v.id) + '">Submit Venue Rating</button>' +
        '</div>' +
      '</div>';
  }

  function ratedSummary(v) {
    var tags = (v.selectedTags || []).map(function (t) {
      return '<span class="chip">' + escapeHtml(t) + '</span>';
    }).join('');
    return '' +
      '<div class="rated-summary">' +
        '<div class="stars-static">' + starGlyphs(v.rating || 0) + '</div>' +
        '<div class="rated-meta">Rated ' + escapeHtml(v.rating) + ' out of 5 on ' + escapeHtml(v.visitedDate) + '</div>' +
        (tags ? '<div class="chips">' + tags + '</div>' : '') +
        (v.reviewComment ? '<p class="rated-comment">"' + escapeHtml(v.reviewComment) + '"</p>' : '') +
      '</div>';
  }

  function attendeeChips(v) {
    return (v.attendees || []).map(function (name) {
      var friend = friendByName(name);
      var canAdd = friend && friend.status === 'DISCOVER' && !state.addedIds[friend.id];
      return '<span class="attendee">' + escapeHtml(name) +
        (canAdd ? '<button class="chip-add" data-action="add-friend" data-friend-id="' + escapeHtml(friend.id) + '">+ Add to Friends</button>' : '') +
        '</span>';
    }).join('');
  }

  function historyCard(v) {
    var highlighted = state.highlightVisitId && String(state.highlightVisitId) === String(v.id);
    return '' +
      '<article class="card history-card' + (highlighted ? ' highlight' : '') + '" id="history-card-' + escapeHtml(v.id) + '">' +
        '<div class="card-head">' +
          '<h3 class="card-title">' + escapeHtml(v.partyTitle) + '</h3>' +
          '<span class="badge badge-theme">' + escapeHtml(v.theme) + '</span>' +
        '</div>' +
        '<div class="meta-row">' +
          '<span>Venue: <strong>' + escapeHtml(v.venueName) + '</strong></span>' +
          '<span class="dot">•</span>' +
          '<span>' + escapeHtml(v.visitedDate) + '</span>' +
        '</div>' +
        '<div class="attendees">' + attendeeChips(v) + '</div>' +
        (v.rating ? ratedSummary(v) : ratingForm(v)) +
      '</article>';
  }

  function renderHistory() {
    var host = el('history-list');
    var visits = sortedHistory();
    if (!visits.length) {
      host.innerHTML = emptyState('No visits logged yet', 'Finish a party with "Complete & Rate Visit" and it will land here for a rating.');
      return;
    }
    host.innerHTML = visits.map(historyCard).join('');
  }

  /* ---------------- rendering: venues ---------------- */

  function venueCard(v) {
    var rating = typeof v.avgRating === 'number' ? v.avgRating.toFixed(1) : '—';
    var count = num(v.ratingCount, 0);
    return '' +
      '<article class="card venue-card" data-venue-id="' + escapeHtml(v.id) + '">' +
        '<div class="card-head">' +
          '<h3 class="card-title">' + escapeHtml(v.name) + '</h3>' +
          '<span class="badge badge-city">' + escapeHtml(v.city) + '</span>' +
        '</div>' +
        '<div class="venue-cat">' + escapeHtml(v.category) + ' • ' + escapeHtml(v.neighborhood) + '</div>' +
        '<div class="meta-row">' +
          '<span class="rating-live">' + rating + ' ★ (' + count + ' rating' + (count === 1 ? '' : 's') + ')</span>' +
          '<span class="dot">•</span>' +
          '<span class="price">$' + escapeHtml(v.avgCostUsd) + '</span>' +
        '</div>' +
        '<div class="chips">' + themeChips(v.tags) + '</div>' +
        '<div class="theme-hint">' + escapeHtml(themeHint(v)) + '</div>' +
      '</article>';
  }

  function themeHint(v) {
    var hits = [];
    (v.tags || []).forEach(function (t) {
      var theme = THEME_HINTS[t];
      if (theme && hits.indexOf(theme) === -1) hits.push(theme);
    });
    if (!hits.length) return 'Best for: ' + (v.category || 'Casual hangs');
    return 'Best for: ' + hits.join(', ');
  }

  function renderVenueMini() {
    var host = el('venue-mini-list');
    var venues = state.results.venues;
    if (!venues.length) {
      host.innerHTML = emptyState('No venues found', 'Change the theme or budget filter and search again.');
      return;
    }
    host.innerHTML = venues.map(venueCard).join('');
  }

  function sortedVenues() {
    var list = state.results.venues.filter(function (v) {
      return state.venueCity === 'All Cities' || v.city === state.venueCity;
    });
    list.sort(function (a, b) {
      if (state.venueSort === 'price') {
        return (a.avgCostUsd - b.avgCostUsd) || (b.avgRating - a.avgRating);
      }
      return (b.avgRating - a.avgRating) || (a.avgCostUsd - b.avgCostUsd);
    });
    return list;
  }

  function renderVenues() {
    var host = el('venue-list');
    var venues = sortedVenues();
    if (!venues.length) {
      host.innerHTML = emptyState('No venues in this city', 'Pick another city or reset the filter to see every spot.');
      return;
    }
    host.innerHTML = venues.map(venueCard).join('');
  }

  /* ---------------- rendering: all ---------------- */

  function renderAll() {
    renderParties();
    renderPlayers();
    renderVenueMini();
    renderFriends();
    renderHistory();
    renderVenues();
    applyProState();
    if (window.CrewMap) window.CrewMap.renderVenues(state.results.venues);
  }

  function applyProState() {
    var btn = el('upgrade-btn');
    if (!btn) return;
    if (state.pro) {
      btn.classList.add('is-pro');
      btn.textContent = 'Pro';
      btn.setAttribute('aria-label', 'CrewParty Pro is active');
    } else {
      btn.classList.remove('is-pro');
      btn.textContent = 'Upgrade ($9.99/mo)';
      btn.removeAttribute('aria-label');
    }
  }

  /* ---------------- tabs ---------------- */

  function switchTab(tab) {
    if (TABS.indexOf(tab) === -1) return;
    state.tab = tab;
    TABS.forEach(function (name) {
      var view = el('view-' + name);
      if (view) view.classList.toggle('active', name === tab);
    });
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (btn) {
      btn.classList.toggle('active', btn.dataset.tab === tab);
    });
    if (tab === 'explore' && window.CrewMap) {
      window.requestAnimationFrame(function () { window.CrewMap.init('venue-map'); });
    }
    if (tab === 'history' && state.highlightVisitId) {
      focusVisit(state.highlightVisitId);
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function focusVisit(visitId) {
    if (!visitId) return;
    window.setTimeout(function () {
      var card = el('history-card-' + visitId);
      if (card) card.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 80);
  }

  /* ---------------- filters & search ---------------- */

  function readFilters() {
    state.filters.city = val('global-city') || 'All Cities';
    state.filters.theme = val('filter-theme') || '';
    state.filters.maxBudget = num(val('filter-budget'), 0);
  }

  function doSearch() {
    readFilters();
    el('party-list').innerHTML = loading('Searching parties…');
    el('player-list').innerHTML = loading('Searching players…');
    el('venue-mini-list').innerHTML = loading('Searching venues…');
    return window.CrewApi.search(state.filters).then(function (res) {
      state.results = {
        parties: res.parties || [],
        players: res.players || [],
        venues: res.venues || []
      };
      renderAll();
      showToast('Found ' + state.results.parties.length + ' open ' +
        (state.results.parties.length === 1 ? 'party' : 'parties') + ' and ' +
        count(state.results.venues.length, 'venue') + ' in ' + state.filters.city + '.', 'success');
    }).catch(function (err) {
      renderAll();
      showToast('Search failed: ' + err.message, 'error');
    });
  }

  function resetFilters() {
    el('global-city').value = 'All Cities';
    el('filter-theme').value = '';
    el('filter-budget').value = '0';
    showToast('Filters reset to All Cities, All Themes, no budget limit.', 'info');
    return doSearch();
  }

  /* ---------------- create party ---------------- */

  function venueOptionsHtml(city) {
    var venues = venuesForCity(city);
    if (!venues.length) return '<option value="">No venues available</option>';
    return venues.map(function (v) {
      return '<option value="' + escapeHtml(v.id) + '">' + escapeHtml(v.name) + ' — ' + escapeHtml(v.neighborhood) + ' ($' + escapeHtml(v.avgCostUsd) + ')</option>';
    }).join('');
  }

  function fillVenueSelect(city) {
    el('create-venue').innerHTML = venueOptionsHtml(city);
  }

  function buddyOptionsHtml(checkedIds) {
    var ids = checkedIds || [];
    var buddies = state.friends.filter(function (f) {
      return f.status === 'CORE_BUDDY' || f.status === 'FRIEND';
    });
    if (!buddies.length) return '<p class="hint">No saved buddies yet. Add players from Explore &amp; Search first.</p>';
    return buddies.map(function (f) {
      var checked = ids.indexOf(f.id) !== -1 ? ' checked' : '';
      return '<label class="check-row"><input type="checkbox" data-buddy value="' + escapeHtml(f.id) + '"' + checked + ' />' +
        '<span>' + escapeHtml(f.name) + ' <em>' + escapeHtml(f.ageRole || '') + '</em></span></label>';
    }).join('');
  }

  function openCreateModal(options) {
    var opts = options || {};
    var citySelect = el('create-city');
    var themeSelect = el('create-theme');

    themeSelect.innerHTML = THEMES.map(function (t) {
      return '<option value="' + escapeHtml(t) + '">' + escapeHtml(t) + '</option>';
    }).join('');
    citySelect.innerHTML = CITIES.map(function (c) {
      return '<option value="' + escapeHtml(c) + '">' + escapeHtml(c) + '</option>';
    }).join('');

    var city = opts.city || (state.filters.city !== 'All Cities' ? state.filters.city : (CITIES.indexOf('Chicago') !== -1 ? 'Chicago' : CITIES[0]));
    citySelect.value = city;

    var chosenVenues = venuesForCity(city);
    var venueId = opts.venueId || (chosenVenues[0] ? chosenVenues[0].id : '');
    fillVenueSelect(city);
    el('create-venue').value = venueId;

    var venue = state.venues.filter(function (v) { return String(v.id) === String(venueId); })[0];

    el('create-title').value = opts.title || '';
    themeSelect.value = opts.theme && THEMES.indexOf(opts.theme) !== -1 ? opts.theme : THEMES[0];
    el('create-neighborhood').value = opts.neighborhood || (venue ? venue.neighborhood : '');
    el('create-slots').value = opts.totalSlots || 4;
    el('create-budget-min').value = typeof opts.budgetMin === 'number' ? opts.budgetMin : 10;
    el('create-budget-max').value = typeof opts.budgetMax === 'number' ? opts.budgetMax : 30;
    el('create-time').value = opts.timeLabel || '';
    el('create-buddies').innerHTML = buddyOptionsHtml(opts.buddyIds || []);

    openModal('create-modal');
  }

  function checkedBuddyIds() {
    return Array.prototype.map.call(
      document.querySelectorAll('#create-buddies input[data-buddy]:checked'),
      function (box) { return box.value; }
    );
  }

  function syncVenueDetails() {
    var venueId = val('create-venue');
    var venue = state.venues.filter(function (v) { return String(v.id) === String(venueId); })[0];
    if (!venue) return;
    if (venue.neighborhood) el('create-neighborhood').value = venue.neighborhood;
    if (venue.city) el('create-city').value = venue.city;
  }

  function submitCreate() {
    var slots = Math.max(2, Math.min(8, num(val('create-slots'), 4)));
    var min = Math.max(0, num(val('create-budget-min'), 0));
    var max = Math.max(0, num(val('create-budget-max'), 0));
    if (max < min) { var swap = min; min = max; max = swap; }

    var payload = {
      title: String(val('create-title') || '').trim(),
      theme: val('create-theme'),
      city: val('create-city'),
      neighborhood: String(val('create-neighborhood') || '').trim(),
      totalSlots: slots,
      budgetMin: min,
      budgetMax: max,
      timeLabel: String(val('create-time') || '').trim(),
      venueId: val('create-venue'),
      buddyIds: checkedBuddyIds()
    };

    if (!payload.title) { showToast('Please give your party a title.', 'error'); return; }
    if (!payload.venueId) { showToast('Please pick a venue for the party.', 'error'); return; }
    if (!payload.neighborhood) payload.neighborhood = payload.city;
    if (!payload.timeLabel) payload.timeLabel = 'This weekend';

    var submitBtn = document.querySelector('#create-form button[type="submit"]');
    if (submitBtn) submitBtn.disabled = true;

    window.CrewApi.createParty(payload).then(function (party) {
      closeAllModals();
      return reloadData().then(function () {
        renderAll();
        switchTab('explore');
        showToast('Party "' + party.title + '" is live. ' +
          Math.max(0, (party.totalSlots - party.filledSlots)) + ' slot(s) still open.', 'success');
        window.setTimeout(function () {
          var card = document.querySelector('.party-card[data-party-id="' + attr(party.id) + '"]');
          if (card) {
            card.scrollIntoView({ behavior: 'smooth', block: 'center' });
            card.classList.add('flash');
            window.setTimeout(function () { card.classList.remove('flash'); }, 1600);
          }
        }, 120);
      });
    }).catch(function (err) {
      showToast('Could not create party: ' + err.message, 'error');
    }).then(function () {
      if (submitBtn) submitBtn.disabled = false;
    });
  }

  function startPartyWithFriends() {
    var buddyIds = state.friends.filter(function (f) {
      return f.status === 'CORE_BUDDY' || f.status === 'FRIEND';
    }).map(function (f) { return f.id; });

    if (!buddyIds.length) {
      showToast('No saved buddies yet — add players from Explore & Search first.', 'error');
      return;
    }
    openCreateModal({ title: 'Crew Night with My Friends', buddyIds: buddyIds });
    showToast('Pick a theme and venue, then create your party.', 'info');
  }

  function runDemoPreset() {
    var city = state.filters.city !== 'All Cities' ? state.filters.city : 'Chicago';
    var buddies = state.friends.filter(function (f) {
      return f.status === 'CORE_BUDDY' || f.status === 'FRIEND';
    }).slice(0, 2).map(function (f) { return f.id; });
    var venue = venuesForCity(city)[0];

    openCreateModal({
      title: 'Board Game Night — 2 Buddies Seeking 2 Players',
      theme: 'Board Games',
      city: city,
      totalSlots: 4,
      budgetMin: 10,
      budgetMax: 30,
      timeLabel: 'Sat 3:00 PM',
      buddyIds: buddies,
      venueId: venue ? venue.id : ''
    });
    // True 1-click demo: the pre-filled form is submitted immediately. If the request fails the
    // modal stays open with the error, so the details can be fixed by hand.
    submitCreate();
  }

  /* ---------------- chooser (join / invite) ---------------- */

  function openJoinChooser(partyId) {
    var party = findParty(partyId);
    if (!party) { showToast('That party is no longer available.', 'error'); return; }

    var candidates = state.friends.filter(function (f) {
      return f.status === 'DISCOVER' || f.status === 'FRIEND';
    });
    if (!candidates.length) {
      showToast('No available friends to add yet. Add a player first.', 'error');
      return;
    }

    state.chooser = { mode: 'join', partyId: partyId };
    el('chooser-title').textContent = 'Add a player to "' + party.title + '"';
    el('chooser-list').innerHTML = candidates.map(function (f) {
      return '<div class="chooser-item">' +
        '<div class="ci-main">' +
          '<div class="ci-title">' + escapeHtml(f.name) + '</div>' +
          '<div class="ci-sub">' + escapeHtml(f.ageRole || '') + ' • ' + escapeHtml(f.city || '') + '</div>' +
        '</div>' +
        '<button class="btn btn-primary btn-sm" data-action="choose-friend" data-friend-id="' + escapeHtml(f.id) + '">Add</button>' +
      '</div>';
    }).join('');
    openModal('chooser-modal');
  }

  function openInviteChooser(friendId) {
    var friend = findFriend(friendId);
    if (!friend) { showToast('That player is no longer available.', 'error'); return; }

    var open = state.results.parties.concat(state.openParties).filter(function (p) {
      return p.filledSlots < p.totalSlots;
    });
    var seen = {};
    open = open.filter(function (p) {
      if (seen[p.id]) return false;
      seen[p.id] = true;
      return true;
    });

    if (!open.length) {
      showToast('No open parties with a free slot right now. Create one first.', 'error');
      return;
    }

    state.chooser = { mode: 'invite', friendId: friendId };
    el('chooser-title').textContent = 'Invite ' + friend.name + ' to a party';
    el('chooser-list').innerHTML = open.map(function (p) {
      return '<div class="chooser-item">' +
        '<div class="ci-main">' +
          '<div class="ci-title">' + escapeHtml(p.title) + '</div>' +
          '<div class="ci-sub">' + escapeHtml(p.theme) + ' • ' + escapeHtml(p.city) + ' • ' + escapeHtml(p.filledSlots) + '/' + escapeHtml(p.totalSlots) + ' players</div>' +
        '</div>' +
        '<button class="btn btn-primary btn-sm" data-action="choose-party" data-party-id="' + escapeHtml(p.id) + '">Invite</button>' +
      '</div>';
    }).join('');
    openModal('chooser-modal');
  }

  function joinParty(partyId, friendId) {
    var party = findParty(partyId);
    var friend = findFriend(friendId);
    return window.CrewApi.joinParty(partyId, friendId).then(function () {
      closeAllModals();
      return reloadData().then(function () {
        renderAll();
        showToast((friend ? friend.name : 'Your friend') + ' joined ' + (party ? '"' + party.title + '"' : 'the party') + '.', 'success');
      });
    }).catch(function (err) {
      showToast('Could not join party: ' + err.message, 'error');
    });
  }

  /* ---------------- complete & rate ---------------- */

  function completeParty(partyId) {
    var party = findParty(partyId);
    return window.CrewApi.completeParty(partyId).then(function (visit) {
      return reloadData().then(function () {
        state.highlightVisitId = visit && visit.id ? visit.id : null;
        renderAll();
        switchTab('history');
        focusVisit(state.highlightVisitId);
        showToast('Visit logged for ' + ((visit && visit.venueName) || (party && party.venueName) || 'the venue') +
          '. Rate it below to help the crew.', 'success');
      });
    }).catch(function (err) {
      showToast('Could not complete party: ' + err.message, 'error');
    });
  }

  function submitRating(visitId) {
    var draft = state.drafts[visitId] || { rating: 0, tags: [] };
    var commentBox = el('comment-' + visitId);
    var comment = commentBox ? String(commentBox.value || '').trim() : '';

    if (!draft.rating) {
      showToast('Pick a star rating from 1 to 5 first.', 'error');
      return;
    }

    return window.CrewApi.rateVisit(visitId, {
      rating: draft.rating,
      tags: draft.tags.slice(),
      comment: comment
    }).then(function (updated) {
      return reloadData().then(function () {
        delete state.drafts[visitId];
        state.highlightVisitId = null;
        renderAll();
        var venue = state.venues.filter(function (v) { return String(v.id) === String(updated.venueId); })[0];
        var summary = venue
          ? venue.avgRating.toFixed(1) + ' ★ (' + count(num(venue.ratingCount, 0), 'rating') + ')'
          : count(draft.rating, 'star');
        showToast('Thanks! ' + ((updated && updated.venueName) || 'The venue') + ' is now ' + summary, 'success');
      });
    }).catch(function (err) {
      showToast('Could not save your rating: ' + err.message, 'error');
    });
  }

  /* ---------------- star picker + vibe tags ---------------- */

  function draftFor(visitId) {
    if (!state.drafts[visitId]) state.drafts[visitId] = { rating: 0, tags: [] };
    return state.drafts[visitId];
  }

  function paintStars(form) {
    var visitId = form.dataset.visitId;
    var rating = draftFor(visitId).rating;
    Array.prototype.forEach.call(form.querySelectorAll('.star'), function (star) {
      var value = Number(star.dataset.value);
      star.classList.toggle('is-selected', value <= rating);
      star.setAttribute('aria-checked', String(value === rating));
    });
    var label = form.querySelector('.star-value');
    if (label) label.textContent = rating ? rating + ' / 5' : 'Not rated';
  }

  function pickStar(star) {
    var form = star.closest('.rating-form');
    if (!form) return;
    draftFor(form.dataset.visitId).rating = Number(star.dataset.value);
    paintStars(form);
  }

  function toggleTag(chip) {
    var form = chip.closest('.rating-form');
    if (!form) return;
    var draft = draftFor(form.dataset.visitId);
    var tag = chip.dataset.tag;
    var index = draft.tags.indexOf(tag);
    if (index === -1) draft.tags.push(tag); else draft.tags.splice(index, 1);
    chip.classList.toggle('chip-active', index === -1);
    chip.setAttribute('aria-pressed', String(index === -1));
  }

  function hoverStars(star, on) {
    var form = star.closest('.rating-form');
    if (!form) return;
    var value = Number(star.dataset.value);
    Array.prototype.forEach.call(form.querySelectorAll('.star'), function (s) {
      s.classList.toggle('is-hover', on && Number(s.dataset.value) <= value);
    });
  }

  /* ---------------- friends ---------------- */

  function addFriend(friendId) {
    return window.CrewApi.addFriend(friendId).then(function (friend) {
      state.addedIds[friendId] = true;
      var existing = findFriend(friendId);
      if (existing) {
        existing.status = friend && friend.status ? friend.status : 'FRIEND';
      } else if (friend) {
        state.friends.push(friend);
      }
      renderAll();
      showToast((friend && friend.name ? friend.name : 'That player') + ' is now in your crew.', 'success');
    }).catch(function (err) {
      showToast('Could not add friend: ' + err.message, 'error');
    });
  }

  /* ---------------- paywall ---------------- */

  function openPaywall() {
    if (state.pro) {
      el('paywall-form').hidden = true;
      el('paywall-success').hidden = false;
    } else {
      el('paywall-form').hidden = false;
      el('paywall-success').hidden = true;
    }
    openModal('paywall-modal');
  }

  function startPro(button) {
    button.disabled = true;
    button.textContent = 'Processing…';
    window.CrewApi.checkout().then(function (res) {
      state.pro = true;
      try { window.localStorage.setItem(PRO_KEY, 'true'); } catch (e) { /* storage may be unavailable */ }
      applyProState();
      el('paywall-form').hidden = true;
      el('paywall-success').hidden = false;
      showToast((res && res.plan ? res.plan : 'CrewParty Pro') + ' unlocked' +
        (res && res.amountUsd ? ' for $' + res.amountUsd + '/mo' : '') + '. Welcome aboard!', 'success');
    }).catch(function (err) {
      showToast('Checkout failed: ' + err.message, 'error');
    }).then(function () {
      button.disabled = false;
      button.textContent = 'Start Pro — Test Mode';
    });
  }

  /* ---------------- venue highlight ---------------- */

  function viewVenue(venueId) {
    var cards = document.querySelectorAll('.venue-card[data-venue-id="' + attr(venueId) + '"]');
    if (!cards.length) {
      showToast('That venue card is not on screen right now.', 'info');
      return;
    }
    Array.prototype.forEach.call(cards, function (card) {
      card.classList.add('flash');
      window.setTimeout(function () { card.classList.remove('flash'); }, 1600);
    });
    cards[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (window.CrewMap) window.CrewMap.highlightVenue(venueId);
  }

  /* ---------------- event wiring ---------------- */

  var CLICK_ACTIONS = {
    'switch-tab': function (node) { switchTab(node.dataset.tab); },
    'demo': function () { runDemoPreset(); },
    'open-paywall': function () { openPaywall(); },
    'close-modal': function (node) { closeModalFrom(node); },
    'start-pro': function (node) { startPro(node); },
    'search': function () { doSearch(); },
    'reset-filters': function () { resetFilters(); },
    'open-create': function () { openCreateModal({}); },
    'join-party': function (node) { openJoinChooser(node.dataset.partyId); },
    'complete-party': function (node) { completeParty(node.dataset.partyId); },
    'add-friend': function (node) { addFriend(node.dataset.friendId); },
    'invite-player': function (node) { openInviteChooser(node.dataset.friendId); },
    'choose-friend': function (node) {
      if (!state.chooser) return;
      joinParty(state.chooser.partyId, node.dataset.friendId);
    },
    'choose-party': function (node) {
      if (!state.chooser) return;
      joinParty(node.dataset.partyId, state.chooser.friendId);
    },
    'start-party-with-friends': function () { startPartyWithFriends(); },
    'star-pick': function (node) { pickStar(node); },
    'toggle-tag': function (node) { toggleTag(node); },
    'submit-rating': function (node) { submitRating(node.dataset.visitId); },
    'view-venue': function (node) { viewVenue(node.dataset.venueId); }
  };

  var CHANGE_ACTIONS = {
    'global-city': function () { readFilters(); doSearch(); },
    'create-city': function () { fillVenueSelect(val('create-city')); },
    'create-venue': function () { syncVenueDetails(); },
    'venue-sort': function () { state.venueSort = val('venue-sort'); renderVenues(); },
    'venue-city-filter': function () { state.venueCity = val('venue-city-filter'); renderVenues(); }
  };

  function handleClick(event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var node = target.closest('[data-action]');
    if (!node || node.tagName === 'SELECT') return;
    var handler = CLICK_ACTIONS[node.dataset.action];
    if (!handler) return;
    handler(node, event);
  }

  function handleChange(event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var node = target.closest('[data-action]');
    if (!node) return;
    var handler = CHANGE_ACTIONS[node.dataset.action];
    if (!handler) return;
    handler(node, event);
  }

  function handleSubmit(event) {
    if (event.target && event.target.id === 'create-form') {
      event.preventDefault();
      submitCreate();
    }
  }

  function handleStarHover(event) {
    var star = event.target.closest ? event.target.closest('.star') : null;
    if (!star) return;
    hoverStars(star, true);
  }

  function handleStarLeave(event) {
    var star = event.target.closest ? event.target.closest('.star') : null;
    if (!star) return;
    var next = event.relatedTarget;
    if (next && next.closest && next.closest('.star')) return; // still moving between stars
    hoverStars(star, false);
  }

  function bindEvents() {
    document.addEventListener('click', handleClick);
    document.addEventListener('change', handleChange);
    document.addEventListener('submit', handleSubmit);
    document.addEventListener('mouseover', handleStarHover);
    document.addEventListener('mouseout', handleStarLeave);
    document.addEventListener('focusin', handleStarHover);
    document.addEventListener('focusout', handleStarLeave);
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') closeAllModals();
    });
  }

  /* ---------------- boot ---------------- */

  function init() {
    state.pro = false;
    try { state.pro = window.localStorage.getItem(PRO_KEY) === 'true'; } catch (e) { state.pro = false; }

    applyProState();
    bindEvents();

    if (window.CrewMap) window.CrewMap.init('venue-map');

    window.CrewApi.health().catch(function () {
      showToast('Backend health check failed — some actions may not work.', 'error');
    });

    loadBootstrap();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
