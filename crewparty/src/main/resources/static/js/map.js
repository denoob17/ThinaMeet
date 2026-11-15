/* CrewParty — Leaflet + OpenStreetMap venue map (Vanilla ES6+).
   Exposes window.CrewMap with:
     - zoom-aware pins: below REVEAL_ZOOM only the promoted featured partners are drawn, at or above
       it every CrewParty venue, every real Overpass organization and every custom-coord party shows
     - a click-anywhere custom-spot picker backed by OSM Nominatim reverse geocoding
     - live Overpass lookups of the real cafes / parks / coworking spots inside the current viewport
     - GPS centering for the "Detect My City" flow
   Callbacks are provided by app.js through setHandlers(). */
(() => {
  'use strict';

  /** At or above this zoom level the map reveals every venue, real place and active spot. */
  const REVEAL_ZOOM = 14;
  /** Venues with live slots stay on the map even at this more general zoom, so busy spots pop out. */
  const ACTIVE_ZOOM = 12;
  const NOMINATIM = 'https://nominatim.openstreetmap.org/reverse';
  const OVERPASS = 'https://overpass-api.de/api/interpreter';
  const VIEW_DEBOUNCE_MS = 500;
  /** Anything closer than this to a CrewParty venue is treated as the same place. */
  const DUP_RADIUS_KM = 0.05;
  /** Soft halo drawn under the "You are here" pin so the GPS fix reads as an approximate area. */
  const USER_ACCURACY_RADIUS_M = 120;

  /** OpenStreetMap tag -> a friendly category label shown in the pin tooltip. */
  const OSM_CATEGORY = {
    cafe: 'Cafe', restaurant: 'Restaurant', bar: 'Bar', pub: 'Pub',
    library: 'Library', community_centre: 'Community Center',
    park: 'Park', sports_centre: 'Sports Center', pitch: 'Sports Pitch',
    coworking: 'Coworking', hospital: 'Hospital', clinic: 'Clinic',
    university: 'University', college: 'College', school: 'School',
    theatre: 'Theatre', cinema: 'Cinema'
  };

  /** Radius of the click-time Overpass lookup: wide enough to catch the building you clicked on. */
  const NEARBY_RADIUS_M = 65;
  /** Hard cap on the click-time Overpass lookup: it may only ever upgrade the name, never block it. */
  const OVERPASS_TIMEOUT_MS = 3500;
  /**
   * Trackpad pinch sensitivity, in zoom levels per scrolled pixel. Matches Leaflet's own
   * wheel-zoom feel (60 px of pinch = one zoom level) and works with the fractional zoom the
   * map is created with, so even a one-pixel pinch nudge moves the camera.
   */
  const PINCH_PER_PX = 1 / 60;

  let map = null;
  let pinLayer = null;        // venue + real place + active spot pins, redrawn whenever the zoom band changes
  let customLayer = null;     // user-dropped spots, kept across zoom redraws
  let userLayer = null;       // the caller's own GPS marker, never touched by drawPins/clearCustomSpots
  const markers = {};
  let venues = [];
  let parties = [];
  let osmVenues = [];
  let viewTimer = null;
  const osmCache = {};
  let resizeBound = false;
  let lastGeocodeAt = 0;

  const handlers = { onVenueSelect: null, onSpotPick: null, onSpotResolved: null, onPartySelect: null };

  const escapeHtml = (value) => {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  };

  const hasCoords = (v) => v &&
    typeof v.lat === 'number' && isFinite(v.lat) &&
    typeof v.lng === 'number' && isFinite(v.lng) &&
    !(v.lat === 0 && v.lng === 0);

  const haversineKm = (lat1, lng1, lat2, lng2) => {
    const toRad = (d) => d * Math.PI / 180;
    const dLat = toRad(lat2 - lat1);
    const dLng = toRad(lng2 - lng1);
    const a = Math.sin(dLat / 2) ** 2
      + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
    return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  };

  const activeSlots = (v) => (v && typeof v.activeSlotCount === 'number' ? v.activeSlotCount : 0);

  /** Featured partners get a noticeably larger pin so they stand out on the zoomed-out map. */
  const pinIcon = (kind) => {
    const size = kind === 'featured' ? 30 : (kind === 'active' ? 28 : 24);
    return L.divIcon({
      className: `crew-pin crew-pin-${kind}`,
      html: '<span class="crew-pin-dot"></span><span class="crew-pin-core"></span>',
      iconSize: [size, size],
      iconAnchor: [size / 2, size / 2],
      tooltipAnchor: [0, -(size / 2) - 2]
    });
  };

  const tooltipHtml = (v) => {
    const rating = typeof v.avgRating === 'number' && v.avgRating > 0 ? v.avgRating.toFixed(1) + ' \u2605' : '';
    const cost = typeof v.avgCostUsd === 'number' && v.avgCostUsd > 0 ? '$$' + escapeHtml(v.avgCostUsd) + ' per person' : '';
    const meta = [rating, cost].filter(Boolean).join(' \u00b7 ');
    const safe = v.publicSafeSpot ? '<span class="tip-safe">Public Safe Spot</span>' : '';
    const featured = v.featuredPartner ? '<span class="tip-featured">Featured Partner</span>' : '';
    const open = activeSlots(v);
    const slots = open > 0
      ? `<span class="tip-slots">${open} slot${open === 1 ? '' : 's'} open now</span>`
      : '';
    return `<div class="tip"><strong>${escapeHtml(v.name)}</strong>` +
      `<div>${escapeHtml(v.category || '')} &middot; ${escapeHtml(v.neighborhood || '')}</div>` +
      (meta ? `<div>${meta}</div>` : '') +
      `${featured}${safe}${slots}</div>`;
  };

  /** The CrewParty venue pin set for the current zoom band: featured when zoomed out, all when zoomed in. */
  const visibleVenues = () => {
    const zoom = map ? map.getZoom() : REVEAL_ZOOM;
    if (zoom >= REVEAL_ZOOM) {
      return venues;
    }
    if (zoom >= ACTIVE_ZOOM) {
      // Venues that have people waiting right now stay visible one zoom band further out.
      return venues.filter((v) => v.featuredPartner || activeSlots(v) > 0);
    }
    return venues.filter((v) => v.featuredPartner);
  };

  /** Active parties that live on a custom spot (no venueId) so anonymous-place slots stay visible. */
  const customSpotParties = () => parties
    .filter((p) => p && (p.venueId === null || p.venueId === undefined || p.venueId === ''))
    .filter(hasCoords);

  const drawPins = () => {
    if (!map || !pinLayer) return;
    pinLayer.clearLayers();
    Object.keys(markers).forEach((key) => delete markers[key]);

    visibleVenues().forEach((v) => {
      const kind = v.featuredPartner ? 'featured' : (activeSlots(v) > 0 ? 'active' : 'safe');
      const marker = L.marker([v.lat, v.lng], { icon: pinIcon(kind), title: v.name }).addTo(pinLayer);
      marker.bindTooltip(tooltipHtml(v), { direction: 'top', opacity: 0.96 });
      marker.on('click', (event) => {
        if (event && event.originalEvent) L.DomEvent.stopPropagation(event);
        if (handlers.onVenueSelect) handlers.onVenueSelect(v.id, v);
      });
      markers[v.id] = marker;
    });

    if (map.getZoom() < REVEAL_ZOOM) return;

    // Real organizations discovered through Overpass: no CrewParty slots yet, so a click starts a party.
    osmVenues.forEach((v) => {
      const marker = L.marker([v.lat, v.lng], { icon: pinIcon('safe'), title: v.name, zIndexOffset: -50 })
        .addTo(pinLayer);
      marker.bindTooltip(tooltipHtml(v), { direction: 'top', opacity: 0.96 });
      marker.on('click', (event) => {
        if (event && event.originalEvent) L.DomEvent.stopPropagation(event);
        if (handlers.onSpotPick) {
          handlers.onSpotPick({
            lat: v.lat, lng: v.lng, name: v.name, category: v.category, isListedPlace: true
          });
        }
      });
      markers[v.id] = marker;
    });

    // Custom-coord parties are drawn last so a freshly created anonymous-place slot pops on the map.
    customSpotParties().forEach((p) => {
      const marker = L.marker([p.lat, p.lng], {
        icon: pinIcon('custom'), title: p.venueName || p.title, zIndexOffset: 350
      }).addTo(pinLayer);
      marker.bindTooltip(
        `<div class="tip"><strong>${escapeHtml(p.venueName || p.title)}</strong>` +
        `<div>${escapeHtml(p.title || '')}</div>` +
        `<div>${escapeHtml(p.timeLabel || '')}</div>` +
        '<span class="tip-custom">Custom Spot</span></div>', { direction: 'top', opacity: 0.96 });
      marker.on('click', (event) => {
        if (event && event.originalEvent) L.DomEvent.stopPropagation(event);
        if (handlers.onPartySelect) handlers.onPartySelect(p.id, p);
      });
      markers['party-' + p.id] = marker;
    });
  };

  const addCustomSpot = (spot) => {
    if (!map || !customLayer || !spot || typeof spot.lat !== 'number') return null;
    const marker = L.marker([spot.lat, spot.lng], {
      icon: pinIcon('custom'),
      title: spot.label || 'Your custom spot',
      zIndexOffset: 400
    }).addTo(customLayer);
    marker.bindTooltip(escapeHtml(spot.label || 'Your custom spot'), { direction: 'top' });
    return marker;
  };

  const clearCustomSpots = () => {
    if (customLayer) customLayer.clearLayers();
  };

  /**
   * Draws the caller's own position on a dedicated layer that survives every pin redraw: a pulsing
   * blue dot with a soft accuracy halo under it and a "You are here" tooltip.
   */
  const setUserLocation = (lat, lng) => {
    if (!map || !userLayer) return;
    if (typeof lat !== 'number' || typeof lng !== 'number' || !isFinite(lat) || !isFinite(lng)) return;
    if (lat === 0 && lng === 0) return;
    userLayer.clearLayers();

    L.circle([lat, lng], {
      radius: USER_ACCURACY_RADIUS_M,
      className: 'crew-me-accuracy',
      stroke: false,
      fillColor: '#3f8cff',
      fillOpacity: 0.16,
      interactive: false
    }).addTo(userLayer);

    const marker = L.marker([lat, lng], {
      icon: L.divIcon({
        className: 'crew-pin crew-pin-me',
        html: '<span class="crew-pin-me-dot"></span><span class="crew-pin-me-core"></span>',
        iconSize: [26, 26],
        iconAnchor: [13, 13],
        tooltipAnchor: [0, -16]
      }),
      title: 'You are here',
      zIndexOffset: 900
    }).addTo(userLayer);
    marker.bindTooltip('You are here', { direction: 'top', opacity: 0.96 });
    marker.on('click', (event) => {
      if (event && event.originalEvent) L.DomEvent.stopPropagation(event);
    });
  };

  /* -------------------- real places from OpenStreetMap (Overpass) -------------------- */

  const knownVenueNear = (lat, lng) => venues.some((v) =>
    hasCoords(v) && haversineKm(lat, lng, v.lat, v.lng) <= DUP_RADIUS_KM);

  const osmToVenue = (element) => {
    const tags = element.tags || {};
    const lat = typeof element.lat === 'number' ? element.lat : (element.center && element.center.lat);
    const lng = typeof element.lon === 'number' ? element.lon : (element.center && element.center.lon);
    if (typeof lat !== 'number' || typeof lng !== 'number' || !tags.name) return null;
    const kind = tags.amenity || tags.leisure || tags.office || '';
    return {
      id: `osm-${element.type}-${element.id}`,
      name: tags.name,
      category: OSM_CATEGORY[kind] || 'Place',
      neighborhood: tags['addr:suburb'] || tags['addr:city'] || '',
      lat, lng,
      avgRating: 0, avgCostUsd: 0,
      publicSafeSpot: false, featuredPartner: false,
      osm: true, listedPlace: true
    };
  };

  const boundsKey = () => {
    const b = map.getBounds();
    return [b.getSouth(), b.getWest(), b.getNorth(), b.getEast()]
      .map((n) => n.toFixed(2)).join(',');
  };

  const applyOsmVenues = (list) => {
    osmVenues = list.filter((v) => !knownVenueNear(v.lat, v.lng));
    drawPins();
  };

  const loadOsmVenues = async () => {
    if (!map || map.getZoom() < REVEAL_ZOOM) return;
    const key = boundsKey();
    if (osmCache[key]) {
      applyOsmVenues(osmCache[key]);
      return;
    }
    const b = map.getBounds();
    const bbox = `${b.getSouth()},${b.getWest()},${b.getNorth()},${b.getEast()}`;
    const query = '[out:json][timeout:25];(' +
      `nwr["name"]["amenity"~"^(cafe|restaurant|bar|pub|library|community_centre)$"](${bbox});` +
      `nwr["name"]["amenity"~"^(hospital|clinic|university|college|school|theatre|cinema)$"](${bbox});` +
      `nwr["name"]["healthcare"](${bbox});` +
      `nwr["name"]["leisure"~"^(park|sports_centre|pitch)$"](${bbox});` +
      `nwr["name"]["office"="coworking"](${bbox});` +
      ');out center 60;';
    try {
      const response = await fetch(OVERPASS, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
        body: 'data=' + encodeURIComponent(query)
      });
      if (!response.ok) return;
      const data = await response.json();
      const list = (data.elements || []).map(osmToVenue).filter(Boolean);
      osmCache[key] = list;
      applyOsmVenues(list);
    } catch (e) {
      // Offline or rate-limited: the CrewParty pins keep working without the real-place overlay.
    }
  };

  const onViewChanged = () => {
    drawPins();
    if (!map || map.getZoom() < REVEAL_ZOOM) return;
    window.clearTimeout(viewTimer);
    viewTimer = window.setTimeout(loadOsmVenues, VIEW_DEBOUNCE_MS);
  };

  /* -------------------- reverse geocoding & map clicks -------------------- */

  /**
   * Click-time Overpass lookup for a named object within {@link NEARBY_RADIUS_M} metres. This is what
   * names big complexes (hospitals, malls, sports centres, coworking hubs, parks) when the click lands
   * on a wing of the building rather than its Nominatim point.
   * It is strictly an optional upgrade: a slow or blocked Overpass is aborted after
   * {@link OVERPASS_TIMEOUT_MS} so it can never hold the Create Party modal hostage.
   */
  const overpassNearbyTags = async (lat, lng) => {
    const query = '[out:json][timeout:5];(' +
      `node(around:${NEARBY_RADIUS_M},${lat},${lng})["name"];` +
      `way(around:${NEARBY_RADIUS_M},${lat},${lng})["name"];` +
      `relation(around:${NEARBY_RADIUS_M},${lat},${lng})["name"];` +
      ');out center tags 5;';
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), OVERPASS_TIMEOUT_MS);
    try {
      const response = await fetch(OVERPASS, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
        body: 'data=' + encodeURIComponent(query),
        signal: controller.signal
      });
      if (!response.ok) return null;
      const data = await response.json();
      const hit = (data.elements || []).find((element) => element.tags && element.tags.name);
      return hit ? hit.tags : null;
    } catch (e) {
      return null; // offline or rate-limited: Nominatim alone still names the spot
    } finally {
      window.clearTimeout(timer);
    }
  };

  /**
   * Nominatim-only reverse lookup: the fast path that fills the Create Party modal with the place
   * name plus city, district and ZIP. Kept separate from Overpass so the form never waits on it.
   */
  const nominatimReverse = async (lat, lng) => {
    const now = Date.now();
    const wait = Math.max(0, 1100 - (now - lastGeocodeAt));
    lastGeocodeAt = now + wait;
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));

    const data = await fetch(
      `${NOMINATIM}?format=jsonv2&zoom=18&addressdetails=1&namedetails=1&accept-language=ru,en&lat=${lat}&lon=${lng}`,
      { headers: { Accept: 'application/json' } })
      .then((response) => (response.ok ? response.json() : null))
      .catch(() => null);

    const address = (data && data.address) || {};
    const namedetails = (data && data.namedetails) || {};
    const organization = namedetails.name
      || (data && data.name)
      || address.hospital || address.clinic || address.amenity || address.leisure || address.building;
    const street = `${address.road || ''} ${address.house_number || ''}`.trim();

    return {
      name: organization || street,
      city: address.city || address.town || address.state || '',
      neighborhood: [address.suburb || address.city_district || address.district || address.neighbourhood,
        address.postcode].filter(Boolean).join(', '),
      postcode: address.postcode || '',
      isListedPlace: !!organization
    };
  };

  const handleMapClick = (event) => {
    const lat = event.latlng.lat;
    const lng = event.latlng.lng;
    const marker = addCustomSpot({ lat, lng, label: 'Locating address\u2026' });
    // Fast feedback: the Create Party modal opens immediately, the details arrive a moment later.
    if (handlers.onSpotPick) handlers.onSpotPick({ lat, lng, name: '', isListedPlace: false });

    const emit = (name, place) => {
      if (marker) marker.setTooltipContent(escapeHtml(name || 'Custom spot'));
      if (handlers.onSpotResolved) {
        handlers.onSpotResolved({
          lat,
          lng,
          name,
          city: place.city,
          neighborhood: place.neighborhood,
          postcode: place.postcode,
          isListedPlace: place.isListedPlace
        });
      }
    };

    // Nominatim answers in well under a second and fills the form right away; the Overpass lookup only
    // ever upgrades the name afterwards, so it can never leave the Custom spot name empty again.
    nominatimReverse(lat, lng)
      .then((place) => {
        emit(place.name, place);
        return overpassNearbyTags(lat, lng);
      })
      .then((tags) => {
        if (tags && tags.name) emit(tags.name, { city: '', neighborhood: '', postcode: '', isListedPlace: true });
      });
  };

  /**
   * Trackpad/touchpad two-finger pinch zoom, like on a phone. Browsers report a pinch as a `wheel`
   * event carrying `ctrlKey`, so plain swiping still scrolls the page (the map keeps
   * `scrollWheelZoom: false`) while a pinch zooms around the point under the fingers.
   */
  const bindTrackpadPinchZoom = (container) => {
    container.addEventListener('wheel', (event) => {
      if (!event.ctrlKey || !map) return;
      event.preventDefault();
      const pixels = event.deltaY * (event.deltaMode === 1 ? 16 : 1);
      const zoom = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), map.getZoom() - pixels * PINCH_PER_PX));
      map.setZoomAround(map.mouseEventToContainerPoint(event), zoom, { animate: false });
    }, { passive: false });
  };

  const init = (containerId) => {
    const container = document.getElementById(containerId);
    if (!container || typeof L === 'undefined') return null;

    if (map) {
      map.invalidateSize();
      return map;
    }

    // zoomSnap: 0 keeps fractional zoom levels, which is what makes a trackpad pinch glide smoothly
    // instead of being rounded away (with the default zoomSnap of 1 every pinch step snapped to 0).
    map = L.map(container, { scrollWheelZoom: false, zoomControl: true, zoomSnap: 0 })
      .setView([39.8283, -98.5795], 4);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);

    pinLayer = L.layerGroup().addTo(map);
    customLayer = L.layerGroup().addTo(map);
    userLayer = L.layerGroup().addTo(map);

    map.on('click', handleMapClick);
    map.on('zoomend', onViewChanged);
    map.on('moveend', onViewChanged);
    bindTrackpadPinchZoom(container);

    if (!resizeBound) {
      resizeBound = true;
      window.addEventListener('resize', () => { if (map) map.invalidateSize(); });
    }

    return map;
  };

  /**
   * Redraws the pins. {@code options.preserveView} is used by the background live refresh: the pins
   * are rebuilt but the camera stays exactly where the user left it.
   * Accepts both {@code (list, parties, options)} and {@code (list, options)}.
   */
  const renderVenues = (list, partyList, options) => {
    if (!map || !pinLayer) return;
    let partiesArg = partyList;
    let opts = options;
    if (opts === undefined && partiesArg && !Array.isArray(partiesArg)) {
      opts = partiesArg;
      partiesArg = null;
    }
    const preserveView = Boolean(opts && opts.preserveView);

    venues = (list || []).filter(hasCoords);
    if (partiesArg) parties = (partiesArg || []).filter(Boolean);
    drawPins();

    if (preserveView) return;

    const placed = venues;
    if (placed.length === 1) {
      map.setView([placed[0].lat, placed[0].lng], 13);
    } else if (placed.length > 1) {
      const bounds = L.latLngBounds(placed.map((v) => [v.lat, v.lng]));
      map.fitBounds(bounds, { padding: [34, 34], maxZoom: 13 });
    }
  };

  const highlightVenue = (venueId) => {
    if (!map) return;
    const marker = markers[venueId];
    if (!marker) return;
    map.panTo(marker.getLatLng());
    marker.openTooltip();
  };

  const centerOn = (lat, lng, zoom) => {
    if (!map || typeof lat !== 'number' || typeof lng !== 'number') return;
    map.setView([lat, lng], zoom || REVEAL_ZOOM);
  };

  const setHandlers = (next) => {
    Object.assign(handlers, next || {});
  };

  window.CrewMap = {
    init,
    renderVenues,
    highlightVenue,
    centerOn,
    setHandlers,
    addCustomSpot,
    clearCustomSpots,
    setUserLocation
  };
})();
