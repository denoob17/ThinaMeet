/* CrewParty — Leaflet + OpenStreetMap venue map (Vanilla ES6+).
   Exposes window.CrewMap with:
     - zoom-aware pins (featured partner spots when zoomed out, all public safe venues when zoomed in)
     - a click-anywhere custom-spot picker backed by OSM Nominatim reverse geocoding
     - GPS centering for the "Detect My City" flow
   Callbacks are provided by app.js through setHandlers(). */
(() => {
  'use strict';

  /** At or above this zoom level the map reveals every public safe venue instead of the featured few. */
  const REVEAL_ZOOM = 14;
  const NOMINATIM = 'https://nominatim.openstreetmap.org/reverse';

  let map = null;
  let pinLayer = null;        // venue pins, redrawn whenever the zoom band changes
  let customLayer = null;     // user-dropped spots, kept across zoom redraws
  const markers = {};
  let venues = [];
  let resizeBound = false;
  let lastGeocodeAt = 0;

  const handlers = { onVenueSelect: null, onSpotPick: null, onSpotResolved: null };

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

  const pinIcon = (kind) => L.divIcon({
    className: `crew-pin crew-pin-${kind}`,
    html: '<span class="crew-pin-dot"></span><span class="crew-pin-core"></span>',
    iconSize: [24, 24],
    iconAnchor: [12, 12],
    tooltipAnchor: [0, -14]
  });

  const tooltipHtml = (v) => {
    const rating = typeof v.avgRating === 'number' ? v.avgRating.toFixed(1) : '—';
    const safe = v.publicSafeSpot ? '<span class="tip-safe">Public Safe Spot</span>' : '';
    return `<div class="tip"><strong>${escapeHtml(v.name)}</strong>` +
      `<div>${escapeHtml(v.category || '')} &middot; ${escapeHtml(v.neighborhood || '')}</div>` +
      `<div>${rating} \u2605 &middot; $${escapeHtml(v.avgCostUsd)} per person</div>` +
      `${safe}</div>`;
  };

  /** The pin set for the current zoom band: featured when zoomed out, all safe venues when zoomed in. */
  const visibleVenues = () => {
    const zoom = map ? map.getZoom() : REVEAL_ZOOM;
    if (zoom < REVEAL_ZOOM) {
      return venues.filter((v) => v.featuredPartner);
    }
    return venues.filter((v) => v.publicSafeSpot || v.featuredPartner);
  };

  const drawPins = () => {
    if (!map || !pinLayer) return;
    pinLayer.clearLayers();
    Object.keys(markers).forEach((key) => delete markers[key]);

    visibleVenues().forEach((v) => {
      const kind = v.featuredPartner ? 'featured' : 'safe';
      const marker = L.marker([v.lat, v.lng], { icon: pinIcon(kind), title: v.name }).addTo(pinLayer);
      marker.bindTooltip(tooltipHtml(v), { direction: 'top', opacity: 0.96 });
      marker.on('click', (event) => {
        if (event && event.originalEvent) L.DomEvent.stopPropagation(event);
        if (handlers.onVenueSelect) handlers.onVenueSelect(v.id, v);
      });
      markers[v.id] = marker;
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

  /** Nominatim asks for at most ~1 request per second, so calls are gently spaced out. */
  const reverseGeocode = async (lat, lng) => {
    const now = Date.now();
    const wait = Math.max(0, 1100 - (now - lastGeocodeAt));
    lastGeocodeAt = now + wait;
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
    try {
      const url = `${NOMINATIM}?format=jsonv2&zoom=17&addressdetails=1&lat=${lat}&lon=${lng}`;
      const response = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!response.ok) return '';
      const data = await response.json();
      const address = data.address || {};
      return data.name || address.amenity || address.leisure || address.shop
        || address.tourism || address.building || address.road
        || address.suburb || address.neighbourhood || '';
    } catch (e) {
      return '';
    }
  };

  const handleMapClick = (event) => {
    const lat = event.latlng.lat;
    const lng = event.latlng.lng;
    const marker = addCustomSpot({ lat, lng, label: 'Locating address\u2026' });
    // Fast feedback: the Create Party modal opens immediately, the name arrives a moment later.
    if (handlers.onSpotPick) handlers.onSpotPick({ lat, lng, name: '' });
    reverseGeocode(lat, lng).then((name) => {
      if (marker) marker.setTooltipContent(escapeHtml(name || 'Custom spot'));
      if (handlers.onSpotResolved) handlers.onSpotResolved({ lat, lng, name: name || '' });
    });
  };

  const init = (containerId) => {
    const container = document.getElementById(containerId);
    if (!container || typeof L === 'undefined') return null;

    if (map) {
      map.invalidateSize();
      return map;
    }

    map = L.map(container, { scrollWheelZoom: false, zoomControl: true })
      .setView([39.8283, -98.5795], 4);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);

    pinLayer = L.layerGroup().addTo(map);
    customLayer = L.layerGroup().addTo(map);

    map.on('click', handleMapClick);
    map.on('zoomend', drawPins);

    if (!resizeBound) {
      resizeBound = true;
      window.addEventListener('resize', () => { if (map) map.invalidateSize(); });
    }

    return map;
  };

  const renderVenues = (list) => {
    if (!map || !pinLayer) return;
    venues = (list || []).filter(hasCoords);
    drawPins();

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
    clearCustomSpots
  };
})();
