/* CrewParty — Leaflet + OpenStreetMap venue map.
   Exposes window.CrewMap = { init, renderVenues, highlightVenue }. */
(function () {
  'use strict';

  var map = null;
  var layer = null;
  var markers = {};
  var resizeBound = false;

  function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function hasCoords(v) {
    return v &&
      typeof v.lat === 'number' && isFinite(v.lat) &&
      typeof v.lng === 'number' && isFinite(v.lng) &&
      !(v.lat === 0 && v.lng === 0);
  }

  function popupHtml(v) {
    var rating = typeof v.avgRating === 'number' ? v.avgRating.toFixed(1) : '—';
    var count = typeof v.ratingCount === 'number' ? v.ratingCount : 0;
    return '' +
      '<div class="map-popup">' +
        '<strong>' + escapeHtml(v.name) + '</strong>' +
        '<div class="m-line">' + escapeHtml(v.neighborhood || '') + ' &middot; ' + escapeHtml(v.category || '') + '</div>' +
        '<div class="m-rating">' + rating + ' ★ (' + count + ')</div>' +
        '<div class="m-line">$' + escapeHtml(v.avgCostUsd) + ' avg per person</div>' +
        '<button type="button" class="btn btn-sm btn-primary" data-action="view-venue" data-venue-id="' + escapeHtml(v.id) + '">View venue</button>' +
      '</div>';
  }

  function init(containerId) {
    var container = document.getElementById(containerId);
    if (!container || typeof L === 'undefined') return null;

    if (map) {
      map.invalidateSize();
      return map;
    }

    map = L.map(container, { scrollWheelZoom: false, zoomControl: true })
      .setView([39.8283, -98.5795], 4);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '© OpenStreetMap contributors'
    }).addTo(map);

    layer = L.layerGroup().addTo(map);

    if (!resizeBound) {
      resizeBound = true;
      window.addEventListener('resize', function () {
        if (map) map.invalidateSize();
      });
    }

    return map;
  }

  function renderVenues(venues) {
    if (!map || !layer) return;
    layer.clearLayers();
    markers = {};

    var placed = (venues || []).filter(hasCoords);

    placed.forEach(function (v) {
      var marker = L.circleMarker([v.lat, v.lng], {
        radius: 9,
        weight: 2,
        color: '#4d8bff',
        fillColor: '#4d8bff',
        fillOpacity: 0.55
      }).addTo(layer);

      marker.bindPopup(popupHtml(v));
      markers[v.id] = marker;
    });

    if (placed.length === 1) {
      map.setView([placed[0].lat, placed[0].lng], 13);
    } else if (placed.length > 1) {
      var bounds = L.latLngBounds(placed.map(function (v) { return [v.lat, v.lng]; }));
      map.fitBounds(bounds, { padding: [34, 34], maxZoom: 13 });
    }
  }

  function highlightVenue(venueId) {
    if (!map) return;
    var marker = markers[venueId];
    if (!marker) return;
    map.panTo(marker.getLatLng());
    marker.openPopup();
  }

  window.CrewMap = {
    init: init,
    renderVenues: renderVenues,
    highlightVenue: highlightVenue
  };
})();
