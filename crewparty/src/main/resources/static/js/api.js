/* CrewParty — API client (Vanilla ES6+).
   Same-origin JSON calls. Every method resolves to parsed JSON,
   or throws an Error carrying the server message. */
(() => {
  'use strict';

  const BASE = '/api';

  const extractMessage = (body, status) => {
    if (body) {
      if (typeof body === 'string' && body.trim()) return body.trim();
      if (body.message) return body.message;
      if (body.error) return body.error;
      if (body.detail) return body.detail;
    }
    return `HTTP ${status}`;
  };

  const request = async (path, options) => {
    const response = await fetch(BASE + path, options);
    const text = await response.text();
    let body = null;
    if (text) {
      try { body = JSON.parse(text); } catch (e) { body = text; }
    }
    if (!response.ok) throw new Error(extractMessage(body, response.status));
    return body;
  };

  const send = (method, path, payload) => {
    const options = { method };
    if (payload !== undefined) {
      options.headers = { 'Content-Type': 'application/json' };
      options.body = JSON.stringify(payload);
    }
    return request(path, options);
  };

  // Empty values are dropped so Spring numeric @RequestParam binding never sees a blank string.
  const query = (params) => {
    const search = new URLSearchParams();
    Object.keys(params).forEach((key) => {
      const value = params[key];
      if (value === null || value === undefined || value === '') return;
      search.set(key, String(value));
    });
    return search.toString();
  };

  window.CrewApi = {
    health: () => request('/health'),
    bootstrap: () => request('/bootstrap'),

    /* ---------------- live multi-tab identity & presence ---------------- */

    sessionInit: (guestId, city) => send('POST', '/session/init', {
      guestId: guestId || null,
      city: city || null
    }),
    presence: (guestId) => request('/presence?' + query({ guestId: guestId || '' })),

    /* ---------------- GPS seeding ---------------- */

    gpsSeed: (lat, lng, cityName) => send('POST', '/gps/seed', {
      lat,
      lng,
      cityName: cityName || ''
    }),

    /* ---------------- search & discovery ---------------- */

    search: (filters) => {
      const f = filters || {};
      return request('/search?' + query({
        guestId: f.guestId || '',
        city: f.city || '',
        theme: f.theme || '',
        timeWindow: f.timeWindow || '',
        budgetMin: f.budgetMin === undefined ? 0 : f.budgetMin,
        budgetMax: f.budgetMax === undefined ? -1 : f.budgetMax,
        format: f.format || 'All',
        pace: f.pace || 'All',
        friendsOnly: !!f.friendsOnly,
        publicSafeOnly: !!f.publicSafeOnly,
        verifiedOnly: !!f.verifiedOnly,
        miniGroupOnly: !!f.miniGroupOnly
      }));
    },

    radar: (city, budgetMax) => request('/radar?' + query({
      city: city || '',
      budgetMax: budgetMax === undefined ? -1 : budgetMax
    })),

    venueSlots: (venueId) => request('/venues/' + encodeURIComponent(venueId) + '/slots'),

    /* ---------------- parties ---------------- */

    createParty: (payload) => send('POST', '/parties', payload),
    joinParty: (id, guestId) => send('POST', `/parties/${encodeURIComponent(id)}/join`, { guestId }),
    queueForSlot: (id, guestId, guestName) => send('POST', `/parties/${encodeURIComponent(id)}/queue`, {
      guestId,
      guestName
    }),
    checkIn: (id, guestId) => send('POST', `/parties/${encodeURIComponent(id)}/checkin`, { guestId }),
    completeParty: (id) => send('POST', `/parties/${encodeURIComponent(id)}/complete`),

    /* ---------------- history ---------------- */

    rateVisit: (visitId, payload) => send('POST', `/history/${encodeURIComponent(visitId)}/rate`, payload),

    /* ---------------- friends ---------------- */

    addFriend: (id) => send('POST', `/friends/${encodeURIComponent(id)}/add`),
    requestFriend: (fromGuestId, toGuestId) => send('POST', '/friends/request', { fromGuestId, toGuestId }),
    acceptFriend: (requestId) => send('POST', `/friends/${encodeURIComponent(requestId)}/accept`),

    /* ---------------- invites ---------------- */

    createInvite: (fromGuestId, toGuestId, partyId) => send('POST', '/invites', {
      fromGuestId,
      toGuestId,
      partyId
    }),
    respondInvite: (inviteId, action) => send('POST', `/invites/${encodeURIComponent(inviteId)}/respond`, { action }),

    /* ---------------- profile & billing ---------------- */

    updateProfile: (payload) => send('PUT', '/profile', payload),
    checkout: () => send('POST', '/checkout')
  };
})();
