/**
 * API 封装：同源调用本地后端，统一解析 { code, message, data } 响应体。
 * 成功返回 data；失败抛出 Error（message 取后端 message，兼容网络/HTTP 错误）。
 * 所有路径均为同源相对路径，无任何外部依赖。
 */
(function () {
  'use strict';

  async function request(method, path, opts) {
    opts = opts || {};
    let url = path;
    if (opts.params) {
      var qs = new URLSearchParams();
      Object.keys(opts.params).forEach(function (k) {
        var v = opts.params[k];
        if (v !== undefined && v !== null && v !== '') {
          qs.set(k, v);
        }
      });
      var q = qs.toString();
      if (q) {
        url += (path.indexOf('?') >= 0 ? '&' : '?') + q;
      }
    }

    var res;
    try {
      res = await fetch(url, {
        method: method,
        headers: opts.body ? { 'Content-Type': 'application/json' } : undefined,
        body: opts.body ? JSON.stringify(opts.body) : undefined
      });
    } catch (e) {
      var netErr = new Error('无法连接服务器，请确认后端服务已启动');
      netErr.network = true;
      throw netErr;
    }

    var payload = null;
    try {
      payload = await res.json();
    } catch (e) {
      payload = null;
    }

    if (!res.ok || !payload || payload.code !== 'SUCCESS') {
      var err = new Error(
        (payload && payload.message) || ('请求失败（HTTP ' + res.status + '）')
      );
      err.status = res.status;
      err.code = payload ? payload.code : undefined;
      throw err;
    }
    return payload.data;
  }

  function esc(seg) {
    return encodeURIComponent(seg);
  }

  window.API = {
    /** GET /api/room-types */
    roomTypes: function () {
      return request('GET', '/api/room-types');
    },

    /** GET /api/availability?checkIn=&checkOut= */
    availability: function (checkIn, checkOut) {
      return request('GET', '/api/availability', {
        params: { checkIn: checkIn, checkOut: checkOut }
      });
    },

    /** POST /api/orders */
    createOrder: function (body) {
      return request('POST', '/api/orders', { body: body });
    },

    /** GET /api/orders?phone= */
    ordersByPhone: function (phone) {
      return request('GET', '/api/orders', { params: { phone: phone } });
    },

    /** GET /api/orders/{orderNo} */
    orderDetail: function (orderNo) {
      return request('GET', '/api/orders/' + esc(orderNo));
    },

    /** POST /api/orders/{orderNo}/pay */
    pay: function (orderNo) {
      return request('POST', '/api/orders/' + esc(orderNo) + '/pay');
    },

    /** POST /api/orders/{orderNo}/cancel */
    cancel: function (orderNo) {
      return request('POST', '/api/orders/' + esc(orderNo) + '/cancel');
    },

    /** GET /api/frontdesk/orders?status=&date= */
    frontDeskOrders: function (status, date) {
      return request('GET', '/api/frontdesk/orders', {
        params: { status: status, date: date }
      });
    },

    /** POST /api/frontdesk/orders/{orderNo}/check-in */
    checkIn: function (orderNo) {
      return request('POST', '/api/frontdesk/orders/' + esc(orderNo) + '/check-in');
    },

    /** POST /api/frontdesk/orders/{orderNo}/check-out */
    checkOut: function (orderNo) {
      return request('POST', '/api/frontdesk/orders/' + esc(orderNo) + '/check-out');
    },

    /** GET /api/frontdesk/stats */
    frontDeskStats: function () {
      return request('GET', '/api/frontdesk/stats');
    }
  };
})();
