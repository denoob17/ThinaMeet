/* CrewParty — API client.
   Same-origin JSON calls. Every method returns parsed JSON,
   or throws an Error carrying the server message. */
(function () {
  'use strict';

  var BASE = '/api';

  function extractMessage(body, status) {
    if (body) {
      if (typeof body === 'string' && body.trim()) return body.trim();
      if (body.message) return body.message;
      if (body.error) return body.error;
      if (body.detail) return body.detail;
    }
    return 'HTTP ' + status;
  }

  function request(path, options) {
    return fetch(BASE + path, options).then(function (response) {
      return response.text().then(function (text) {
        var body = null;
        if (text) {
          try { body = JSON.parse(text); } catch (e) { body = text; }
        }
        if (!response.ok) {
          throw new Error(extractMessage(body, response.status));
        }
        return body;
      });
    });
  }

  function send(method, path, payload) {
    var options = { method: method };
    if (payload !== undefined) {
      options.headers = { 'Content-Type': 'application/json' };
      options.body = JSON.stringify(payload);
    }
    return request(path, options);
  }

  function query(params) {
    var search = new URLSearchParams();
    Object.keys(params).forEach(function (key) {
      search.set(key, params[key] === null || params[key] === undefined ? '' : String(params[key]));
    });
    return search.toString();
  }

  window.CrewApi = {
    health: function () {
      return request('/health');
    },
    bootstrap: function () {
      return request('/bootstrap');
    },
    search: function (filters) {
      var f = filters || {};
      return request('/search?' + query({
        city: f.city || '',
        theme: f.theme || '',
        maxBudget: f.maxBudget || 0
      }));
    },
    createParty: function (payload) {
      return send('POST', '/parties', payload);
    },
    joinParty: function (id, friendId) {
      return send('POST', '/parties/' + encodeURIComponent(id) + '/join', { friendId: friendId });
    },
    completeParty: function (id) {
      return send('POST', '/parties/' + encodeURIComponent(id) + '/complete');
    },
    rateVisit: function (visitId, payload) {
      return send('POST', '/history/' + encodeURIComponent(visitId) + '/rate', payload);
    },
    addFriend: function (id) {
      return send('POST', '/friends/' + encodeURIComponent(id) + '/add');
    },
    checkout: function () {
      return send('POST', '/checkout');
    }
  };
})();
