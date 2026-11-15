/* CrewParty — application state, rendering and event wiring (Vanilla ES6+).
   Zero-login multi-tab identities, live presence polling, an interactive map, a filter
   sidebar with a weekly free-event radar, and five fully wired navigation tabs. */
(() => {
  'use strict';

  /* ---------------- constants ---------------- */

  const THEMES = ['Board Games', 'Co-op Video Games', 'Coworking & Chill', 'Trivia & Social', 'Outdoor Casual'];
  const CITIES = ['Chicago', 'Austin', 'Seattle', 'Washington, D.C.'];
  const VIBE_TAGS = ['Great Wi-Fi', 'Budget Friendly', 'Quiet Enough to Talk', 'Great Table Space', 'Craft Drinks', 'Late Night'];
  const TABS = ['explore', 'friends', 'history', 'venues', 'profile'];
  const PRO_KEY = 'crewparty.pro';
  const SESSION_ID_KEY = 'crewparty.guestId';
  const PRESENCE_MS = 2500;

  /** Radar categories mapped onto a crew theme so "Start Party Here" pre-fills correctly. */
  const RADAR_THEME = {
    'Free Museum Day': 'Trivia & Social',
    'Local Fair': 'Outdoor Casual',
    'Public Park Meetup': 'Outdoor Casual'
  };

  /* ---------------- state ---------------- */

  const state = {
    identity: null,
    presence: { onlineGuests: [], acceptedFriends: [], pendingFriendRequests: [], pendingSlotInvites: [] },
    pending: [],                 // normalized actionable notifications
    dismissedRequests: new Set(),
    friends: [],
    venues: [],
    openParties: [],
    visitHistory: [],
    results: { parties: [], players: [], venues: [] },
    radar: [],
    filters: {
      city: 'All Cities', theme: '', timeWindow: '',
      budgetMin: 0, budgetMax: 100,
      format: 'All', pace: 'All',
      friendsOnly: false, publicSafeOnly: false, verifiedOnly: false, miniGroupOnly: false
    },
    venueCity: 'All Cities',
    venueSort: 'rating',
    tab: 'explore',
    pro: false,
    drafts: {},                  // visitId -> { rating, tags }
    chooser: null,               // { mode, guestId?, partyId? }
    highlightVisitId: null,
    selectedSpot: null,          // { lat, lng, name, category, isListedPlace } dropped/picked on the map
    spotNameDirty: false,        // true once the user typed a Custom spot name themselves
    createDraft: null,           // Create Party form values parked while the user picks a spot on the map
    pickingMapSpot: false,       // true while the map is armed to receive the Create Party spot
    lastRevision: null,          // last shared-data revision we rendered; drives the live cross-tab sync
    openDrawerVenueId: null,     // venue whose drawer is open right now, so a live refresh can reload it
    loaded: false
  };

  /* ---------------- small helpers ---------------- */

  const el = (id) => document.getElementById(id);
  const val = (id) => { const node = el(id); return node ? node.value : ''; };
  const num = (value, fallback) => { const parsed = parseInt(value, 10); return isNaN(parsed) ? fallback : parsed; };
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const myId = () => (state.identity ? state.identity.guestId : '');
  const count = (n, noun) => `${n} ${noun}${n === 1 ? '' : 's'}`;

  const escapeHtml = (value) => {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  };
  const attr = (value) => String(value === null || value === undefined ? '' : value).replace(/["\\]/g, '');

  const initials = (name) => String(name || '?').trim().split(/\s+/).slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase()).join('');

  const starGlyphs = (rating) => {
    let out = '';
    for (let i = 1; i <= 5; i++) out += i <= rating ? '\u2605' : '<span class="off">\u2605</span>';
    return out;
  };

  const chips = (list) => (list || []).map((t) => `<span class="chip">${escapeHtml(t)}</span>`).join('');
  const loading = (text) => `<div class="loading">${escapeHtml(text)}</div>`;
  const emptyState = (title, text) =>
    `<div class="empty"><span class="empty-title">${escapeHtml(title)}</span>${escapeHtml(text)}</div>`;

  /** Great-circle distance in km — used for the client-side "nearest venue" spot matching. */
  const haversineKm = (lat1, lng1, lat2, lng2) => {
    const toRad = (d) => d * Math.PI / 180;
    const dLat = toRad(lat2 - lat1);
    const dLng = toRad(lng2 - lng1);
    const a = Math.sin(dLat / 2) ** 2
      + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
    return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  };

  const findVenue = (venueId) => state.venues.find((v) => String(v.id) === String(venueId)) || null;
  const findParty = (partyId) => allOpenParties().find((p) => String(p.id) === String(partyId)) || null;
  const findGuestById = (guestId) =>
    (state.presence.onlineGuests || []).find((g) => g.guestId === guestId) || null;

  const allOpenParties = () => {
    const seen = {};
    const list = [];
    [...state.openParties, ...state.results.parties].forEach((p) => {
      if (p && !seen[p.id]) { seen[p.id] = true; list.push(p); }
    });
    return list;
  };

  const mySlots = () => allOpenParties().filter((p) => (p.memberGuestIds || []).includes(myId()));

  /** Parties this tab hosts right now (the host is always the first memberGuestId). */
  const hostedActiveCount = () =>
    allOpenParties().filter((p) => (p.memberGuestIds || [])[0] === myId()).length;

  const acceptedFriendIds = () => {
    const ids = new Set();
    (state.presence.acceptedFriends || []).forEach((relation) => {
      ids.add(relation.fromGuestId === myId() ? relation.toGuestId : relation.fromGuestId);
    });
    return ids;
  };

  /** Closest CrewParty venue. With maxKm set, anything farther away resolves to null. */
  const nearestVenue = (lat, lng, maxKm) => {
    let best = null;
    let bestDistance = Infinity;
    state.venues.forEach((v) => {
      if (typeof v.lat !== 'number' || typeof v.lng !== 'number') return;
      const d = haversineKm(lat, lng, v.lat, v.lng);
      if (d < bestDistance) { bestDistance = d; best = v; }
    });
    if (best && typeof maxKm === 'number' && bestDistance > maxKm) return null;
    return best;
  };

  /* ---------------- toasts & banners ---------------- */

  const showToast = (message, type) => {
    const area = el('toast-area');
    if (!area) return;
    const toast = document.createElement('div');
    toast.className = `toast toast-${type || 'info'}`;
    toast.textContent = message;
    area.appendChild(toast);
    const life = type === 'error' ? 6500 : 4200;
    window.setTimeout(() => {
      toast.classList.add('toast-out');
      window.setTimeout(() => { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 320);
    }, life);
  };

  const dismissBanner = (node) => {
    const banner = node && node.closest ? node.closest('.banner') : null;
    if (!banner) return;
    banner.classList.add('banner-out');
    window.setTimeout(() => { if (banner.parentNode) banner.parentNode.removeChild(banner); }, 320);
  };

  const removeBanner = (key) => {
    const node = document.querySelector(`.banner[data-banner-key="${attr(key)}"]`);
    if (node) dismissBanner(node);
  };

  const showBanner = (notification) => {
    const area = el('banner-area');
    if (!area) return;
    removeBanner(notification.key);
    area.insertAdjacentHTML('afterbegin', bannerHtml(notification));
    while (area.children.length > 3) area.removeChild(area.lastElementChild);
  };

  const bannerHtml = (n) => {
    if (n.kind === 'invite') {
      const inv = n.invite;
      return `<div class="banner" data-banner-key="${attr(n.key)}">
        <div class="banner-title">Slot invitation</div>
        <div class="banner-sub"><strong>${escapeHtml(inv.fromName)}</strong> invited you to <strong>${escapeHtml(inv.partyTitle)}</strong>.</div>
        ${inv.venueName ? `<div class="banner-meta">at ${escapeHtml(inv.venueName)}</div>` : ''}
        <div class="banner-actions">
          <button class="btn btn-primary btn-sm" data-action="accept-invite" data-invite-id="${attr(inv.id)}">Accept &amp; Join</button>
          <button class="btn btn-ghost btn-sm" data-action="decline-invite" data-invite-id="${attr(inv.id)}">Decline</button>
        </div>
      </div>`;
    }
    const req = n.request;
    return `<div class="banner" data-banner-key="${attr(n.key)}">
      <div class="banner-title">Friend request</div>
      <div class="banner-sub"><strong>${escapeHtml(req.fromName)}</strong> wants to join your crew.</div>
      <div class="banner-actions">
        <button class="btn btn-primary btn-sm" data-action="accept-friend" data-request-id="${attr(req.id)}">Accept</button>
        <button class="btn btn-ghost btn-sm" data-action="decline-friend" data-request-id="${attr(req.id)}">Decline</button>
      </div>
    </div>`;
  };

  /** Hint banner shown above the map while the Create Party flow is waiting for a spot click. */
  const showMapPickBanner = () => {
    const banner = el('map-pick-banner');
    if (banner) banner.hidden = false;
  };

  const hideMapPickBanner = () => {
    const banner = el('map-pick-banner');
    if (banner) banner.hidden = true;
  };

  const notifItemHtml = (n) => {
    if (n.kind === 'invite') {
      const inv = n.invite;
      return `<div class="notif-item">
        <div class="notif-item-title">Slot invitation</div>
        <div class="notif-item-sub"><strong>${escapeHtml(inv.fromName)}</strong> invited you to ${escapeHtml(inv.partyTitle)}${inv.venueName ? ` at ${escapeHtml(inv.venueName)}` : ''}.</div>
        <div class="notif-item-actions">
          <button class="btn btn-primary btn-sm" data-action="accept-invite" data-invite-id="${attr(inv.id)}">Accept &amp; Join</button>
          <button class="btn btn-ghost btn-sm" data-action="decline-invite" data-invite-id="${attr(inv.id)}">Decline</button>
        </div>
      </div>`;
    }
    const req = n.request;
    return `<div class="notif-item">
      <div class="notif-item-title">Friend request</div>
      <div class="notif-item-sub"><strong>${escapeHtml(req.fromName)}</strong> wants to join your crew.</div>
      <div class="notif-item-actions">
        <button class="btn btn-primary btn-sm" data-action="accept-friend" data-request-id="${attr(req.id)}">Accept</button>
        <button class="btn btn-ghost btn-sm" data-action="decline-friend" data-request-id="${attr(req.id)}">Decline</button>
      </div>
    </div>`;
  };

  /* ---------------- modals & drawer ---------------- */

  const openModal = (id) => {
    const modal = el(id);
    if (!modal) return;
    modal.classList.add('open');
    document.body.classList.add('modal-open');
    const focusable = modal.querySelector('input, select, button');
    if (focusable) window.setTimeout(() => focusable.focus(), 40);
  };

  const closeModalFrom = (node) => {
    const modal = node && node.closest ? node.closest('.modal') : null;
    if (modal) modal.classList.remove('open');
    if (!document.querySelector('.modal.open')) document.body.classList.remove('modal-open');
  };

  const closeAllModals = () => {
    document.querySelectorAll('.modal.open').forEach((m) => m.classList.remove('open'));
    document.body.classList.remove('modal-open');
  };

  const openDrawer = () => {
    el('drawer-scrim').hidden = false;
    const drawer = el('venue-drawer');
    drawer.classList.add('open');
    drawer.setAttribute('aria-hidden', 'false');
  };

  const closeDrawer = () => {
    el('drawer-scrim').hidden = true;
    const drawer = el('venue-drawer');
    drawer.classList.remove('open');
    drawer.setAttribute('aria-hidden', 'true');
    state.openDrawerVenueId = null;
  };

  /** True while the right-hand venue drawer is on screen. */
  const drawerIsOpen = () => {
    const drawer = el('venue-drawer');
    return Boolean(drawer && drawer.classList.contains('open'));
  };

  /** Reloads the open venue drawer in place so a slot created elsewhere shows up immediately. */
  const refreshOpenDrawer = async () => {
    const venueId = state.openDrawerVenueId;
    if (!venueId || !drawerIsOpen()) return;
    try {
      renderVenueDrawer(await window.CrewApi.venueSlots(venueId));
    } catch (e) {
      /* keep the current drawer contents if the background refresh blips */
    }
  };

  /* ---------------- identity (per-tab, zero login) ---------------- */

  const initIdentity = async () => {
    let storedId = null;
    try { storedId = window.sessionStorage.getItem(SESSION_ID_KEY); } catch (e) { storedId = null; }

    const session = await window.CrewApi.sessionInit(storedId, null);
    state.identity = session;
    syncPro();
    try {
      window.sessionStorage.setItem(SESSION_ID_KEY, session.guestId);
      window.sessionStorage.setItem('crewparty.guestName', session.displayName || '');
    } catch (e) { /* sessionStorage may be unavailable */ }
    applyProfileDefaults();
    renderIdentity();
  };

  /** The server session is the source of truth for Pro; localStorage is only the pre-session fallback. */
  const syncPro = () => {
    const next = Boolean(state.identity && state.identity.pro);
    if (next === state.pro) return;
    state.pro = next;
    try { window.localStorage.setItem(PRO_KEY, String(next)); } catch (e) { /* storage may be unavailable */ }
  };

  const applyProfileDefaults = () => {
    const s = state.identity;
    if (!s) return;
    state.filters.publicSafeOnly = !!s.publicVenuesOnly;
    el('public-safe').checked = !!s.publicVenuesOnly;
    state.filters.miniGroupOnly = !!s.miniGroupPriority;
    el('mini-group-only').checked = !!s.miniGroupPriority;
  };

  const renderIdentity = () => {
    const s = state.identity;
    if (!s) return;
    const age = String(s.ageRole || '').split('\u2022')[0].trim();
    el('identity-avatar').textContent = initials(s.displayName);
    const name = `${escapeHtml(s.displayName)}${age ? ` (${escapeHtml(age)})` : ''}`;
    el('identity-name').innerHTML = state.pro
      ? `${name} <span class="badge badge-pro" title="CrewParty Pro member">PRO</span>`
      : name;
    const plan = state.pro ? 'Pro' : `Free \u2022 ${hostedActiveCount()}/1 active party`;
    el('identity-sub').textContent = [plan, s.neighborhood, s.city, s.format, s.pace]
      .filter(Boolean).join(' \u2022 ');
  };

  /* ---------------- presence polling & notifications ---------------- */

  const startPresencePolling = () => {
    pollPresence();
    window.setInterval(pollPresence, PRESENCE_MS);
  };

  const pollPresence = async (options) => {
    const opts = options || {};
    if (!state.identity) return;
    try {
      const presence = await window.CrewApi.presence(myId());
      state.presence = presence;
      if (presence.currentGuest) {
        state.identity = { ...state.identity, ...presence.currentGuest };
        syncPro();
        applyProState();
        renderIdentity();
      }
      handleNotifications(presence);
      renderPresence();
      if (opts.adopt) {
        // This poll follows one of our own mutations, so the new revision is already rendered.
        if (presence.stateRevision !== undefined && presence.stateRevision !== null) {
          state.lastRevision = presence.stateRevision;
        }
      } else {
        syncSharedState(presence.stateRevision);
      }
    } catch (e) {
      /* keep polling silently so a blip never spams the UI */
    }
  };

  /**
   * The server bumps its shared-data revision whenever anybody creates a slot, drops a pin or joins
   * a party. When the number moves past the one we last rendered, another tab (or another user) has
   * changed the map, so we quietly refetch everything and redraw the pins without moving the camera.
   */
  const syncSharedState = (revision) => {
    if (revision === undefined || revision === null) return;
    if (state.lastRevision === null) {
      state.lastRevision = revision;           // first poll just establishes the baseline
      return;
    }
    if (revision === state.lastRevision) return;
    state.lastRevision = revision;
    refreshLiveViews();
  };

  /** Background refresh used by the live sync: never steals focus and never resets the map view. */
  let liveRefreshInFlight = false;
  const refreshLiveViews = async () => {
    if (liveRefreshInFlight) return;
    liveRefreshInFlight = true;
    try {
      await reloadAll();
      await doSearch({ silent: true, background: true, preserveView: true });
      await refreshOpenDrawer();
    } catch (e) {
      /* a failed background refresh must never disrupt the user */
    } finally {
      liveRefreshInFlight = false;
    }
  };

  const handleNotifications = (presence) => {
    const pending = [];
    (presence.pendingSlotInvites || []).forEach((invite) => {
      pending.push({ key: `invite:${invite.id}`, kind: 'invite', invite });
    });
    (presence.pendingFriendRequests || []).forEach((request) => {
      if (state.dismissedRequests.has(request.id)) return;
      pending.push({ key: `req:${request.id}`, kind: 'friend', request });
    });
    state.pending = pending;

    const bell = el('notif-count');
    bell.textContent = pending.length;
    bell.hidden = pending.length === 0;

    renderNotifPanel(pending);

    pending.forEach((n) => {
      if (state.seenKeys && state.seenKeys.has(n.key)) return;
      if (!state.seenKeys) state.seenKeys = new Set();
      state.seenKeys.add(n.key);
      showBanner(n);
    });
  };

  const renderNotifPanel = (pending) => {
    const list = el('notif-list');
    if (!list) return;
    list.innerHTML = pending.length
      ? pending.map(notifItemHtml).join('')
      : '<div class="notif-empty">You are all caught up.</div>';
  };

  const toggleNotif = (node) => {
    const panel = el('notif-panel');
    const open = panel.hidden;
    panel.hidden = !open;
    node.setAttribute('aria-expanded', String(open));
  };

  /* ---------------- data loading ---------------- */

  const applyBootstrap = (data) => {
    state.friends = data.friends || [];
    state.venues = data.venues || [];
    state.openParties = data.openParties || [];
    state.visitHistory = data.visitHistory || [];
  };

  const reloadAll = async () => {
    applyBootstrap(await window.CrewApi.bootstrap());
  };

  const loadRadar = async () => {
    state.filters.city = val('global-city') || 'All Cities';
    const city = state.filters.city === 'All Cities' ? '' : state.filters.city;
    try {
      state.radar = await window.CrewApi.radar(city, state.filters.budgetMax);
    } catch (e) {
      state.radar = [];
    }
    renderRadar();
  };

  /** Reload everything after a mutation and refresh the views. */
  const refreshAfterChange = async (message) => {
    await reloadAll();
    await doSearch({ silent: true });
    await loadRadar();
    // The mutation we just made bumped the server revision; adopt it instead of treating our own
    // change as somebody else's and firing a redundant background refresh.
    await pollPresence({ adopt: true });
    renderAll();
    if (message) showToast(message, 'success');
  };

  /* ---------------- rendering: identity extras ---------------- */

  const isJoined = (party) => (party.memberGuestIds || []).includes(myId());

  const friendsInsideParty = (party) => {
    const friends = acceptedFriendIds();
    return (party.memberGuestIds || []).filter((id) => friends.has(id)).length;
  };

  const partyFlags = (p) => {
    const venue = findVenue(p.venueId);
    const inside = friendsInsideParty(p);
    return [
      p.demoPool ? '<span class="badge badge-demo">Demo Pool</span>' : '',
      p.midpointMode ? '<span class="badge badge-midpoint">Fair-Midpoint Auto-Matched</span>' : '',
      inside > 0 ? `<span class="badge badge-friends">${inside} friend${inside === 1 ? '' : 's'} inside</span>` : '',
      venue && venue.publicSafeSpot ? '<span class="badge badge-safe">Public Safe Spot</span>' : '',
      venue && venue.featuredPartner ? '<span class="badge badge-featured">Featured Partner</span>' : '',
      (p.trustBadges || []).includes('Pro Host') ? '<span class="badge badge-pro">Pro Host</span>' : '',
      (p.trustBadges || []).filter((b) => b !== 'Pro Host')
        .map((b) => `<span class="badge badge-trust">${escapeHtml(b)}</span>`).join('')
    ].filter(Boolean).join('');
  };

  /* ---------------- rendering: parties ---------------- */

  const partyCard = (p) => {
    const full = p.filledSlots >= p.totalSlots;
    const fit = clamp(num(p.fitScore, 0), 0, 100);
    const distance = num(p.distanceKm, 0);
    const joined = isJoined(p);
    const checkedIn = (p.checkedInGuestIds || []).includes(myId());
    const members = (p.memberNames && p.memberNames.length ? p.memberNames : p.members || [])
      .map((m) => `<span class="chip">${escapeHtml(m)}</span>`).join('');
    const icebreakers = (p.icebreakers || []).map((i) => `<li>${escapeHtml(i)}</li>`).join('');

    return `<article class="card party-card" data-party-id="${attr(p.id)}">
      <div class="card-head">
        <h3 class="card-title">${escapeHtml(p.title)}</h3>
        <span class="badge badge-theme">${escapeHtml(p.theme)}</span>
      </div>
      <div class="meta-row">
        <span>${escapeHtml(p.city)} \u2022 ${escapeHtml(p.neighborhood)}</span>
        <span class="dot">\u2022</span>
        <span>${escapeHtml(p.timeLabel)}</span>
      </div>
      <div class="meta-row">
        <span class="price">$${escapeHtml(p.budgetMin)}-$${escapeHtml(p.budgetMax)}</span>
        <span class="dot">\u2022</span>
        <span>${escapeHtml(p.filledSlots)}/${escapeHtml(p.totalSlots)} players</span>
        ${distance > 0 ? `<span class="dot">\u2022</span><span>${distance} km away</span>` : ''}
      </div>
      <div class="party-flags">${partyFlags(p)}</div>
      <div class="fit">
        <div class="fit-label"><span>Match Fit Score</span><strong>${fit}%</strong></div>
        <div class="fit-bar"><span style="width:${fit}%"></span></div>
      </div>
      <div class="venue-line">Venue: <strong>${escapeHtml(p.venueName)}</strong></div>
      <div class="chips">${members}</div>
      ${icebreakers ? `<div class="icebreakers"><div class="icebreakers-label">AI Icebreakers</div><ul>${icebreakers}</ul></div>` : ''}
      <div class="card-actions">
        ${full
          ? `<button class="btn btn-accent" data-action="queue-party" data-party-id="${attr(p.id)}">Join Waitlist Queue</button>`
          : `<button class="btn btn-primary" data-action="join-party" data-party-id="${attr(p.id)}">Join Slot</button>`}
        ${joined && !checkedIn
          ? `<button class="btn btn-accent" data-action="checkin-party" data-party-id="${attr(p.id)}">Safety Check-In</button>`
          : ''}
        ${p.venueId ? `<button class="btn btn-ghost" data-action="open-venue" data-venue-id="${attr(p.venueId)}">View Live Slots</button>` : ''}
        <button class="btn btn-ghost" data-action="complete-party" data-party-id="${attr(p.id)}">Complete &amp; Rate</button>
      </div>
    </article>`;
  };

  const renderParties = () => {
    const host = el('party-list');
    const parties = state.results.parties;
    host.innerHTML = parties.length
      ? parties.map(partyCard).join('')
      : emptyState('No open parties match your filters', 'Loosen the budget window or hit Reset filters to see every crew in town.');
  };

  /* ---------------- rendering: my open slots ---------------- */

  const mySlotCard = (p) => {
    const members = p.memberGuestIds || [];
    const isHost = members[0] === myId();
    const queue = (p.queueGuestNames || []).length;
    const distance = num(p.distanceKm, 0);
    const openSlots = Math.max(0, num(p.totalSlots, 0) - num(p.filledSlots, 0));
    const place = [p.venueName, p.neighborhood].filter(Boolean).join(' \u2022 ');

    return `<article class="card party-card my-slot-card" data-party-id="${attr(p.id)}">
      <div class="card-head">
        <h3 class="card-title">${escapeHtml(p.title)}</h3>
        <span class="badge ${isHost ? 'badge-pro' : 'badge-slot'}">${isHost ? 'You are Host' : 'Joined'}</span>
      </div>
      <div class="meta-row">
        <span>${escapeHtml(p.city)}${place ? ` \u2022 ${escapeHtml(place)}` : ''}</span>
        <span class="dot">\u2022</span>
        <span>${escapeHtml(p.timeLabel)}</span>
      </div>
      <div class="meta-row">
        <span>${escapeHtml(p.filledSlots)}/${escapeHtml(p.totalSlots)} joined</span>
        <span class="dot">\u2022</span>
        <span>${openSlots} open</span>
        <span class="dot">\u2022</span>
        <span>${queue} in queue</span>
        ${distance > 0 ? `<span class="dot">\u2022</span><span>${distance} km away</span>` : ''}
      </div>
      <div class="party-flags">${partyFlags(p)}</div>
      <div class="venue-line">Spot: <strong>${escapeHtml(p.venueName || 'TBD')}</strong></div>
      <div class="card-actions">
        <button class="btn btn-accent" data-action="invite-friend-slot" data-party-id="${attr(p.id)}">Invite Friend</button>
        <button class="btn btn-primary" data-action="complete-party" data-party-id="${attr(p.id)}">Complete &amp; Rate</button>
        <button class="btn btn-ghost" data-action="leave-slot" data-party-id="${attr(p.id)}">Cancel / Leave Slot</button>
      </div>
    </article>`;
  };

  const renderMySlots = () => {
    const host = el('my-slots-list');
    if (!host) return;
    const slots = mySlots();
    const badge = el('my-slots-count');
    if (badge) badge.textContent = String(slots.length);
    host.innerHTML = slots.length
      ? slots.map(mySlotCard).join('')
      : emptyState('You are not in any slots yet', 'Create a party or join one below and your seats, open spots and invites show up here.');
  };

  /* ---------------- rendering: players (demo crowd) ---------------- */

  const playerCard = (f) => {
    const added = f.status && f.status !== 'DISCOVER';
    return `<article class="card player-card" data-friend-id="${attr(f.id)}">
      <div class="avatar">${escapeHtml(initials(f.name))}</div>
      <div class="player-body">
        <div class="player-name">${escapeHtml(f.name)}${f.verified ? '<span class="verified" title="Verified">\u2713</span>' : ''}</div>
        <div class="player-role">${escapeHtml(f.ageRole)}</div>
        <div class="player-city">${escapeHtml(f.city)}</div>
        <div class="chips">${chips(f.themes)}</div>
      </div>
      <div class="player-actions">
        <button class="btn btn-primary btn-sm" data-action="add-friend" data-friend-id="${attr(f.id)}"${added ? ' disabled' : ''}>${added ? 'Added' : 'Add Friend'}</button>
      </div>
    </article>`;
  };

  const renderPlayers = () => {
    const host = el('player-list');
    const players = state.results.players || [];
    host.innerHTML = players.length
      ? players.map(playerCard).join('')
      : emptyState('No matching players right now', 'Try another theme or city, or hit Reset to see everyone.');
  };

  /* ---------------- rendering: friends & live invites ---------------- */

  const relationshipFor = (guestId) => {
    const friends = acceptedFriendIds();
    if (friends.has(guestId)) return 'friends';
    const incoming = (state.presence.pendingFriendRequests || []).find((r) => r.fromGuestId === guestId);
    if (incoming) return 'incoming';
    return 'none';
  };

  const onlineCard = (g) => {
    const relation = relationshipFor(g.guestId);
    const incoming = (state.presence.pendingFriendRequests || []).find((r) => r.fromGuestId === g.guestId);
    return `<article class="card online-card" data-guest-id="${attr(g.guestId)}">
      <div class="avatar">${escapeHtml(initials(g.displayName))}</div>
      <div class="online-body">
        <div class="player-name"><span class="online-dot"></span>${escapeHtml(g.displayName)}</div>
        <div class="online-role">${escapeHtml(g.ageRole || '')}</div>
        <div class="online-meta">${escapeHtml(g.neighborhood || '')} \u2022 ${escapeHtml(g.city || '')} \u2022 prefers ${escapeHtml(g.format || 'Solo')} / ${escapeHtml(g.pace || 'Chill')}</div>
        <div class="chips">${chips(g.verificationBadges)}</div>
      </div>
      <div class="online-actions">
        ${relation === 'friends'
          ? '<span class="badge badge-verified">In your crew</span>'
          : relation === 'incoming'
            ? `<button class="btn btn-primary btn-sm" data-action="accept-friend" data-request-id="${attr(incoming.id)}">Accept</button>`
            : `<button class="btn btn-primary btn-sm" data-action="request-friend" data-guest-id="${attr(g.guestId)}">Add Friend</button>`}
        <button class="btn btn-ghost btn-sm" data-action="invite-to-slot" data-guest-id="${attr(g.guestId)}">Invite to My Slot</button>
      </div>
    </article>`;
  };

  const renderOnline = () => {
    const host = el('online-list');
    const others = (state.presence.onlineGuests || []).filter((g) => g.guestId !== myId());
    host.innerHTML = others.length
      ? others.map(onlineCard).join('')
      : emptyState('Nobody else is online yet', 'Open a second browser tab with the button up top to bring another live user in.');
  };

  const requestCard = (relation) => `<article class="card online-card" data-request-id="${attr(relation.id)}">
    <div class="avatar">${escapeHtml(initials(relation.fromName))}</div>
    <div class="online-body">
      <div class="player-name">${escapeHtml(relation.fromName)}</div>
      <div class="online-role">wants to join your crew</div>
    </div>
    <div class="online-actions">
      <button class="btn btn-primary btn-sm" data-action="accept-friend" data-request-id="${attr(relation.id)}">Accept</button>
      <button class="btn btn-ghost btn-sm" data-action="decline-friend" data-request-id="${attr(relation.id)}">Decline</button>
    </div>
  </article>`;

  const renderFriendRequests = () => {
    const host = el('friend-requests');
    const requests = (state.presence.pendingFriendRequests || []).filter((r) => !state.dismissedRequests.has(r.id));
    host.innerHTML = requests.length
      ? requests.map(requestCard).join('')
      : emptyState('No pending requests', 'Friend requests from other live tabs will show up here.');
  };

  const friendCard = (relation) => {
    const otherName = relation.fromGuestId === myId() ? relation.toName : relation.fromName;
    const otherId = relation.fromGuestId === myId() ? relation.toGuestId : relation.fromGuestId;
    const online = !!findGuestById(otherId);
    return `<article class="card online-card" data-guest-id="${attr(otherId)}">
      <div class="avatar">${escapeHtml(initials(otherName))}</div>
      <div class="online-body">
        <div class="player-name">${online ? '<span class="online-dot"></span>' : ''}${escapeHtml(otherName)}</div>
        <div class="online-role">${online ? 'Online now' : 'Offline'}</div>
      </div>
      <div class="online-actions">
        <button class="btn btn-ghost btn-sm" data-action="invite-to-slot" data-guest-id="${attr(otherId)}">Invite to My Slot</button>
      </div>
    </article>`;
  };

  const renderFriends = () => {
    const host = el('friends-list');
    const relations = state.presence.acceptedFriends || [];
    host.innerHTML = relations.length
      ? relations.map(friendCard).join('')
      : emptyState('Your crew is empty', 'Send a friend request to anyone online and they will appear here once they accept.');
  };

  const renderInvitePanel = () => {
    const host = el('invite-panel');
    if (!host) return;
    const slots = mySlots();
    const relations = state.presence.acceptedFriends || [];
    if (!slots.length) {
      host.innerHTML = emptyState('You have no open slots', 'Create a party first, then invite friends straight into a free slot.');
      return;
    }
    if (!relations.length) {
      host.innerHTML = emptyState('No friends to invite yet', 'Add an online guest to your crew first.');
      return;
    }
    const slotOptions = slots.map((p) =>
      `<option value="${attr(p.id)}">${escapeHtml(p.title)} (${p.filledSlots}/${p.totalSlots})</option>`).join('');
    const friendOptions = relations.map((r) => {
      const name = r.fromGuestId === myId() ? r.toName : r.fromName;
      const id = r.fromGuestId === myId() ? r.toGuestId : r.fromGuestId;
      return `<option value="${attr(id)}">${escapeHtml(name)}</option>`;
    }).join('');
    host.innerHTML = `<div class="invite-row">
        <div class="field">
          <label class="field-label" for="invite-slot-select">My open slot</label>
          <select id="invite-slot-select" class="select">${slotOptions}</select>
        </div>
        <div class="field">
          <label class="field-label" for="invite-friend-select">Invite friend</label>
          <select id="invite-friend-select" class="select">${friendOptions}</select>
        </div>
      </div>
      <div class="card-actions">
        <button class="btn btn-primary" data-action="send-invite">Send Invite</button>
      </div>`;
  };

  const renderPresence = () => {
    renderOnline();
    renderFriendRequests();
    renderFriends();
    renderInvitePanel();
  };

  /* ---------------- rendering: radar ---------------- */

  const renderRadar = () => {
    const host = el('radar-strip');
    if (!host) return;
    const events = state.radar || [];
    if (!events.length) {
      host.innerHTML = emptyState('No free events in this window', 'Try All Cities or widen your budget to catch this weekend\'s free museum days and park meetups.');
      return;
    }
    host.innerHTML = events.map((event) => {
      const free = num(event.costPerPerson, 0) === 0;
      return `<article class="radar-card">
        <div class="radar-cat">${escapeHtml(event.category)}</div>
        <div class="radar-title">${escapeHtml(event.title)}</div>
        <div class="radar-meta">${escapeHtml(event.whenLabel)} \u2022 ${escapeHtml(event.venueName)}</div>
        <div class="radar-meta">${escapeHtml(event.city)}</div>
        <div class="radar-cost ${free ? 'free' : 'paid'}">${free ? 'FREE' : `$${escapeHtml(event.costPerPerson)} per person`}</div>
        <button class="btn btn-accent btn-sm" data-action="radar-start" data-event-id="${attr(event.id)}">Start Party Here</button>
      </article>`;
    }).join('');
  };

  /* ---------------- rendering: visit history ---------------- */

  const sortedHistory = () => state.visitHistory.slice().sort((a, b) => {
    const ta = Date.parse(a.visitedDate) || 0;
    const tb = Date.parse(b.visitedDate) || 0;
    return tb - ta;
  });

  const ratingForm = (v) => {
    const draft = state.drafts[v.id] || { rating: 0, tags: [] };
    const stars = [1, 2, 3, 4, 5].map((n) =>
      `<button type="button" class="star${n <= draft.rating ? ' is-selected' : ''}" data-action="star-pick" data-value="${n}" role="radio" aria-checked="${n === draft.rating}" aria-label="${n} star${n === 1 ? '' : 's'}">\u2605</button>`
    ).join('');
    const tags = VIBE_TAGS.map((t) => {
      const active = draft.tags.includes(t);
      return `<button type="button" class="chip chip-btn${active ? ' chip-active' : ''}" data-action="toggle-tag" data-tag="${escapeHtml(t)}" aria-pressed="${active}">${escapeHtml(t)}</button>`;
    }).join('');

    return `<div class="rating-form" data-visit-id="${attr(v.id)}">
      <div class="rating-label">Rate this venue</div>
      <div class="star-row" role="radiogroup" aria-label="Venue rating">${stars}
        <span class="star-value">${draft.rating ? `${draft.rating} / 5` : 'Not rated'}</span>
      </div>
      <div class="tag-row">${tags}</div>
      <textarea class="textarea" id="comment-${attr(v.id)}" rows="3" placeholder="How was the venue? Any tips for the crew?"></textarea>
      <div class="card-actions">
        <button class="btn btn-primary" data-action="submit-rating" data-visit-id="${attr(v.id)}">Submit Venue Rating</button>
      </div>
    </div>`;
  };

  const ratedSummary = (v) => {
    const tags = (v.selectedTags || []).map((t) => `<span class="chip">${escapeHtml(t)}</span>`).join('');
    return `<div class="rated-summary">
      <div class="stars-static">${starGlyphs(v.rating || 0)}</div>
      <div class="rated-meta">Rated ${escapeHtml(v.rating)} out of 5 on ${escapeHtml(v.visitedDate)}</div>
      ${tags ? `<div class="chips">${tags}</div>` : ''}
      ${v.reviewComment ? `<p class="rated-comment">"${escapeHtml(v.reviewComment)}"</p>` : ''}
    </div>`;
  };

  const historyCard = (v) => {
    const highlighted = state.highlightVisitId && String(state.highlightVisitId) === String(v.id);
    const attendees = (v.attendees || []).map((name) => `<span class="attendee">${escapeHtml(name)}</span>`).join('');
    return `<article class="card history-card${highlighted ? ' highlight' : ''}" id="history-card-${attr(v.id)}">
      <div class="card-head">
        <h3 class="card-title">${escapeHtml(v.partyTitle)}</h3>
        <span class="badge badge-theme">${escapeHtml(v.theme)}</span>
      </div>
      <div class="meta-row">
        <span>Venue: <strong>${escapeHtml(v.venueName)}</strong></span>
        <span class="dot">\u2022</span>
        <span>${escapeHtml(v.visitedDate)}</span>
      </div>
      <div class="attendees">${attendees}</div>
      ${v.rating ? ratedSummary(v) : ratingForm(v)}
    </article>`;
  };

  const renderHistory = () => {
    const host = el('history-list');
    const visits = sortedHistory();
    host.innerHTML = visits.length
      ? visits.map(historyCard).join('')
      : emptyState('No visits logged yet', 'Finish a party with "Complete & Rate" and it will land here for a rating.');
  };

  /* ---------------- rendering: venues ---------------- */

  /** Guesses the crew theme a venue's own tags point at. */
  const bestFitTheme = (v) => {
    const tags = (v.tags || []).map((t) => String(t).toLowerCase());
    const has = (word) => tags.some((t) => t.includes(word));
    if (has('wifi') || has('quiet') || has('table')) return 'Coworking & Chill';
    if (has('craft') || has('late') || has('drink')) return 'Trivia & Social';
    return 'Board Games';
  };

  /** Pro-only venue intel: best-fit theme, live slot demand and a budget tip. */
  const venueInsights = (v) => {
    const slots = num(v.activeSlotCount, 0);
    const demand = slots >= 3 ? 'High' : slots >= 1 ? 'Steady' : 'Quiet';
    const cost = num(v.avgCostUsd, 0);
    const tip = cost <= 20 ? 'Budget pick \u2014 perfect for a first hangout.'
      : cost <= 40 ? 'Mid-range \u2014 split the bill and keep it easy.'
      : 'Splurge spot \u2014 save it for a special crew night.';
    const rows = [
      ['Best-fit theme', bestFitTheme(v)],
      ['Slot demand', `${demand} \u2014 ${count(slots, 'active slot')}`],
      ['Budget tip', tip]
    ];
    const list = (extraClass) => `<ul class="vi-list${extraClass}">${rows.map(([label, text]) =>
      `<li><span>${escapeHtml(label)}</span><strong>${escapeHtml(text)}</strong></li>`).join('')}</ul>`;
    return `<div class="venue-insights">
      <div class="vi-head">Venue Insights</div>
      ${state.pro ? list('')
        : `<div class="vi-locked">${list(' vi-blur')}
          <button class="btn btn-primary btn-sm vi-unlock" data-action="open-paywall">Unlock Venue Insights with Pro</button></div>`}
    </div>`;
  };

  const venueCard = (v) => {
    const rating = typeof v.avgRating === 'number' ? v.avgRating.toFixed(1) : '\u2014';
    const ratingCount = num(v.ratingCount, 0);
    const slots = num(v.activeSlotCount, 0);
    return `<article class="card venue-card" data-venue-id="${attr(v.id)}">
      <div class="card-head">
        <h3 class="card-title">${escapeHtml(v.name)}</h3>
        <span class="badge badge-city">${escapeHtml(v.city)}</span>
      </div>
      <div class="venue-cat">${escapeHtml(v.category)} \u2022 ${escapeHtml(v.neighborhood)}</div>
      <div class="meta-row">
        <span class="rating-live">${rating} \u2605 (${ratingCount} rating${ratingCount === 1 ? '' : 's'})</span>
        <span class="dot">\u2022</span>
        <span class="price">$${escapeHtml(v.avgCostUsd)} pp</span>
        <span class="dot">\u2022</span>
        <span>${slots} active slot${slots === 1 ? '' : 's'}</span>
        ${num(v.queueCount, 0) > 0 ? `<span class="dot">\u2022</span><span>${escapeHtml(v.queueCount)} in queue</span>` : ''}
      </div>
      <div class="venue-badges">
        ${v.publicSafeSpot ? '<span class="badge badge-safe">Public Safe Spot</span>' : ''}
        ${v.featuredPartner ? '<span class="badge badge-featured">Featured Partner</span>' : ''}
        ${v.demoPool ? '<span class="badge badge-demo">Demo Pool</span>' : ''}
      </div>
      <div class="chips">${chips(v.tags)}</div>
      ${venueInsights(v)}
      <div class="card-actions">
        <button class="btn btn-primary btn-sm" data-action="open-venue" data-venue-id="${attr(v.id)}">View Live Slots</button>
        <button class="btn btn-ghost btn-sm" data-action="create-at-venue" data-venue-id="${attr(v.id)}" data-venue-city="${attr(v.city || '')}" data-venue-neighborhood="${attr(v.neighborhood || '')}">+ Create Slot</button>
      </div>
    </article>`;
  };

  const sortedVenues = () => {
    const list = (state.results.venues.length ? state.results.venues : state.venues)
      .filter((v) => state.venueCity === 'All Cities' || v.city === state.venueCity);
    list.sort((a, b) => {
      if (state.venueSort === 'price') return (a.avgCostUsd - b.avgCostUsd) || (b.avgRating - a.avgRating);
      if (state.venueSort === 'slots') return (num(b.activeSlotCount, 0) - num(a.activeSlotCount, 0)) || (b.avgRating - a.avgRating);
      if (state.venueSort === 'name') return String(a.name).localeCompare(String(b.name));
      return (b.avgRating - a.avgRating) || (a.avgCostUsd - b.avgCostUsd);
    });
    return list;
  };

  const renderVenues = () => {
    const host = el('venue-list');
    const venues = sortedVenues();
    host.innerHTML = venues.length
      ? venues.map(venueCard).join('')
      : emptyState('No venues in this city', 'Pick another city or run a search to see every spot.');
  };

  const renderVenueMini = () => {
    const host = el('venue-mini-list');
    const venues = state.results.venues;
    host.innerHTML = venues.length
      ? venues.map(venueCard).join('')
      : emptyState('No venues found', 'Change the theme or budget filter and search again.');
  };

  /* ---------------- rendering: profile ---------------- */

  const renderProfile = () => {
    const s = state.identity;
    if (!s) return;
    const parts = String(s.ageRole || '').split('\u2022');
    el('profile-name').value = s.displayName || '';
    el('profile-age').value = (parts[0] || '').trim().replace(/[^0-9]/g, '');
    el('profile-role').value = parts.length > 1 ? parts.slice(1).join('\u2022').trim() : '';
    ensureCityOption('profile-city', s.city);
    el('profile-city').value = s.city || CITIES[0];
    el('profile-neighborhood').value = s.neighborhood || '';
    el('profile-format').value = s.format || 'Solo';
    el('profile-pace').value = s.pace || 'Chill';
    el('profile-budget-min').value = s.budgetMin ?? 0;
    el('profile-budget-max').value = s.budgetMax ?? 40;
    const badges = s.verificationBadges || [];
    el('profile-id').checked = badges.includes('ID Verified');
    el('profile-linkedin').checked = badges.some((b) => /linkedin/i.test(b));
    el('profile-public').checked = !!s.publicVenuesOnly;
    el('profile-minigroup').checked = !!s.miniGroupPriority;
  };

  /* ---------------- rendering: orchestrator ---------------- */

  const renderAll = (options) => {
    const opts = options || {};
    renderMySlots();
    renderParties();
    renderPlayers();
    renderVenueMini();
    renderVenues();
    renderHistory();
    renderRadar();
    renderPresence();
    applyProState();
    if (window.CrewMap) {
      window.CrewMap.renderVenues(
        state.results.venues.length ? state.results.venues : state.venues,
        allOpenParties(),
        { preserveView: Boolean(opts.preserveView) });
    }
  };

  const applyProState = () => {
    const btn = el('upgrade-btn');
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
  };

  /* ---------------- tabs ---------------- */

  const switchTab = (tab) => {
    if (!TABS.includes(tab)) return;
    state.tab = tab;
    TABS.forEach((name) => {
      const view = el(`view-${name}`);
      if (view) view.classList.toggle('active', name === tab);
    });
    document.querySelectorAll('.tab').forEach((btn) => btn.classList.toggle('active', btn.dataset.tab === tab));

    if (tab === 'explore' && window.CrewMap) {
      window.requestAnimationFrame(() => window.CrewMap.init('venue-map'));
    }
    if (tab === 'profile') renderProfile();
    if (tab === 'friends') renderPresence();
    if (tab === 'history' && state.highlightVisitId) focusVisit(state.highlightVisitId);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const focusVisit = (visitId) => {
    if (!visitId) return;
    window.setTimeout(() => {
      const card = el(`history-card-${visitId}`);
      if (card) card.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 80);
  };

  /* ---------------- filters & search ---------------- */

  const setActivePill = (group, node) => {
    if (!group) return;
    group.querySelectorAll('.pill').forEach((p) => p.classList.toggle('active', p === node));
  };

  const syncBudget = () => {
    let min = clamp(num(val('budget-min'), 0), 0, 100);
    let max = clamp(num(val('budget-max'), 100), 0, 100);
    if (min > max) { const swap = min; min = max; max = swap; el('budget-min').value = min; el('budget-max').value = max; }
    const fill = el('budget-fill');
    if (fill) { fill.style.left = `${min}%`; fill.style.width = `${max - min}%`; }
    el('budget-readout').textContent = `$${min} \u2013 $${max} per person`;
    state.filters.budgetMin = min;
    state.filters.budgetMax = max;
  };

  const readFilters = () => {
    state.filters.city = val('global-city') || 'All Cities';
    state.filters.theme = val('filter-theme') || '';
    state.filters.timeWindow = val('filter-time') || '';
    state.filters.friendsOnly = el('friends-only').checked;
    state.filters.publicSafeOnly = el('public-safe').checked;
    state.filters.verifiedOnly = el('verified-only').checked;
    state.filters.miniGroupOnly = el('mini-group-only').checked;
    syncBudget();
  };

  const doSearch = async (options) => {
    const opts = options || {};
    readFilters();
    if (!opts.silent) {
      el('party-list').innerHTML = loading('Searching parties\u2026');
      el('player-list').innerHTML = loading('Searching players\u2026');
      el('venue-mini-list').innerHTML = loading('Searching venues\u2026');
    }
    try {
      const res = await window.CrewApi.search({ ...state.filters, guestId: myId() });
      state.results = {
        parties: res.parties || [],
        players: res.players || [],
        venues: res.venues || []
      };
      renderAll({ preserveView: Boolean(opts.preserveView) });
      if (!opts.silent) {
        showToast(`Found ${count(state.results.parties.length, 'open party').replace('party s', 'parties')} and ${count(state.results.venues.length, 'venue')} in ${state.filters.city}.`, 'success');
      }
    } catch (err) {
      renderAll({ preserveView: Boolean(opts.preserveView) });
      if (!opts.background) showToast('Search failed: ' + err.message, 'error');
    }
  };

  const resetFilters = () => {
    el('global-city').value = 'All Cities';
    el('filter-theme').value = '';
    el('filter-time').value = '';
    el('budget-min').value = 0;
    el('budget-max').value = 100;
    el('friends-only').checked = false;
    el('public-safe').checked = false;
    el('verified-only').checked = false;
    el('mini-group-only').checked = false;
    state.filters.format = 'All';
    state.filters.pace = 'All';
    setActivePill(el('format-pills'), el('format-pills').querySelector('[data-format="All"]'));
    setActivePill(el('pace-pills'), el('pace-pills').querySelector('[data-pace="All"]'));
    syncBudget();
    loadRadar();
    showToast('Filters reset to All Cities, All Themes, no applied budget window.', 'info');
    doSearch();
  };

  /* ---------------- venue drawer ---------------- */

  const slotCard = (p) => {
    const full = p.filledSlots >= p.totalSlots;
    const distance = num(p.distanceKm, 0);
    const queue = (p.queueGuestNames || []).length;
    return `<div class="slot-card">
      <div class="slot-head">
        <div>
          <div class="slot-title">${escapeHtml(p.title)}</div>
          <div class="meta-row">${escapeHtml(p.theme)} \u2022 ${escapeHtml(p.timeLabel)}</div>
        </div>
        <span class="badge ${full ? 'badge-queue' : 'badge-slot'}">${full ? 'Full' : `${p.totalSlots - p.filledSlots} open`}</span>
      </div>
      <div class="slot-meta">
        <span>$${escapeHtml(p.budgetMin)}-$${escapeHtml(p.budgetMax)} pp</span>
        <span>${escapeHtml(p.filledSlots)}/${escapeHtml(p.totalSlots)} players</span>
        ${distance > 0 ? `<span>${distance} km away</span>` : ''}
        ${queue > 0 ? `<span>${queue} in queue</span>` : ''}
      </div>
      <div class="party-flags">
        ${(p.trustBadges || []).map((b) => `<span class="badge badge-trust">${escapeHtml(b)}</span>`).join('')}
        ${p.miniGroupPriority ? '<span class="badge badge-trust">Mini-group</span>' : ''}
        ${p.midpointMode ? '<span class="badge badge-midpoint">Fair-Midpoint</span>' : ''}
      </div>
      <div class="slot-actions">
        ${full
          ? `<button class="btn btn-accent btn-sm" data-action="queue-party" data-party-id="${attr(p.id)}">Join Waitlist Queue</button>`
          : `<button class="btn btn-primary btn-sm" data-action="join-party" data-party-id="${attr(p.id)}">Join Slot</button>`}
        <button class="btn btn-ghost btn-sm" data-action="complete-party" data-party-id="${attr(p.id)}">Complete &amp; Rate</button>
      </div>
    </div>`;
  };

  const openVenueDrawer = async (venueId) => {
    state.openDrawerVenueId = venueId;
    openDrawer();
    el('drawer-title').textContent = 'Venue';
    el('drawer-sub').textContent = '';
    el('drawer-body').innerHTML = loading('Loading live slots\u2026');
    try {
      const res = await window.CrewApi.venueSlots(venueId);
      renderVenueDrawer(res);
    } catch (err) {
      el('drawer-body').innerHTML = emptyState('Could not load this venue', err.message);
    }
  };

  const renderVenueDrawer = (res) => {
    const v = res.venue || {};
    const rating = typeof v.avgRating === 'number' ? v.avgRating.toFixed(1) : '\u2014';
    el('drawer-title').textContent = v.name || 'Venue';
    el('drawer-sub').innerHTML = `${escapeHtml(v.category || '')} \u2022 ${escapeHtml(v.neighborhood || '')}, ${escapeHtml(v.city || '')} \u2022 ${rating} \u2605 \u2022 $${escapeHtml(v.avgCostUsd)} pp \u2022 ${escapeHtml(res.activeSlotCount)} active slot(s) \u2022 ${escapeHtml(res.queueCount)} in queue`;
    const flags = `<div class="venue-badges">
      ${v.publicSafeSpot ? '<span class="badge badge-safe">Public Safe Spot</span>' : ''}
      ${v.featuredPartner ? '<span class="badge badge-featured">Featured Partner</span>' : ''}
      ${v.demoPool ? '<span class="badge badge-demo">Demo Pool</span>' : ''}
    </div>`;
    const slots = res.slots || [];
    const createHere = v.id
      ? `<button class="btn btn-primary btn-block" data-action="create-at-venue" data-venue-id="${attr(v.id)}" data-venue-city="${attr(v.city || '')}" data-venue-neighborhood="${attr(v.neighborhood || '')}">+ Create Slot Here</button>`
      : '';
    el('drawer-body').innerHTML = createHere
      + flags
      + venueInsights({ ...v, activeSlotCount: res.activeSlotCount })
      + (slots.length
        ? slots.map(slotCard).join('')
        : emptyState('No open slots at this venue yet', 'Be the first to start a party here and your crew will see it instantly.'));
  };

  /* ---------------- custom-spot pins on the map ---------------- */

  /** The party drawer's Join / Queue / Leave / Check-In actions, depending on my membership. */
  const partyDrawerActions = (p) => {
    const full = p.filledSlots >= p.totalSlots;
    const joined = isJoined(p);
    const checkedIn = (p.checkedInGuestIds || []).includes(myId());
    const buttons = [];
    if (joined) {
      buttons.push(`<button class="btn btn-ghost btn-sm" data-action="leave-slot" data-party-id="${attr(p.id)}">Leave Slot</button>`);
      if (!checkedIn) {
        buttons.push(`<button class="btn btn-accent btn-sm" data-action="checkin-party" data-party-id="${attr(p.id)}">Safety Check-In</button>`);
      }
    } else if (full) {
      buttons.push(`<button class="btn btn-accent btn-sm" data-action="queue-party" data-party-id="${attr(p.id)}">Join Waitlist Queue</button>`);
    } else {
      buttons.push(`<button class="btn btn-primary btn-sm" data-action="join-party" data-party-id="${attr(p.id)}">Join Slot</button>`);
    }
    if (p.venueId) {
      buttons.push(`<button class="btn btn-ghost btn-sm" data-action="open-venue" data-venue-id="${attr(p.venueId)}">View Live Slots</button>`);
    }
    return `<div class="card-actions drawer-actions">${buttons.join('')}</div>`;
  };

  /** Full party details in the right drawer: theme, time, budget, members, Fit Score and icebreakers. */
  const partyDrawerBody = (p) => {
    const fit = clamp(num(p.fitScore, 0), 0, 100);
    const distance = num(p.distanceKm, 0);
    const members = (p.memberNames && p.memberNames.length ? p.memberNames : p.members || [])
      .map((m) => `<span class="chip">${escapeHtml(m)}</span>`).join('');
    const icebreakers = (p.icebreakers || []).map((i) => `<li>${escapeHtml(i)}</li>`).join('');
    return `<div class="slot-card">
        <div class="meta-row">
          <span class="badge badge-theme">${escapeHtml(p.theme || '')}</span>
          <span>${escapeHtml(p.timeLabel || '')}</span>
        </div>
        <div class="slot-meta">
          <span>$${escapeHtml(p.budgetMin)}-$${escapeHtml(p.budgetMax)} pp</span>
          <span>${escapeHtml(p.filledSlots)}/${escapeHtml(p.totalSlots)} players</span>
          ${distance > 0 ? `<span>${distance} km away</span>` : ''}
        </div>
        <div class="venue-line">Venue: <strong>${escapeHtml(p.venueName || 'Custom Spot')}</strong></div>
        <div class="party-flags">${partyFlags(p)}</div>
      </div>
      <div class="fit">
        <div class="fit-label"><span>Match Fit Score</span><strong>${fit}%</strong></div>
        <div class="fit-bar"><span style="width:${fit}%"></span></div>
      </div>
      <div class="chips">${members}</div>
      ${icebreakers ? `<div class="icebreakers"><div class="icebreakers-label">AI Icebreakers</div><ul>${icebreakers}</ul></div>` : ''}
      ${partyDrawerActions(p)}`;
  };

  /**
   * Opens the right drawer with a party's full details and a live Join Slot action. Used by the map
   * pins of open parties and custom spots, so a tap lets the user join straight from the map.
   */
  const openPartyDrawer = (partyId) => {
    const party = findParty(partyId);
    if (!party) {
      showToast('That slot is no longer open.', 'info');
      return;
    }
    // No venue id: a background venue refresh must not overwrite the party contents.
    state.openDrawerVenueId = null;
    openDrawer();
    el('drawer-title').textContent = party.title || 'Open Party';
    el('drawer-sub').innerHTML = `${escapeHtml(party.theme || '')} \u2022 ${escapeHtml(party.timeLabel || '')} \u2022 ${escapeHtml(party.venueName || party.city || '')}`;
    el('drawer-body').innerHTML = partyDrawerBody(party);
  };

  /**
   * Click on a custom-spot pin (a slot created by another user straight on the map). Opens the party
   * drawer so the details and the Join Slot button are one tap away.
   */
  const focusParty = (partyId) => {
    openPartyDrawer(partyId);
  };

  /* ---------------- create party ---------------- */

  const venueOptionsHtml = (city) => {
    const empty = '<option value="">\u2014 Map spot / Custom location \u2014</option>';
    const venues = city && city !== 'All Cities' ? state.venues.filter((v) => v.city === city) : state.venues;
    const list = venues.length ? venues : state.venues;
    if (!list.length) return empty;
    return empty + list.map((v) =>
      `<option value="${attr(v.id)}">${escapeHtml(v.name)} \u2014 ${escapeHtml(v.neighborhood)} ($${escapeHtml(v.avgCostUsd)})</option>`).join('');
  };

  const fillVenueSelect = (city) => { el('create-venue').innerHTML = venueOptionsHtml(city); };

  const buddyOptionsHtml = (checkedIds) => {
    const ids = checkedIds || [];
    const buddies = state.friends.filter((f) => f.status === 'CORE_BUDDY' || f.status === 'FRIEND');
    if (!buddies.length) return '<p class="hint">No saved buddies yet. Add players from Explore &amp; Map first.</p>';
    return buddies.map((f) =>
      `<label class="check-row"><input type="checkbox" data-buddy value="${attr(f.id)}"${ids.includes(f.id) ? ' checked' : ''} /><span>${escapeHtml(f.name)} <em>${escapeHtml(f.ageRole || '')}</em></span></label>`).join('');
  };

  const applyCityOptions = () => {
    const cities = [];
    const remember = (city) => {
      if (city && city !== 'All Cities' && !cities.includes(city)) cities.push(city);
    };

    ['create-city', 'venue-city-filter'].forEach((id) => {
      const select = el(id);
      if (!select) return;
      // Whatever is already in the dropdown (e.g. a GPS-detected "Астана") stays available, so
      // opening the Create Party modal never wipes the city the user is browsing.
      [...select.options].forEach((option) => remember(option.value));
    });
    CITIES.forEach(remember);
    remember(state.filters.city);
    remember(state.identity && state.identity.city);
    remember(state.selectedSpot && state.selectedSpot.city);

    ['create-city', 'venue-city-filter'].forEach((id) => {
      const select = el(id);
      if (!select) return;
      const current = select.value;
      const list = id === 'venue-city-filter' ? ['All Cities', ...cities] : cities;
      select.innerHTML = list
        .map((city) => `<option value="${escapeHtml(city)}">${escapeHtml(city)}</option>`).join('');
      if (list.includes(current)) select.value = current;
    });
  };

  const openCreateModal = (options) => {
    const opts = options || {};
    // Opening the form fresh cancels any pending "pick a spot on the map" arming.
    state.pickingMapSpot = false;
    hideMapPickBanner();
    el('create-theme').innerHTML = THEMES.map((t) => `<option value="${escapeHtml(t)}">${escapeHtml(t)}</option>`).join('');
    applyCityOptions();

    const city = opts.city || (state.filters.city !== 'All Cities' ? state.filters.city : CITIES[0]);
    ensureCityOption('create-city', city);
    el('create-city').value = city;
    fillVenueSelect(city);

    const venueId = opts.venueId || (el('create-venue').options[0] ? el('create-venue').options[0].value : '');
    el('create-venue').value = venueId;
    if (venueId && el('create-venue').value !== venueId) {
      // A venue opened straight from its drawer may not be in the current city list yet: add it so
      // the pre-filled selection survives and the created slot points at the right place.
      const known = findVenue(venueId);
      el('create-venue').insertAdjacentHTML('beforeend',
        `<option value="${attr(venueId)}">${escapeHtml(known ? known.name : 'Selected venue')}</option>`);
      el('create-venue').value = venueId;
    }
    const venue = findVenue(venueId);

    el('create-title').value = opts.title || '';
    el('create-theme').value = opts.theme && THEMES.includes(opts.theme) ? opts.theme : THEMES[0];
    el('create-neighborhood').value = opts.neighborhood || (venue ? venue.neighborhood : '');
    el('create-slots').value = opts.totalSlots || 4;
    el('create-budget-min').value = typeof opts.budgetMin === 'number' ? opts.budgetMin : 10;
    el('create-budget-max').value = typeof opts.budgetMax === 'number' ? opts.budgetMax : 30;
    el('create-time').value = opts.timeLabel || '';
    el('create-format').value = opts.format || 'Solo';
    el('create-pace').value = opts.pace || 'Chill';
    el('create-minigroup').checked = !!opts.miniGroupPriority;
    el('create-buddies').innerHTML = buddyOptionsHtml(opts.buddyIds || []);
    el('create-midpoint').checked = !!opts.midpointMode;
    const spot = opts.spot || null;
    el('create-spot-name').value = opts.spotName || (spot ? spot.name || '' : '');
    state.selectedSpot = spot;
    state.spotNameDirty = false;
    toggleMidpointFields();

    openModal('create-modal');
  };

  const toggleMidpointFields = () => {
    const on = el('create-midpoint').checked;
    el('create-venue-field').style.display = on ? 'none' : '';
    el('create-custom-spot-field').style.display = on ? 'none' : '';
    el('midpoint-hint').hidden = !on;
  };

  const checkedBuddyIds = () =>
    Array.from(document.querySelectorAll('#create-buddies input[data-buddy]:checked')).map((box) => box.value);

  const syncVenueDetails = () => {
    const venue = findVenue(val('create-venue'));
    if (!venue) return;
    if (venue.neighborhood) el('create-neighborhood').value = venue.neighborhood;
    if (venue.city) ensureCityOption('create-city', venue.city) && (el('create-city').value = venue.city);
  };

  const submitCreate = async () => {
    let min = Math.max(0, num(val('create-budget-min'), 0));
    let max = Math.max(0, num(val('create-budget-max'), 0));
    if (max < min) { const swap = min; min = max; max = swap; }

    const midpointMode = el('create-midpoint').checked;
    const spot = midpointMode ? null : state.selectedSpot;
    let venueId = val('create-venue');

    // A map click only snaps to a CrewParty venue that sits within 50 m — otherwise it stays a custom spot.
    if (!midpointMode && !venueId && spot && typeof spot.lat === 'number') {
      const venue = nearestVenue(spot.lat, spot.lng, 0.05);
      if (venue) venueId = venue.id;
    }

    const payload = {
      title: String(val('create-title') || '').trim(),
      theme: val('create-theme'),
      city: val('create-city'),
      neighborhood: String(val('create-neighborhood') || '').trim(),
      totalSlots: clamp(num(val('create-slots'), 4), 2, 8),
      budgetMin: min,
      budgetMax: max,
      timeLabel: String(val('create-time') || '').trim(),
      venueId: midpointMode ? '' : venueId,
      spotName: spot ? String(spot.name || '').trim() : '',
      spotCategory: spot ? String(spot.category || '').trim() : '',
      spotLat: spot && typeof spot.lat === 'number' ? spot.lat : null,
      spotLng: spot && typeof spot.lng === 'number' ? spot.lng : null,
      isListedPlace: !!(spot && spot.isListedPlace),
      buddyIds: checkedBuddyIds(),
      guestId: myId(),
      midpointMode,
      format: val('create-format'),
      pace: val('create-pace'),
      miniGroupPriority: el('create-minigroup').checked
    };

    if (!payload.title) { showToast('Please give your party a title.', 'error'); return; }
    if (!payload.timeLabel) payload.timeLabel = 'This weekend';
    if (!payload.neighborhood) payload.neighborhood = payload.city;
    // Without Fair-Midpoint a location is mandatory: either a venue, or a spot picked on the map.
    const hasSpot = Boolean(state.selectedSpot
      && (typeof state.selectedSpot.lat === 'number' || String(state.selectedSpot.name || '').trim()));
    if (!midpointMode && !payload.venueId && !hasSpot && !payload.spotName) {
      showToast('Select a venue, pick a spot on the map, or enable Fair-Midpoint mode', 'error');
      return;
    }

    const submitBtn = document.querySelector('#create-form button[type="submit"]');
    if (submitBtn) submitBtn.disabled = true;
    try {
      const party = await window.CrewApi.createParty(payload);
      closeAllModals();
      await refreshAfterChange(`Party "${party.title}" is live. ${Math.max(0, party.totalSlots - party.filledSlots)} slot(s) still open.`);
      switchTab('explore');
      window.setTimeout(() => {
        const card = document.querySelector(`.party-card[data-party-id="${attr(party.id)}"]`);
        if (card) {
          card.scrollIntoView({ behavior: 'smooth', block: 'center' });
          card.classList.add('flash');
          window.setTimeout(() => card.classList.remove('flash'), 1600);
        }
      }, 140);
    } catch (err) {
      if (!handleLimitError(err)) showToast('Could not create party: ' + err.message, 'error');
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  };

  /** Opens Create Party pre-filled with a venue, closing the drawer that hosted the button. */
  const startPartyAtVenue = (node) => {
    closeDrawer();
    openCreateModal({
      venueId: node.dataset.venueId,
      city: node.dataset.venueCity,
      neighborhood: node.dataset.venueNeighborhood
    });
  };

  const startPartyWithFriends = () => {
    const buddies = state.friends.filter((f) => f.status === 'CORE_BUDDY' || f.status === 'FRIEND').map((f) => f.id);
    const friends = state.presence.acceptedFriends || [];
    if (!buddies.length && !friends.length) {
      showToast('Add at least one friend or buddy first, then start a party together.', 'error');
      return;
    }
    openCreateModal({ title: 'Crew Night with My Friends', buddyIds: buddies });
    showToast('Pick a theme and venue, then create your party.', 'info');
  };

  const runDemoPreset = () => {
    const city = state.filters.city !== 'All Cities' ? state.filters.city : 'Chicago';
    const buddies = state.friends.filter((f) => f.status === 'CORE_BUDDY' || f.status === 'FRIEND').slice(0, 2).map((f) => f.id);
    const venue = state.venues.find((v) => v.city === city) || state.venues[0];
    openCreateModal({
      title: 'Board Game Night \u2014 2 Buddies Seeking 2 Players',
      theme: 'Board Games',
      city,
      totalSlots: 4,
      budgetMin: 10,
      budgetMax: 30,
      timeLabel: 'Sat 3:00 PM',
      buddyIds: buddies,
      venueId: venue ? venue.id : ''
    });
    submitCreate();
  };

  /* ---------------- map spot picking ---------------- */

  /** Normalizes a picked spot into the shape the Create Party form and the API expect. */
  const normalizeSpot = (spot) => ({
    lat: typeof spot.lat === 'number' ? spot.lat : null,
    lng: typeof spot.lng === 'number' ? spot.lng : null,
    name: spot.name || '',
    category: spot.category || '',
    city: spot.city || '',
    neighborhood: spot.neighborhood || '',
    postcode: spot.postcode || '',
    isListedPlace: !!spot.isListedPlace
  });

  /** Parks the Create Party form values while the user temporarily leaves the modal to pick a spot. */
  const captureCreateDraft = () => ({
    title: val('create-title'),
    theme: val('create-theme'),
    city: val('create-city'),
    neighborhood: val('create-neighborhood'),
    totalSlots: num(val('create-slots'), 4),
    budgetMin: num(val('create-budget-min'), 10),
    budgetMax: num(val('create-budget-max'), 30),
    timeLabel: val('create-time'),
    format: val('create-format'),
    pace: val('create-pace'),
    miniGroupPriority: el('create-minigroup').checked,
    buddyIds: checkedBuddyIds(),
    midpointMode: el('create-midpoint').checked
  });

  /**
   * Leaves the Create Party modal, parks the typed values and arms the map: the next click anywhere
   * on the map reopens the modal with those values plus the chosen coordinates, name, city and ZIP.
   */
  const pickSpotOnMap = () => {
    state.createDraft = captureCreateDraft();
    state.pickingMapSpot = true;
    closeAllModals();
    const panel = document.querySelector('.map-panel');
    if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'center' });
    showMapPickBanner();
    showToast('Click anywhere on the map to set the party spot.', 'info');
  };

  const handleSpotPick = (spot) => {
    const selected = normalizeSpot(spot);
    const fromPickFlow = state.pickingMapSpot;
    state.pickingMapSpot = false;
    hideMapPickBanner();
    // Coming back from "Pick Spot on Map": restore the parked form values together with the new spot.
    if (fromPickFlow) {
      openCreateModal({ ...(state.createDraft || {}), spot: selected, spotName: selected.name });
      state.createDraft = null;
    } else if (!el('create-modal').classList.contains('open')) {
      // Open the modal first: openCreateModal installs the spot, so nothing wipes it afterwards.
      openCreateModal({ spot: selected });
    } else {
      state.selectedSpot = selected;
    }
    state.spotNameDirty = false;
    el('create-midpoint').checked = false;
    toggleMidpointFields();
    el('create-venue').value = '';
    const input = el('create-spot-name');
    if (input) { input.value = selected.name; if (!fromPickFlow) input.focus(); }
    showToast(selected.isListedPlace
      ? `Real spot selected: ${selected.name}. Set the details and start your party.`
      : 'Spot dropped on the map. Name it and create your party.', 'info');
  };

  const handleSpotResolved = (spot) => {
    if (!state.selectedSpot) return; // the picker was superseded by an existing venue meanwhile
    // A name the user typed by hand always beats a late geocoder answer.
    const keepTypedName = state.spotNameDirty;
    state.selectedSpot = {
      ...state.selectedSpot,
      lat: spot.lat,
      lng: spot.lng,
      name: keepTypedName ? state.selectedSpot.name : (spot.name || state.selectedSpot.name),
      city: spot.city || state.selectedSpot.city || '',
      neighborhood: spot.neighborhood || state.selectedSpot.neighborhood || '',
      postcode: spot.postcode || state.selectedSpot.postcode || '',
      isListedPlace: keepTypedName ? false : !!spot.isListedPlace
    };

    // The click already knows the city, district + ZIP, so the form never has to guess them.
    if (spot.city && ensureCityOption('create-city', spot.city)) {
      el('create-city').value = spot.city;
      fillVenueSelect(spot.city);
    }
    if (spot.neighborhood) el('create-neighborhood').value = spot.neighborhood;
    if (!keepTypedName && spot.name) el('create-spot-name').value = spot.name;

    if (keepTypedName) return;
    if (state.selectedSpot.isListedPlace) return; // a real organization is bound on the backend
    if (spot.name) showToast(`Found: ${spot.name}.`, 'info');
    else showToast('No named place here \u2014 type a Custom Spot Name for your spot.', 'info');
  };

  /** Typing a custom name means "not a listed venue": detach any selected venue. */
  const onSpotNameTyped = (value) => {
    const name = String(value || '').trim();
    state.spotNameDirty = true;
    const select = el('create-venue');
    if (select && select.value) select.value = '';
    const base = state.selectedSpot || {};
    state.selectedSpot = {
      lat: typeof base.lat === 'number' ? base.lat : null,
      lng: typeof base.lng === 'number' ? base.lng : null,
      name,
      category: base.category || '',
      city: base.city || '',
      neighborhood: base.neighborhood || '',
      postcode: base.postcode || '',
      isListedPlace: false
    };
  };

  const startFromRadar = (node) => {
    const event = (state.radar || []).find((e) => String(e.id) === String(node.dataset.eventId));
    if (!event) { showToast('That event is no longer on the radar.', 'error'); return; }
    const venue = nearestVenue(event.lat, event.lng);
    openCreateModal({
      title: event.title,
      theme: RADAR_THEME[event.category] || 'Outdoor Casual',
      city: event.city,
      timeLabel: event.whenLabel,
      venueId: venue ? venue.id : '',
      budgetMin: 0,
      budgetMax: Math.max(20, num(event.costPerPerson, 0) + 20),
      neighborhood: venue ? venue.neighborhood : ''
    });
    showToast(`Starting a crew at ${event.title}.`, 'info');
  };

  /* ---------------- GPS city detection ---------------- */

  const ensureCityOption = (selectId, city) => {
    const select = el(selectId);
    if (!select || !city) return false;
    if ([...select.options].some((o) => o.value === city)) return true;
    select.insertAdjacentHTML('beforeend', `<option value="${escapeHtml(city)}">${escapeHtml(city)}</option>`);
    return true;
  };

  /** Reverse geocode GPS coords into a real city name so the backend never falls back to "Your Area". */
  const reverseGeocodeCity = async (lat, lng) => {
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&lat=${lat}&lon=${lng}`;
      const response = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!response.ok) return '';
      const data = await response.json();
      const address = data.address || {};
      return address.city || address.town || address.state || '';
    } catch (e) {
      return '';
    }
  };

  const detectCity = () => {
    if (!navigator.geolocation) {
      showToast('GPS is not available in this browser \u2014 pick a city from the dropdown instead.', 'error');
      return;
    }
    showToast('Detecting your city\u2026', 'info');
    navigator.geolocation.getCurrentPosition(async (position) => {
      const lat = position.coords.latitude;
      const lng = position.coords.longitude;
      try {
        const cityName = await reverseGeocodeCity(lat, lng);
        const res = await window.CrewApi.gpsSeed(lat, lng, cityName);
        ensureCityOption('global-city', res.city);
        ensureCityOption('create-city', res.city);
        ensureCityOption('venue-city-filter', res.city);
        ensureCityOption('profile-city', res.city);
        // One GPS fix syncs every city filter at once so Explore, Rated Venues, Create and Profile all
        // open on the detected city instead of the demo city the session was seeded with.
        state.filters.city = res.city;
        el('global-city').value = res.city;
        state.venueCity = res.city;
        el('venue-city-filter').value = res.city;
        el('create-city').value = res.city;
        el('profile-city').value = res.city;
        // Persist the live GPS fix on the session so distanceKm to nearby slots is measured from
        // the real position instead of the demo city the session was seeded with.
        try {
          const updated = await window.CrewApi.updateProfile({
            guestId: myId(),
            city: res.city || cityName,
            lat,
            lng
          });
          state.identity = { ...state.identity, ...updated };
          renderIdentity();
        } catch (e) { /* seeding still works even if the profile sync fails */ }
        await reloadAll();
        await loadRadar();
        await doSearch({ silent: true });
        renderAll();
        if (window.CrewMap) {
          window.CrewMap.init('venue-map');
          window.CrewMap.centerOn(res.centerLat, res.centerLng, 13);
          window.CrewMap.setUserLocation(lat, lng);
        }
        showToast(`Demo pool ready in ${res.city}: ${count(res.venuesCreated, 'venue')} and ${count(res.partiesCreated, 'open slot')}.`, 'success');
      } catch (err) {
        showToast('GPS seeding failed: ' + err.message, 'error');
      }
    }, () => {
      showToast('Location was blocked \u2014 pick a city from the dropdown instead.', 'error');
    }, { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 });
  };

  /**
   * Drops the "You are here" marker on the map. Uses a live GPS fix when the browser already has
   * permission, and otherwise falls back to the coordinates stored on the session.
   */
  const initUserLocation = () => {
    if (!window.CrewMap || !window.CrewMap.setUserLocation) return;
    const applySessionCoords = () => {
      const s = state.identity;
      if (s && typeof s.lat === 'number' && typeof s.lng === 'number' && (s.lat !== 0 || s.lng !== 0)) {
        window.CrewMap.setUserLocation(s.lat, s.lng);
      }
    };
    const locate = () => navigator.geolocation.getCurrentPosition(
      (pos) => window.CrewMap.setUserLocation(pos.coords.latitude, pos.coords.longitude),
      applySessionCoords,
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 });

    if (navigator.geolocation && navigator.permissions && navigator.permissions.query) {
      navigator.permissions.query({ name: 'geolocation' })
        .then((status) => { if (status.state === 'granted') locate(); else applySessionCoords(); })
        .catch(applySessionCoords);
    } else {
      applySessionCoords();
    }
  };

  /* ---------------- join / queue / check-in ---------------- */

  const joinSlot = async (partyId) => {
    try {
      const party = await window.CrewApi.joinParty(partyId, myId());
      closeDrawer();
      await refreshAfterChange(`You joined "${party.title}". ${Math.max(0, party.totalSlots - party.filledSlots)} slot(s) left.`);
    } catch (err) {
      if (!handleLimitError(err)) showToast('Could not join slot: ' + err.message, 'error');
    }
  };

  const joinQueue = async (partyId) => {
    try {
      const party = await window.CrewApi.queueForSlot(partyId, myId(), (state.identity && state.identity.displayName) || 'Guest');
      closeDrawer();
      await refreshAfterChange(`Added to the waitlist for "${party.title}".`);
    } catch (err) {
      showToast('Could not join the queue: ' + err.message, 'error');
    }
  };

  const checkIn = async (partyId) => {
    try {
      const party = await window.CrewApi.checkIn(partyId, myId());
      await refreshAfterChange(`Safety check-in confirmed for "${party.title}". See you there.`);
    } catch (err) {
      showToast('Could not check in: ' + err.message, 'error');
    }
  };

  const completeParty = async (partyId) => {
    try {
      const visit = await window.CrewApi.completeParty(partyId);
      state.highlightVisitId = visit && visit.id ? visit.id : null;
      await refreshAfterChange(`Visit logged for ${(visit && visit.venueName) || 'the venue'}. Rate it to help the crew.`);
      switchTab('history');
      focusVisit(state.highlightVisitId);
    } catch (err) {
      showToast('Could not complete party: ' + err.message, 'error');
    }
  };

  /** Leaves a slot; a host leaving their own empty party closes it on the backend. */
  const leaveSlot = async (partyId) => {
    try {
      const party = await window.CrewApi.leaveParty(partyId, myId());
      const message = party && party.status === 'COMPLETED'
        ? `You left "${party.title}" — it was yours and had nobody else in it, so it is now closed.`
        : `You left "${party.title}".`;
      await refreshAfterChange(message);
    } catch (err) {
      showToast('Could not leave the slot: ' + err.message, 'error');
    }
  };

  /* ---------------- friends & invites ---------------- */

  const addFriend = async (friendId) => {
    try {
      const friend = await window.CrewApi.addFriend(friendId);
      await refreshAfterChange(`${(friend && friend.name) || 'That player'} is now in your crew.`);
    } catch (err) {
      showToast('Could not add friend: ' + err.message, 'error');
    }
  };

  const requestFriend = async (guestId) => {
    try {
      const relation = await window.CrewApi.requestFriend(myId(), guestId);
      await pollPresence({ adopt: true });
      showToast(`Friend request sent to ${relation.toName}.`, 'success');
    } catch (err) {
      showToast('Could not send friend request: ' + err.message, 'error');
    }
  };

  const acceptFriend = async (requestId) => {
    try {
      const relation = await window.CrewApi.acceptFriend(requestId);
      removeBanner(`req:${requestId}`);
      if (state.seenKeys) state.seenKeys.delete(`req:${requestId}`);
      await pollPresence({ adopt: true });
      showToast(`${relation.fromName} is now in your crew.`, 'success');
    } catch (err) {
      showToast('Could not accept friend request: ' + err.message, 'error');
    }
  };

  const declineFriend = (requestId) => {
    state.dismissedRequests.add(requestId);
    removeBanner(`req:${requestId}`);
    state.pending = state.pending.filter((n) => !(n.kind === 'friend' && n.request.id === requestId));
    renderNotifPanel(state.pending);
    const bell = el('notif-count');
    bell.textContent = state.pending.length;
    bell.hidden = state.pending.length === 0;
    showToast('Friend request dismissed.', 'info');
  };

  const acceptInvite = async (inviteId) => {
    try {
      const invite = await window.CrewApi.respondInvite(inviteId, 'ACCEPT');
      removeBanner(`invite:${inviteId}`);
      if (state.seenKeys) state.seenKeys.delete(`invite:${inviteId}`);
      await refreshAfterChange(`You joined "${invite.partyTitle}" from the invitation.`);
    } catch (err) {
      if (!handleLimitError(err)) showToast('Could not accept the invitation: ' + err.message, 'error');
    }
  };

  const declineInvite = async (inviteId) => {
    try {
      await window.CrewApi.respondInvite(inviteId, 'DECLINE');
      removeBanner(`invite:${inviteId}`);
      await pollPresence({ adopt: true });
      showToast('Invitation declined.', 'info');
    } catch (err) {
      showToast('Could not decline the invitation: ' + err.message, 'error');
    }
  };

  const inviteToSlot = (guestId) => {
    const guest = findGuestById(guestId);
    const slots = mySlots();
    if (!slots.length) {
      showToast('Create a party first, then invite people into its free slots.', 'error');
      return;
    }
    state.chooser = { mode: 'invite-guest', guestId };
    el('chooser-title').textContent = `Invite ${guest ? guest.displayName : 'this guest'} to a slot`;
    el('chooser-list').innerHTML = slots.map((p) =>
      `<div class="chooser-item">
        <div class="ci-main">
          <div class="ci-title">${escapeHtml(p.title)}</div>
          <div class="ci-sub">${escapeHtml(p.theme)} \u2022 ${escapeHtml(p.city)} \u2022 ${escapeHtml(p.filledSlots)}/${escapeHtml(p.totalSlots)} players</div>
        </div>
        <button class="btn btn-primary btn-sm" data-action="choose-slot" data-party-id="${attr(p.id)}">Invite</button>
      </div>`).join('');
    openModal('chooser-modal');
  };

  const chooseSlot = async (partyId) => {
    if (!state.chooser || state.chooser.mode !== 'invite-guest') return;
    const guestId = state.chooser.guestId;
    try {
      const invite = await window.CrewApi.createInvite(myId(), guestId, partyId);
      closeAllModals();
      showToast(`Invitation sent to ${invite.fromName ? '' : ''}${findGuestById(guestId) ? findGuestById(guestId).displayName : 'your friend'}.`, 'success');
    } catch (err) {
      showToast('Could not send the invite: ' + err.message, 'error');
    }
  };

  const sendInviteFromPanel = async () => {
    const slotId = val('invite-slot-select');
    const friendId = val('invite-friend-select');
    if (!slotId || !friendId) { showToast('Pick a slot and a friend to invite.', 'error'); return; }
    try {
      await window.CrewApi.createInvite(myId(), friendId, slotId);
      const guest = findGuestById(friendId);
      showToast(`Invitation sent to ${guest ? guest.displayName : 'your friend'}.`, 'success');
    } catch (err) {
      showToast('Could not send the invite: ' + err.message, 'error');
    }
  };

  /** "Invite Friend" straight from a My Open Slots card: pick one of my crew for this slot. */
  const inviteFriendToSlot = (partyId) => {
    const party = findParty(partyId);
    if (!party) { showToast('That slot is no longer open.', 'error'); return; }
    const relations = state.presence.acceptedFriends || [];
    if (!relations.length) {
      showToast('Add someone to your crew first, then invite them into this slot.', 'error');
      return;
    }
    state.chooser = { mode: 'invite-friend', partyId };
    el('chooser-title').textContent = `Invite a friend to "${party.title}"`;
    el('chooser-list').innerHTML = relations.map((r) => {
      const name = r.fromGuestId === myId() ? r.toName : r.fromName;
      const id = r.fromGuestId === myId() ? r.toGuestId : r.fromGuestId;
      return `<div class="chooser-item">
        <div class="ci-main">
          <div class="ci-title">${escapeHtml(name)}</div>
          <div class="ci-sub">In your crew</div>
        </div>
        <button class="btn btn-primary btn-sm" data-action="choose-friend" data-guest-id="${attr(id)}">Invite</button>
      </div>`;
    }).join('');
    openModal('chooser-modal');
  };

  const chooseFriend = async (guestId) => {
    if (!state.chooser || state.chooser.mode !== 'invite-friend') return;
    const partyId = state.chooser.partyId;
    try {
      await window.CrewApi.createInvite(myId(), guestId, partyId);
      closeAllModals();
      const guest = findGuestById(guestId);
      showToast(`Invitation sent to ${guest ? guest.displayName : 'your friend'}.`, 'success');
    } catch (err) {
      showToast('Could not send the invite: ' + err.message, 'error');
    }
  };

  /* ---------------- rating ---------------- */

  const draftFor = (visitId) => {
    if (!state.drafts[visitId]) state.drafts[visitId] = { rating: 0, tags: [] };
    return state.drafts[visitId];
  };

  const paintStars = (form) => {
    const rating = draftFor(form.dataset.visitId).rating;
    form.querySelectorAll('.star').forEach((star) => {
      const value = Number(star.dataset.value);
      star.classList.toggle('is-selected', value <= rating);
      star.setAttribute('aria-checked', String(value === rating));
    });
    const label = form.querySelector('.star-value');
    if (label) label.textContent = rating ? `${rating} / 5` : 'Not rated';
  };

  const pickStar = (star) => {
    const form = star.closest('.rating-form');
    if (!form) return;
    draftFor(form.dataset.visitId).rating = Number(star.dataset.value);
    paintStars(form);
  };

  const toggleTag = (chip) => {
    const form = chip.closest('.rating-form');
    if (!form) return;
    const draft = draftFor(form.dataset.visitId);
    const tag = chip.dataset.tag;
    const index = draft.tags.indexOf(tag);
    if (index === -1) draft.tags.push(tag); else draft.tags.splice(index, 1);
    chip.classList.toggle('chip-active', index === -1);
    chip.setAttribute('aria-pressed', String(index === -1));
  };

  const submitRating = async (visitId) => {
    const draft = state.drafts[visitId] || { rating: 0, tags: [] };
    const commentBox = el(`comment-${visitId}`);
    const comment = commentBox ? String(commentBox.value || '').trim() : '';
    if (!draft.rating) { showToast('Pick a star rating from 1 to 5 first.', 'error'); return; }
    try {
      const updated = await window.CrewApi.rateVisit(visitId, { rating: draft.rating, tags: draft.tags.slice(), comment });
      delete state.drafts[visitId];
      state.highlightVisitId = null;
      await reloadAll();
      renderVenues();
      renderVenueMini();
      renderHistory();
      const venue = findVenue(updated.venueId);
      const summary = venue ? `${venue.avgRating.toFixed(1)} \u2605 (${count(num(venue.ratingCount, 0), 'rating')})` : count(draft.rating, 'star');
      showToast(`Thanks! ${(updated && updated.venueName) || 'The venue'} is now ${summary}.`, 'success');
    } catch (err) {
      showToast('Could not save your rating: ' + err.message, 'error');
    }
  };

  /* ---------------- profile ---------------- */

  const submitProfile = async (event) => {
    event.preventDefault();
    const age = clamp(num(val('profile-age'), 0), 0, 120);
    const role = String(val('profile-role') || '').trim();
    const ageRole = age ? `${age}${role ? ` \u2022 ${role}` : ''}` : role;

    let min = Math.max(0, num(val('profile-budget-min'), 0));
    let max = Math.max(0, num(val('profile-budget-max'), 40));
    if (max < min) { const swap = min; min = max; max = swap; }

    const badges = [];
    if (el('profile-id').checked) badges.push('ID Verified');
    if (el('profile-linkedin').checked) badges.push('LinkedIn Verified');
    if (badges.length === 0) badges.push('Shared Interests');

    const payload = {
      guestId: myId(),
      displayName: String(val('profile-name') || '').trim() || undefined,
      ageRole: ageRole || undefined,
      city: val('profile-city'),
      neighborhood: String(val('profile-neighborhood') || '').trim() || undefined,
      budgetMin: min,
      budgetMax: max,
      format: val('profile-format'),
      pace: val('profile-pace'),
      publicVenuesOnly: el('profile-public').checked,
      miniGroupPriority: el('profile-minigroup').checked,
      verificationBadges: badges
    };

    try {
      const updated = await window.CrewApi.updateProfile(payload);
      state.identity = { ...state.identity, ...updated };
      renderIdentity();
      applyProfileDefaults();
      showToast('Profile saved. Your defaults now shape matching and invites.', 'success');
    } catch (err) {
      showToast('Could not save your profile: ' + err.message, 'error');
    }
  };

  /* ---------------- paywall ---------------- */

  /** Recognises a backend Free-tier limit message and, instead of a plain error, opens the paywall. */
  const handleLimitError = (err) => {
    const message = String((err && err.message) || '');
    if (!/Free plan limit|CrewParty Pro feature|Pro for unlimited/i.test(message)) return false;
    showToast(`${message} Opening CrewParty Pro\u2026`, 'info');
    openPaywall();
    return true;
  };

  const openPaywall = () => {
    // The venue drawer sits below the modal anyway; closing it avoids a stale panel behind the checkout.
    closeDrawer();
    if (state.pro) {
      el('paywall-form').hidden = true;
      el('paywall-success').hidden = false;
    } else {
      el('paywall-form').hidden = false;
      el('paywall-success').hidden = true;
    }
    openModal('paywall-modal');
  };

  const startPro = (button) => {
    button.disabled = true;
    button.textContent = 'Processing\u2026';
    window.CrewApi.checkout(myId()).then((res) => {
      state.pro = true;
      if (state.identity) state.identity.pro = true;
      try { window.localStorage.setItem(PRO_KEY, 'true'); } catch (e) { /* storage may be unavailable */ }
      applyProState();
      renderIdentity();
      renderVenues();
      el('paywall-form').hidden = true;
      el('paywall-success').hidden = false;
      showToast(`${(res && res.plan) || 'CrewParty Pro'} unlocked${res && res.amountUsd ? ` for $${res.amountUsd}/mo` : ''}. Welcome aboard!`, 'success');
    }).catch((err) => {
      showToast('Checkout failed: ' + err.message, 'error');
    }).then(() => {
      button.disabled = false;
      button.textContent = 'Start Pro \u2014 Test Mode';
    });
  };

  /* ---------------- event wiring ---------------- */

  const CLICK_ACTIONS = {
    'switch-tab': (node) => switchTab(node.dataset.tab),
    'open-second-tab': () => window.open(window.location.href, '_blank'),
    'toggle-notif': (node) => toggleNotif(node),
    'demo': () => runDemoPreset(),
    'open-paywall': () => openPaywall(),
    'close-modal': (node) => closeModalFrom(node),
    'start-pro': (node) => startPro(node),
    'search': () => doSearch(),
    'reset-filters': () => resetFilters(),
    'open-create': () => openCreateModal({}),
    'pick-spot-on-map': () => pickSpotOnMap(),
    'gps-detect': () => detectCity(),
    'pick-format': (node) => { state.filters.format = node.dataset.format; setActivePill(el('format-pills'), node); },
    'pick-pace': (node) => { state.filters.pace = node.dataset.pace; setActivePill(el('pace-pills'), node); },
    'close-drawer': () => closeDrawer(),
    'start-party-with-friends': () => startPartyWithFriends(),
    'open-venue': (node) => openVenueDrawer(node.dataset.venueId),
    'create-at-venue': (node) => startPartyAtVenue(node),
    'join-party': (node) => joinSlot(node.dataset.partyId),
    'queue-party': (node) => joinQueue(node.dataset.partyId),
    'checkin-party': (node) => checkIn(node.dataset.partyId),
    'complete-party': (node) => completeParty(node.dataset.partyId),
    'leave-slot': (node) => leaveSlot(node.dataset.partyId),
    'invite-friend-slot': (node) => inviteFriendToSlot(node.dataset.partyId),
    'choose-friend': (node) => chooseFriend(node.dataset.guestId),
    'add-friend': (node) => addFriend(node.dataset.friendId),
    'request-friend': (node) => requestFriend(node.dataset.guestId),
    'accept-friend': (node) => acceptFriend(node.dataset.requestId),
    'decline-friend': (node) => declineFriend(node.dataset.requestId),
    'invite-to-slot': (node) => inviteToSlot(node.dataset.guestId),
    'choose-slot': (node) => chooseSlot(node.dataset.partyId),
    'send-invite': () => sendInviteFromPanel(),
    'accept-invite': (node) => acceptInvite(node.dataset.inviteId),
    'decline-invite': (node) => declineInvite(node.dataset.inviteId),
    'radar-start': (node) => startFromRadar(node),
    'dismiss-banner': (node) => dismissBanner(node),
    'star-pick': (node) => pickStar(node),
    'toggle-tag': (node) => toggleTag(node),
    'submit-rating': (node) => submitRating(node.dataset.visitId)
  };

  const CHANGE_ACTIONS = {
    'city-change': () => {
      readFilters();
      // Keep the Rated Venues tab on the same town: changing the global city moves its filter too.
      const city = state.filters.city;
      if (city && city !== 'All Cities') ensureCityOption('venue-city-filter', city);
      el('venue-city-filter').value = city;
      state.venueCity = city;
      loadRadar();
      doSearch();
      renderVenues();
    },
    'create-city': () => { fillVenueSelect(val('create-city')); },
    'create-venue': () => {
      syncVenueDetails();
      // Choosing a real venue detaches any map spot / custom name.
      if (val('create-venue')) {
        const input = el('create-spot-name');
        if (input) input.value = '';
        state.selectedSpot = null;
        state.spotNameDirty = false;
      }
    },
    'toggle-midpoint': () => {
      if (el('create-midpoint').checked && !state.pro) {
        el('create-midpoint').checked = false;
        showToast('Fair-Midpoint auto-matching is a CrewParty Pro feature. Opening Pro\u2026', 'info');
        openPaywall();
      }
      toggleMidpointFields();
    },
    'venue-sort': () => { state.venueSort = val('venue-sort'); renderVenues(); },
    'venue-city-filter': () => { state.venueCity = val('venue-city-filter'); renderVenues(); }
  };

  const handleClick = (event) => {
    const target = event.target;
    if (!target || !target.closest) return;

    const bell = target.closest('#notif-bell');
    if (!bell && !target.closest('#notif-panel')) {
      const panel = el('notif-panel');
      if (panel && !panel.hidden) { panel.hidden = true; el('notif-bell').setAttribute('aria-expanded', 'false'); }
    }

    const node = target.closest('[data-action]');
    if (!node || node.tagName === 'SELECT') return;
    const handler = CLICK_ACTIONS[node.dataset.action];
    if (handler) handler(node, event);
  };

  const handleChange = (event) => {
    const node = event.target.closest ? event.target.closest('[data-action]') : null;
    if (!node) return;
    const handler = CHANGE_ACTIONS[node.dataset.action];
    if (handler) handler(node, event);
  };

  const handleInput = (event) => {
    const id = event.target.id;
    if (id === 'budget-min' || id === 'budget-max') syncBudget();
    if (id === 'create-spot-name') onSpotNameTyped(event.target.value);
  };

  const handleSubmit = (event) => {
    if (event.target && event.target.id === 'create-form') { event.preventDefault(); submitCreate(); }
    if (event.target && event.target.id === 'profile-form') submitProfile(event);
  };

  const bindEvents = () => {
    document.addEventListener('click', handleClick);
    document.addEventListener('change', handleChange);
    document.addEventListener('input', handleInput);
    document.addEventListener('submit', handleSubmit);
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') { closeAllModals(); closeDrawer(); }
    });
  };

  /* ---------------- boot ---------------- */

  const init = async () => {
    try { state.pro = window.localStorage.getItem(PRO_KEY) === 'true'; } catch (e) { state.pro = false; }
    applyProState();
    bindEvents();
    syncBudget();

    if (window.CrewMap) {
      window.CrewMap.init('venue-map');
      window.CrewMap.setHandlers({
        onVenueSelect: (venueId) => openVenueDrawer(venueId),
        onSpotPick: (spot) => handleSpotPick(spot),
        onSpotResolved: (spot) => handleSpotResolved(spot),
        onPartySelect: (partyId) => focusParty(partyId)
      });
    }

    window.CrewApi.health().catch(() => showToast('Backend health check failed \u2014 some actions may not work.', 'error'));

    try {
      await initIdentity();
    } catch (err) {
      showToast('Could not start a live session: ' + err.message, 'error');
    }
    initUserLocation();

    try {
      await reloadAll();
    } catch (err) {
      showToast('Could not load CrewParty data: ' + err.message, 'error');
    }

    state.loaded = true;
    await doSearch({ silent: true });
    await loadRadar();
    startPresencePolling();
    if (state.tab === 'profile') renderProfile();
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
