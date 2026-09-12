/**
 * 栖云酒店预订管理系统 · 前端 SPA 逻辑
 * - URL hash 路由：#/booking（预订）、#/orders（订单查询）、#/frontdesk（前台管理）
 * - 所有接口数据渲染仅使用 textContent / createElement（无 innerHTML 注入面）
 * - 图表遵循 dataviz 规范：单序列 slot-1 蓝、薄条形、值标注 + 表格视图、悬停提示
 */
(function () {
  'use strict';

  var UI = window.UI;
  var el = UI.el, clear = UI.clear, fmt = UI.fmt;
  var badge = UI.badge, Toast = UI.Toast, confirmDialog = UI.confirmDialog;
  var copyText = UI.copyText, setBusy = UI.setBusy;
  var operatorLabel = UI.operatorLabel, statusOf = UI.statusOf, toneInk = UI.toneInk;

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  var PHONE_RE = /^1\d{10}$/;
  var MAX_NIGHTS = 30;   // 与后端 app.booking.max-stay-nights 一致
  var MAX_ROOMS = 3;     // 预订页间数上限（后端上限 5，演示取 3）

  /* ================= 通用工具 ================= */

  function pad2(n) { return String(n).padStart(2, '0'); }
  function dateStr(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function addDays(dateStrLike, n) {
    var d = UI.parseDate(dateStrLike);
    if (!d) return dateStrLike;
    d.setDate(d.getDate() + n);
    return dateStr(d);
  }
  function nightsBetween(a, b) {
    var da = UI.parseDate(a), db = UI.parseDate(b);
    if (!da || !db) return 0;
    return Math.round((db - da) / 86400000);
  }
  function newRequestId() {
    try {
      if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    } catch (e) { /* fall through */ }
    return 'r-' + Date.now().toString(36) + '-' +
      Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 10);
  }
  function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }

  function emptyState(ico, title, hint, chips) {
    var wrap = el('div', 'empty');
    wrap.appendChild(el('div', 'empty-ico', ico));
    wrap.appendChild(el('div', 'empty-title', title));
    if (hint) wrap.appendChild(el('div', 'empty-hint', hint));
    if (chips && chips.length) {
      var row = el('div', 'empty-chips');
      chips.forEach(function (c) {
        var chip = el('button', 'chip chip--click', c.label);
        chip.type = 'button';
        chip.addEventListener('click', c.onClick);
        row.appendChild(chip);
      });
      wrap.appendChild(row);
    }
    return wrap;
  }

  function setFieldError(input, msg) {
    if (!input) return;
    input.setAttribute('aria-invalid', 'true');
    var box = input.parentNode;
    var err = box ? box.querySelector('.field-error') : null;
    if (!err) {
      err = el('div', 'field-error', msg);
      if (box) box.appendChild(err);
    } else {
      err.textContent = msg;
    }
    input.focus();
  }
  function clearFieldError(input) {
    if (!input) return;
    input.removeAttribute('aria-invalid');
    var box = input.parentNode;
    var err = box ? box.querySelector('.field-error') : null;
    if (err) err.parentNode.removeChild(err);
  }

  function summaryRow(rows, k, v) {
    var r = el('div', 'summary-row');
    r.appendChild(el('span', 'k', k));
    r.appendChild(el('span', 'v', v));
    rows.appendChild(r);
  }

  /* ================= 主题切换 ================= */

  var THEME_KEY = 'qiyun-theme';
  var themeToggle = $('#theme-toggle');

  function effectiveTheme() {
    var forced = document.documentElement.getAttribute('data-theme');
    if (forced) return forced;
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) return 'dark';
    return 'light';
  }
  function syncThemeIcon() {
    var dark = effectiveTheme() === 'dark';
    themeToggle.textContent = dark ? '☀️' : '🌙';
    themeToggle.title = dark ? '切换为浅色主题' : '切换为深色主题';
  }
  function initTheme() {
    try {
      var stored = localStorage.getItem(THEME_KEY);
      if (stored === 'dark' || stored === 'light') {
        document.documentElement.setAttribute('data-theme', stored);
      }
    } catch (e) { /* 隐私模式等场景忽略 */ }
    syncThemeIcon();
    themeToggle.addEventListener('click', function () {
      var next = effectiveTheme() === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* ignore */ }
      document.documentElement.setAttribute('data-theme', next);
      syncThemeIcon();
    });
    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
        if (!document.documentElement.getAttribute('data-theme')) syncThemeIcon();
      });
    }
  }

  /* ================= 视图一：预订房间 ================= */

  var booking = {
    state: {
      step: 1,
      availability: [],
      selected: null,
      requestId: null,
      form: { name: '', phone: '', idNo: '', count: 1 },
      fields: null,
      result: null
    },
    loaded: false,
    els: null,

    ensure: function () {
      if (!this.loaded) {
        this.cacheEls();
        this.initDates();
        this.bind();
        this.loaded = true;
        this.query({ first: true });
      }
    },

    cacheEls: function () {
      this.els = {
        checkIn: $('#bk-checkin'),
        checkOut: $('#bk-checkout'),
        nights: $('#bk-nights'),
        queryBtn: $('#bk-query'),
        listWrap: $('#bk-list-wrap'),
        list: $('#bk-list'),
        step2: $('#bk-step2'),
        step3: $('#bk-step3')
      };
    },

    initDates: function () {
      var today = new Date();
      var t = dateStr(today);
      this.els.checkIn.value = t;
      this.els.checkIn.min = t;
      this.els.checkOut.value = addDays(t, 1);
      this.els.checkOut.min = addDays(t, 1);
      this.updateNights();
    },

    bind: function () {
      var self = this;
      this.els.checkIn.addEventListener('change', function () {
        var inD = self.els.checkIn.value;
        if (!inD) return;
        self.els.checkOut.min = addDays(inD, 1);
        var nights = nightsBetween(inD, self.els.checkOut.value);
        if (nights < 1) {
          self.els.checkOut.value = addDays(inD, 1);
          Toast.info('离店日期已自动调整为入住次日');
        } else if (nights > MAX_NIGHTS) {
          self.els.checkOut.value = addDays(inD, MAX_NIGHTS);
          Toast.info('单次入住最长 ' + MAX_NIGHTS + ' 晚，离店日期已调整');
        }
        self.updateNights();
        if (self.state.step === 2) {
          // 第二步的价格/晚数来自旧日期的房态快照，日期变更必须回退重新选房，避免展示价与实付价不一致
          self.backToStep1();
          Toast.info('日期已变更，请重新确认房型与价格');
          self.query({ quiet: true });
        }
      });
      this.els.checkOut.addEventListener('change', function () {
        var inD = self.els.checkIn.value;
        var nights = nightsBetween(inD, self.els.checkOut.value);
        if (nights < 1) {
          self.els.checkOut.value = addDays(inD, 1);
          Toast.info('离店日期必须晚于入住日期，已自动调整');
        } else if (nights > MAX_NIGHTS) {
          self.els.checkOut.value = addDays(inD, MAX_NIGHTS);
          Toast.info('单次入住最长 ' + MAX_NIGHTS + ' 晚，离店日期已调整');
        }
        self.updateNights();
        if (self.state.step === 2) {
          self.backToStep1();
          Toast.info('日期已变更，请重新确认房型与价格');
          self.query({ quiet: true });
        }
      });
      this.els.queryBtn.addEventListener('click', function () { self.query(); });
    },

    updateNights: function () {
      var n = Math.max(0, nightsBetween(this.els.checkIn.value, this.els.checkOut.value));
      this.els.nights.textContent = String(n);
    },

    query: function (opts) {
      opts = opts || {};
      var self = this;
      var inD = this.els.checkIn.value;
      var outD = this.els.checkOut.value;
      if (!inD || !outD) { Toast.info('请先选择入住与离店日期'); return; }
      if (nightsBetween(inD, outD) < 1) {
        Toast.error('离店日期必须晚于入住日期');
        return;
      }
      setBusy(this.els.queryBtn, true, '查询中…');
      if (!opts.quiet) this.els.listWrap.classList.add('is-refreshing');
      API.availability(inD, outD).then(function (data) {
        self.state.availability = data || [];
        self.renderAvailability(null);
      }).catch(function (e) {
        self.state.availability = [];
        self.renderAvailability(e.message);
        if (!opts.quiet) Toast.error(e.message);
      }).finally(function () {
        setBusy(self.els.queryBtn, false);
        self.els.listWrap.classList.remove('is-refreshing');
      });
    },

    /** 下单成功后静默刷新房态余量（保留上一帧、不打扰） */
    refreshAvailabilityQuiet: function () {
      var self = this;
      API.availability(this.els.checkIn.value, this.els.checkOut.value).then(function (data) {
        self.state.availability = data || [];
        self.renderAvailability(null);
      }).catch(function () { /* 静默失败 */ });
    },

    renderAvailability: function (errorMsg) {
      clear(this.els.list);
      var self = this;
      if (!this.state.availability.length) {
        this.els.list.appendChild(errorMsg
          ? emptyState('📡', '查询失败', errorMsg, null)
          : emptyState('🏨', '该日期区间暂无可售房型', '换个日期再试试，或返回默认日期查询', null));
        return;
      }
      this.state.availability.forEach(function (a) {
        self.els.list.appendChild(self.renderRoomCard(a));
      });
    },

    renderRoomCard: function (a) {
      var self = this;
      var soldOut = a.remaining <= 0;
      var card = el('article', 'room-card' + (soldOut ? ' room-card--sold' : ''));

      var head = el('div', 'room-head');
      head.appendChild(el('h3', 'room-name', a.name || '—'));
      if (a.code) head.appendChild(el('span', 'tag tag-code', a.code));
      var stock = soldOut
        ? el('span', 'chip chip--danger', '满房')
        : el('span', 'chip chip--accent', '剩 ' + a.remaining + ' 间');
      stock.classList.add('room-stock');
      head.appendChild(stock);
      card.appendChild(head);

      if (a.amenities) {
        var am = el('div', 'room-amenities');
        String(a.amenities).split('·').map(function (s) { return s.trim(); })
          .filter(Boolean)
          .forEach(function (t) { am.appendChild(el('span', 'amenity', t)); });
        card.appendChild(am);
      }

      var list = el('div', 'night-list');
      (a.priceDetails || []).forEach(function (d) {
        var row = el('div', 'night-row');
        var wk = fmt.isWeekendNight(d.date);
        var hiked = Number(d.price) > Number(a.basePrice);
        if (wk || hiked) row.classList.add('night-row--weekend');
        var left = el('span', 'night-date', fmt.date(d.date));
        left.appendChild(el('span', 'night-dow', ' ' + fmt.weekday(d.date)));
        var right = el('span', 'night-right');
        if (wk) right.appendChild(el('span', 'tag tag-weekend', '周末价'));
        else if (hiked) right.appendChild(el('span', 'tag tag-weekend', '调价'));
        right.appendChild(el('span', 'night-price', fmt.money(d.price)));
        row.appendChild(left);
        row.appendChild(right);
        list.appendChild(row);
      });
      card.appendChild(list);

      var foot = el('div', 'room-foot');
      var price = el('span', 'price-total num', fmt.money(a.totalPrice));
      price.appendChild(el('span', 'price-cap', ' / ' + a.nights + ' 晚 · 1 间'));
      foot.appendChild(price);
      var btn = el('button', 'btn btn-primary');
      if (soldOut) {
        btn.textContent = '已满房';
        btn.disabled = true;
      } else {
        btn.textContent = '预订';
        btn.addEventListener('click', function () { self.openStep2(a); });
      }
      foot.appendChild(btn);
      card.appendChild(foot);
      return card;
    },

    openStep2: function (a) {
      this.state.selected = a;
      this.state.step = 2;
      this.state.form.count = clamp(Number(this.state.form.count) || 1, 1, MAX_ROOMS);
      this.renderStep2();
      this.els.listWrap.hidden = true;
      this.els.step2.hidden = false;
      this.els.step3.hidden = true;
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },

    backToStep1: function () {
      this.state.step = 1;
      this.els.step2.hidden = true;
      this.els.step3.hidden = true;
      this.els.listWrap.hidden = false;
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },

    renderStep2: function () {
      var self = this;
      var a = this.state.selected;
      if (!a) return;
      clear(this.els.step2);
      var grid = el('div', 'panel-grid');

      /* ---- 摘要卡 ---- */
      var sc = el('div', 'card');
      var t1 = el('div', 'panel-title');
      t1.appendChild(el('span', 'step-num', '2'));
      t1.appendChild(el('span', null, '确认预订信息'));
      sc.appendChild(t1);
      var rows = el('div', 'summary-rows');
      summaryRow(rows, '房型', (a.name || '') + '（' + (a.code || '') + '）');
      summaryRow(rows, '入住', fmt.date(this.els.checkIn.value) + ' ' + fmt.weekday(this.els.checkIn.value));
      summaryRow(rows, '离店', fmt.date(this.els.checkOut.value) + ' ' + fmt.weekday(this.els.checkOut.value));
      summaryRow(rows, '晚数', a.nights + ' 晚');
      var countRow = el('div', 'summary-row');
      countRow.appendChild(el('span', 'k', '间数'));
      var sel = el('select', 'input input-sm select');
      for (var i = 1; i <= MAX_ROOMS; i++) {
        var o = el('option', null, i + ' 间');
        o.value = String(i);
        if (i === Number(this.state.form.count)) o.selected = true;
        sel.appendChild(o);
      }
      countRow.appendChild(sel);
      rows.appendChild(countRow);
      sc.appendChild(rows);

      var nl = el('div', 'night-list');
      nl.style.marginTop = '12px';
      (a.priceDetails || []).forEach(function (d) {
        var r = el('div', 'night-row');
        if (fmt.isWeekendNight(d.date)) r.classList.add('night-row--weekend');
        var left = el('span', 'night-date', fmt.date(d.date) + ' ' + fmt.weekday(d.date));
        var right = el('span', 'night-right');
        if (fmt.isWeekendNight(d.date)) right.appendChild(el('span', 'tag tag-weekend', '周末价'));
        right.appendChild(el('span', 'night-price', fmt.money(d.price)));
        r.appendChild(left);
        r.appendChild(right);
        nl.appendChild(r);
      });
      sc.appendChild(nl);

      var total = el('div', 'summary-total');
      total.appendChild(el('span', 'k', '预计总价'));
      var totalV = el('span', 'v', fmt.money(Number(a.totalPrice) * Number(this.state.form.count)));
      total.appendChild(totalV);
      sc.appendChild(total);
      grid.appendChild(sc);

      /* ---- 入住人表单卡 ---- */
      var fc = el('div', 'card');
      var t2 = el('div', 'panel-title');
      t2.appendChild(el('span', 'step-num', '2'));
      t2.appendChild(el('span', null, '入住人信息'));
      fc.appendChild(t2);
      var form = el('div', 'form-grid');

      var nameInput = el('input', 'input');
      nameInput.type = 'text';
      nameInput.maxLength = 64;
      nameInput.placeholder = '与证件一致的姓名';
      nameInput.value = this.state.form.name || '';
      var phoneInput = el('input', 'input');
      phoneInput.type = 'tel';
      phoneInput.maxLength = 11;
      phoneInput.inputMode = 'numeric';
      phoneInput.placeholder = '11 位手机号（1 开头），用于查询订单';
      phoneInput.value = this.state.form.phone || '';
      var idInput = el('input', 'input');
      idInput.type = 'text';
      idInput.maxLength = 32;
      idInput.placeholder = '选填，入住时出示';
      idInput.value = this.state.form.idNo || '';
      this.state.fields = { name: nameInput, phone: phoneInput, idNo: idInput };

      nameInput.addEventListener('input', function () {
        self.state.form.name = nameInput.value;
        clearFieldError(nameInput);
      });
      phoneInput.addEventListener('input', function () {
        phoneInput.value = phoneInput.value.replace(/\D/g, '');
        self.state.form.phone = phoneInput.value;
        clearFieldError(phoneInput);
      });
      idInput.addEventListener('input', function () {
        self.state.form.idNo = idInput.value;
        clearFieldError(idInput);
      });

      var mkField = function (labelText, input) {
        var f = el('label', 'field');
        f.appendChild(el('span', 'field-label', labelText));
        f.appendChild(input);
        return f;
      };
      form.appendChild(mkField('姓名 *', nameInput));
      form.appendChild(mkField('手机号 *', phoneInput));
      form.appendChild(mkField('证件号（选填）', idInput));

      var actions = el('div', 'form-actions');
      var backBtn = el('button', 'btn btn-ghost', '← 返回房型');
      backBtn.type = 'button';
      backBtn.addEventListener('click', function () { self.backToStep1(); });
      var submitBtn = el('button', 'btn btn-primary',
        '确认预订 · ' + fmt.money(Number(a.totalPrice) * Number(self.state.form.count)));
      submitBtn.type = 'button';
      submitBtn.addEventListener('click', function () { self.submitBooking(submitBtn); });
      actions.appendChild(backBtn);
      actions.appendChild(submitBtn);
      form.appendChild(actions);
      fc.appendChild(form);
      grid.appendChild(fc);

      this.els.step2.appendChild(grid);

      sel.addEventListener('change', function () {
        self.state.form.count = Number(sel.value);
        var t = Number(a.totalPrice) * Number(self.state.form.count);
        totalV.textContent = fmt.money(t);
        if (!submitBtn.disabled) submitBtn.textContent = '确认预订 · ' + fmt.money(t);
      });
    },

    submitBooking: function (submitBtn) {
      var a = this.state.selected;
      if (!a) return;
      var f = this.state.fields || {};
      var name = (this.state.form.name || '').trim();
      var phone = (this.state.form.phone || '').trim();
      var idNo = (this.state.form.idNo || '').trim();

      var ok = true;
      if (!name) { setFieldError(f.name, '请输入入住人姓名'); ok = false; }
      else if (name.length > 64) { setFieldError(f.name, '姓名长度超限（最多 64 字）'); ok = false; }
      if (!PHONE_RE.test(phone)) {
        setFieldError(f.phone, '手机号格式不正确（11 位数字，1 开头）');
        ok = false;
      }
      if (idNo && idNo.length > 32) { setFieldError(f.idNo, '证件号长度超限（最多 32 位）'); ok = false; }
      if (!ok) return;

      // 幂等键：每次“下单动作”生成一次，失败重试沿用同一 requestId
      if (!this.state.requestId) this.state.requestId = newRequestId();

      var body = {
        roomTypeId: a.roomTypeId,
        checkIn: this.els.checkIn.value,
        checkOut: this.els.checkOut.value,
        roomCount: Number(this.state.form.count),
        guestName: name,
        guestPhone: phone,
        requestId: this.state.requestId
      };
      if (idNo) body.guestIdNo = idNo;

      var self = this;
      setBusy(submitBtn, true, '提交中…');
      API.createOrder(body).then(function (order) {
        self.state.requestId = null;
        self.state.result = order;
        self.state.step = 3;
        self.renderResult();
        self.els.step2.hidden = true;
        self.els.step3.hidden = false;
        self.els.listWrap.hidden = true;
        window.scrollTo({ top: 0, behavior: 'smooth' });
        self.refreshAvailabilityQuiet();
      }).catch(function (e) {
        Toast.error(e.message);
        setBusy(submitBtn, false);
      });
    },

    renderResult: function () {
      var self = this;
      var o = this.state.result;
      if (!o) return;
      clear(this.els.step3);
      var card = el('div', 'result-card');

      var top = el('div', 'result-top');
      top.appendChild(el('div', 'result-check', '✓'));
      var tbox = el('div');
      tbox.appendChild(el('div', 'result-title', '预订成功'));
      tbox.appendChild(el('div', 'result-sub', '房间已为您锁定，请尽快完成支付'));
      top.appendChild(tbox);
      card.appendChild(top);

      var noRow = el('div', 'result-no-row');
      noRow.appendChild(el('span', 'muted', '订单号'));
      noRow.appendChild(el('span', 'result-no', o.orderNo));
      var copyBtn = el('button', 'copy-btn', '复制');
      copyBtn.type = 'button';
      copyBtn.addEventListener('click', function () {
        copyText(o.orderNo).then(function (okd) {
          Toast.show(okd ? '订单号已复制' : '复制失败，请手动选择复制', okd ? 'success' : 'error');
        });
      });
      noRow.appendChild(copyBtn);
      card.appendChild(noRow);
      card.appendChild(badge(o.status));

      var rows = el('div', 'summary-rows');
      summaryRow(rows, '房型', (o.roomTypeName || '—') + '（' + (o.roomTypeCode || '') + '）');
      summaryRow(rows, '入住 / 离店', fmt.date(o.checkIn) + ' → ' + fmt.date(o.checkOut));
      summaryRow(rows, '晚数 / 间数', o.nights + ' 晚 · ' + o.roomCount + ' 间');
      summaryRow(rows, '入住人', o.guestName || '—');
      summaryRow(rows, '手机号', o.guestPhone || '—');
      summaryRow(rows, '证件号', o.guestIdNo || '未填写');
      card.appendChild(rows);

      var amt = el('div', 'result-amount');
      amt.appendChild(el('span', 'k', '应付金额'));
      amt.appendChild(el('span', 'v', fmt.money(o.totalPrice)));
      card.appendChild(amt);

      var actions = el('div', 'result-actions');
      if (o.status === 'PENDING_PAYMENT') {
        var payBtn = el('button', 'btn btn-primary', '💳 模拟支付');
        payBtn.type = 'button';
        payBtn.addEventListener('click', function () { self.payFromResult(payBtn, o.orderNo); });
        actions.appendChild(payBtn);
      }
      var viewBtn = el('button', 'btn btn-ghost', '查看订单');
      viewBtn.type = 'button';
      viewBtn.addEventListener('click', function () { goToOrders(o.orderNo); });
      actions.appendChild(viewBtn);
      var againBtn = el('button', 'btn btn-ghost', '再订一间');
      againBtn.type = 'button';
      againBtn.addEventListener('click', function () {
        self.backToStep1();
        self.refreshAvailabilityQuiet();
      });
      actions.appendChild(againBtn);
      card.appendChild(actions);

      this.els.step3.appendChild(card);
    },

    payFromResult: function (payBtn, orderNo) {
      var self = this;
      setBusy(payBtn, true, '支付中…');
      API.pay(orderNo).then(function (updated) {
        self.state.result = updated;
        self.renderResult();
        Toast.success('支付成功，订单已确认');
      }).catch(function (e) {
        Toast.error(e.message);
        setBusy(payBtn, false);
      });
    }
  };

  /* ================= 视图二：订单查询 ================= */

  var DEMO_PHONES = ['13800138001', '13800138002', '13800138003', '13800138004'];

  var ordersView = {
    state: { items: [], expanded: {} },
    loaded: false,
    pendingQuery: null,
    els: null,

    ensure: function () {
      if (!this.loaded) {
        this.cacheEls();
        this.bind();
        this.loaded = true;
      }
      if (this.pendingQuery) {
        var q = this.pendingQuery;
        this.pendingQuery = null;
        this.els.input.value = q;
        this.search();
      }
    },

    cacheEls: function () {
      this.els = {
        input: $('#od-input'),
        searchBtn: $('#od-search'),
        results: $('#od-results')
      };
    },

    bind: function () {
      var self = this;
      this.els.searchBtn.addEventListener('click', function () { self.search(); });
      this.els.input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.isComposing) self.search();
      });
    },

    search: function () {
      var self = this;
      var q = this.els.input.value.trim();
      if (!q) {
        Toast.info('请输入手机号或订单号');
        this.els.input.focus();
        return;
      }
      var isPhone = PHONE_RE.test(q);
      setBusy(this.els.searchBtn, true, '查询中…');
      var task = isPhone ? API.ordersByPhone(q) : API.orderDetail(q).then(function (one) { return [one]; });
      task.then(function (items) {
        self.state.items = items || [];
        self.state.expanded = {};
        if (self.state.items.length === 1) {
          self.state.expanded[self.state.items[0].orderNo] = true;
        }
        self.render(null);
      }).catch(function (e) {
        self.state.items = [];
        self.render(e.message);
        Toast.error(e.message);
      }).finally(function () {
        setBusy(self.els.searchBtn, false);
      });
    },

    render: function (errorMsg) {
      var self = this;
      clear(this.els.results);
      if (!this.state.items.length) {
        this.els.results.appendChild(errorMsg
          ? emptyState('📡', '查询失败', errorMsg, null)
          : emptyState('🔍', '未查询到订单',
            '试试演示数据（内置四笔演示订单），或换个手机号 / 订单号',
            DEMO_PHONES.map(function (p) {
              return {
                label: '📱 ' + p,
                onClick: function () { self.els.input.value = p; self.search(); }
              };
            })));
        return;
      }
      this.state.items.forEach(function (o) {
        self.els.results.appendChild(self.renderCard(o));
      });
    },

    renderCard: function (o) {
      var self = this;
      var open = !!this.state.expanded[o.orderNo];
      var card = el('article', 'order-card' + (open ? ' order-card--open' : ''));

      var head = el('button', 'order-head');
      head.type = 'button';
      head.setAttribute('aria-expanded', open ? 'true' : 'false');
      var main = el('div', 'order-main');
      main.appendChild(el('div', 'order-no', o.orderNo));
      main.appendChild(el('div', 'order-meta',
        (o.roomTypeName || '—') + ' · ' + fmt.date(o.checkIn) + ' → ' + fmt.date(o.checkOut) +
        ' · ' + o.nights + ' 晚 · ' + o.roomCount + ' 间 · 下单 ' + fmt.datetime(o.createdAt)));
      head.appendChild(main);
      head.appendChild(badge(o.status));
      head.appendChild(el('span', 'order-money', fmt.money(o.totalPrice)));
      head.appendChild(el('span', 'chevron', '›'));
      head.addEventListener('click', function () {
        if (self.state.expanded[o.orderNo]) delete self.state.expanded[o.orderNo];
        else self.state.expanded[o.orderNo] = true;
        self.render(null);
      });
      card.appendChild(head);

      if (open) card.appendChild(this.renderDetail(o));
      return card;
    },

    renderDetail: function (o) {
      var self = this;
      var detail = el('div', 'order-detail');

      var left = el('div');
      left.appendChild(el('div', 'detail-col-title', '客人信息'));
      var rows = el('div', 'detail-rows');
      var addDetailRow = function (k, v) {
        var r = el('div', 'detail-row');
        r.appendChild(el('span', 'k', k));
        r.appendChild(el('span', 'v', v));
        rows.appendChild(r);
      };
      addDetailRow('入住人', o.guestName || '—');
      addDetailRow('手机号', o.guestPhone || '—');
      addDetailRow('证件号', o.guestIdNo || '未填写');
      addDetailRow('房型', (o.roomTypeName || '—') + '（' + (o.roomTypeCode || '') + '）');
      addDetailRow('入住 / 离店', fmt.date(o.checkIn) + ' → ' + fmt.date(o.checkOut));
      addDetailRow('晚数 / 间数', o.nights + ' 晚 · ' + o.roomCount + ' 间');
      addDetailRow('下单时间', fmt.datetime(o.createdAt));
      left.appendChild(rows);
      detail.appendChild(left);

      var right = el('div');
      right.appendChild(el('div', 'detail-col-title', '状态时间线'));
      var tl = el('div', 'timeline');
      var logs = o.logs || [];
      if (!logs.length) {
        tl.appendChild(el('div', 'muted', '暂无状态记录'));
      }
      logs.forEach(function (l) {
        var meta = statusOf(l.toStatus);
        var node = el('div', 'tl-node');
        node.style.setProperty('--tl-color', toneInk(meta.tone));
        node.appendChild(el('div', 'tl-title', meta.icon + ' ' + meta.label));
        if (l.remark) node.appendChild(el('div', 'tl-remark', l.remark));
        node.appendChild(el('div', 'tl-meta',
          fmt.datetime(l.createdAt) + ' · ' + operatorLabel(l.operator) +
          (l.fromStatusLabel ? ' · ' + l.fromStatusLabel + ' → ' + meta.label : ' · 创建订单')));
        tl.appendChild(node);
      });
      right.appendChild(tl);
      detail.appendChild(right);

      var actions = el('div', 'order-actions');
      if (o.status === 'PENDING_PAYMENT') {
        var payBtn = el('button', 'btn btn-primary btn-sm', '💳 模拟支付');
        payBtn.type = 'button';
        payBtn.addEventListener('click', function () { self.doPay(payBtn, o.orderNo); });
        actions.appendChild(payBtn);
        var cancelBtn1 = el('button', 'btn btn-danger btn-sm', '取消订单');
        cancelBtn1.type = 'button';
        cancelBtn1.addEventListener('click', function () { self.doCancel(o.orderNo); });
        actions.appendChild(cancelBtn1);
      } else if (o.status === 'CONFIRMED') {
        var cancelBtn2 = el('button', 'btn btn-danger btn-sm', '取消订单');
        cancelBtn2.type = 'button';
        cancelBtn2.addEventListener('click', function () { self.doCancel(o.orderNo); });
        actions.appendChild(cancelBtn2);
      } else {
        actions.appendChild(el('span', 'muted', '该状态无可执行操作'));
      }
      detail.appendChild(actions);
      return detail;
    },

    updateItem: function (updated) {
      this.state.items = this.state.items.map(function (o) {
        return o.orderNo === updated.orderNo ? updated : o;
      });
      this.render(null);
    },

    doPay: function (btn, orderNo) {
      var self = this;
      setBusy(btn, true, '支付中…');
      API.pay(orderNo).then(function (updated) {
        self.updateItem(updated);
        Toast.success('支付成功，订单已确认');
      }).catch(function (e) {
        Toast.error(e.message);
        setBusy(btn, false);
      });
    },

    doCancel: function (orderNo) {
      var self = this;
      confirmDialog({
        title: '取消订单',
        message: '确认取消订单 ' + orderNo + '？\n取消后已占用的房量将释放，此操作不可撤销。',
        confirmText: '确认取消',
        danger: true
      }).then(function (yes) {
        if (!yes) return;
        API.cancel(orderNo).then(function (updated) {
          self.updateItem(updated);
          Toast.success('订单已取消，房量已释放');
        }).catch(function (e) {
          Toast.error(e.message);
        });
      });
    }
  };

  function goToOrders(orderNo) {
    ordersView.pendingQuery = orderNo;
    if (location.hash === '#/orders') ordersView.ensure();
    else location.hash = '#/orders';
  }

  /* ================= 视图三：前台管理 ================= */

  /** 条形图：网格线/刻度相对轨道区域的横向位置（单序列、slot-1 蓝、值在端外标注） */
  function trackX(p) {
    return 'calc(var(--name-w) + var(--gap) + ' +
      '(100% - var(--name-w) - 2 * var(--gap) - var(--val-w)) * ' + (p / 100) + ')';
  }

  var chartTip = null;
  function ensureChartTip() {
    if (!chartTip) {
      chartTip = el('div', 'chart-tooltip');
      chartTip.setAttribute('role', 'tooltip');
      document.body.appendChild(chartTip);
    }
    return chartTip;
  }
  function fillChartTip(rt) {
    clear(chartTip);
    chartTip.appendChild(el('div', 'tt-title', (rt.name || '') + (rt.code ? '（' + rt.code + '）' : '')));
    var mk = function (k, v) {
      var r = el('div', 'tt-row');
      r.appendChild(el('span', 'k', k));
      r.appendChild(el('span', 'v', v));
      chartTip.appendChild(r);
    };
    mk('总房数', fmt.num(rt.total));
    mk('已售间数', fmt.num(rt.sold));
    mk('入住率', fmt.pct(rt.rate));
  }
  function moveChartTip(x, y) {
    var pad = 14;
    var rect = chartTip.getBoundingClientRect();
    var px = x + pad;
    var py = y + pad;
    if (px + rect.width > window.innerWidth - 8) px = x - rect.width - pad;
    if (py + rect.height > window.innerHeight - 8) py = y - rect.height - pad;
    chartTip.style.left = px + 'px';
    chartTip.style.top = py + 'px';
  }
  function hideChartTip() {
    if (chartTip) {
      chartTip.parentNode.removeChild(chartTip);
      chartTip = null;
    }
  }

  var frontdesk = {
    state: { stats: null, orders: [] },
    loaded: false,
    els: null,

    ensure: function () {
      if (!this.loaded) {
        this.cacheEls();
        this.bind();
        this.loaded = true;
      }
      this.loadAll();
    },

    cacheEls: function () {
      this.els = {
        statsRow: $('#fd-stats'),
        chartBody: $('#fd-occupancy'),
        occTbody: $('#fd-occupancy-tbody'),
        statusSel: $('#fd-status'),
        dateInput: $('#fd-date'),
        refreshBtn: $('#fd-refresh'),
        tbody: $('#fd-tbody')
      };
    },

    bind: function () {
      var self = this;
      this.els.statusSel.addEventListener('change', function () { self.loadOrders(); });
      this.els.dateInput.addEventListener('change', function () { self.loadOrders(); });
      this.els.refreshBtn.addEventListener('click', function () { self.loadAll(); });
    },

    loadAll: function () {
      return Promise.allSettled([this.loadStats(), this.loadOrders()]);
    },

    loadStats: function () {
      var self = this;
      this.els.statsRow.classList.add('is-refreshing');
      this.els.chartBody.classList.add('is-refreshing');
      return API.frontDeskStats().then(function (s) {
        self.state.stats = s;
        self.renderStats(s);
        self.renderChart(s.roomTypes || []);
      }).catch(function (e) {
        Toast.error('统计加载失败：' + e.message);
      }).finally(function () {
        self.els.statsRow.classList.remove('is-refreshing');
        self.els.chartBody.classList.remove('is-refreshing');
      });
    },

    renderStats: function (s) {
      var row = this.els.statsRow;
      clear(row);
      var mkTile = function (label, valueText, sub) {
        var tile = el('div', 'stat-tile');
        tile.appendChild(el('div', 'stat-label', label));
        tile.appendChild(el('div', 'stat-value num', valueText));
        tile.appendChild(el('div', 'stat-sub', sub));
        return tile;
      };
      row.appendChild(mkTile('今日入住', fmt.num(s.todayCheckIn), '今日已办理入住'));
      row.appendChild(mkTile('今日退房', fmt.num(s.todayCheckOut), '今日已办理退房'));
      row.appendChild(mkTile('当前在住', fmt.num(s.inHouse), '当前在住客人'));
      row.appendChild(mkTile('今日营业额', fmt.money(s.todayRevenue), '今日未取消订单金额合计'));

      // 今日入住率：数值 + 同色阶仪表（浅轨道、深填充）
      var occ = el('div', 'stat-tile');
      occ.appendChild(el('div', 'stat-label', '今日入住率'));
      occ.appendChild(el('div', 'stat-value num', fmt.pct(s.occupancyRate)));
      var meter = el('div', 'meter');
      var fill = el('div', 'meter-fill');
      fill.style.width = clamp(Number(s.occupancyRate) || 0, 0, 100) + '%';
      meter.appendChild(fill);
      occ.appendChild(meter);
      occ.appendChild(el('div', 'stat-sub', '整体入住率（已售 ÷ 总房数）'));
      row.appendChild(occ);
    },

    renderChart: function (list) {
      var body = this.els.chartBody;
      var tb = this.els.occTbody;
      clear(body);
      clear(tb);

      if (!list || !list.length) {
        body.appendChild(el('div', 'empty-hint', '暂无房型数据'));
        var tr0 = el('tr');
        var td0 = el('td', 'empty-cell', '暂无数据');
        td0.colSpan = 4;
        tr0.appendChild(td0);
        tb.appendChild(tr0);
        return;
      }

      var ticks = [0, 25, 50, 75, 100];
      var plot = el('div', 'bar-plot');

      // 竖向网格线：表面色一步之外的细线，弱化不抢数据
      var grid = el('div', 'bar-grid');
      ticks.forEach(function (p) {
        var line = el('div', 'bar-gridline');
        line.style.left = trackX(p);
        grid.appendChild(line);
      });
      plot.appendChild(grid);

      // 条形行：<=24px 厚、左端齐基线、右端 4px 圆角（CSS 控制）
      list.forEach(function (rt) {
        var rate = clamp(Number(rt.rate) || 0, 0, 100);
        var row = el('div', 'bar-row');
        row.tabIndex = 0;
        row.setAttribute('role', 'listitem');
        row.setAttribute('aria-label',
          (rt.name || '') + '：已售 ' + rt.sold + ' 间 / 共 ' + rt.total + ' 间，入住率 ' + fmt.pct(rt.rate));
        var name = el('div', 'bar-name', rt.name || '—');
        if (rt.code) name.appendChild(el('span', 'bar-code', rt.code));
        var track = el('div', 'bar-track');
        var fill = el('div', 'bar-fill');
        fill.style.width = rate + '%';
        track.appendChild(fill);
        row.appendChild(name);
        row.appendChild(track);
        row.appendChild(el('div', 'bar-val', fmt.pct(rt.rate)));
        row.addEventListener('mouseenter', function (e) {
          ensureChartTip();
          fillChartTip(rt);
          moveChartTip(e.clientX, e.clientY);
        });
        row.addEventListener('mousemove', function (e) {
          if (chartTip) moveChartTip(e.clientX, e.clientY);
        });
        row.addEventListener('mouseleave', hideChartTip);
        row.addEventListener('focus', function () {
          ensureChartTip();
          fillChartTip(rt);
          var r = row.getBoundingClientRect();
          var tip = ensureChartTip();
          var w = tip.offsetWidth;
          tip.style.left = Math.max(8, r.right - w) + 'px';
          tip.style.top = (r.bottom + 8) + 'px';
        });
        row.addEventListener('blur', hideChartTip);
        plot.appendChild(row);
      });

      // 底部刻度：文字用 muted 墨水，绝不使用序列色
      var tickRow = el('div', 'bar-ticks');
      ticks.forEach(function (p) {
        var t = el('span', 'bar-tick', p + '%');
        t.style.left = trackX(p);
        if (p === 0) t.style.transform = 'translateX(0)';
        else if (p === 100) t.style.transform = 'translateX(-100%)';
        tickRow.appendChild(t);
      });
      plot.appendChild(tickRow);
      body.appendChild(plot);

      // 表格视图（图表的数据双胞胎：颜色之外的完整读取通道）
      list.forEach(function (rt) {
        var tr = el('tr');
        var c1 = el('td', 'cell-main', rt.name || '—');
        if (rt.code) c1.appendChild(el('span', 'cell-sub', ' ' + rt.code));
        tr.appendChild(c1);
        tr.appendChild(el('td', 'num', fmt.num(rt.total)));
        tr.appendChild(el('td', 'num', fmt.num(rt.sold)));
        tr.appendChild(el('td', 'num', fmt.pct(rt.rate)));
        tb.appendChild(tr);
      });
    },

    loadOrders: function (opts) {
      opts = opts || {};
      var self = this;
      this.els.tbody.classList.add('is-refreshing');
      if (opts.fromRefresh) setBusy(this.els.refreshBtn, true, '刷新中…');
      return API.frontDeskOrders(
        this.els.statusSel.value || undefined,
        this.els.dateInput.value || undefined
      ).then(function (list) {
        self.state.orders = list || [];
        self.renderTable();
      }).catch(function (e) {
        Toast.error('订单列表加载失败：' + e.message);
      }).finally(function () {
        self.els.tbody.classList.remove('is-refreshing');
        setBusy(self.els.refreshBtn, false);
      });
    },

    renderTable: function () {
      var self = this;
      clear(this.els.tbody);
      if (!this.state.orders.length) {
        var tr = el('tr');
        var td = el('td', 'empty-cell', '暂无符合条件的订单');
        td.colSpan = 9;
        tr.appendChild(td);
        this.els.tbody.appendChild(tr);
        return;
      }
      this.state.orders.forEach(function (o) {
        var tr = el('tr');
        tr.appendChild(el('td', 'cell-main num', o.orderNo));
        tr.appendChild(el('td', null, o.roomTypeName || '—'));
        var g = el('td');
        g.appendChild(el('div', 'cell-main', o.guestName || '—'));
        g.appendChild(el('div', 'cell-sub', o.guestPhone || ''));
        tr.appendChild(g);
        tr.appendChild(el('td', 'num', fmt.date(o.checkIn)));
        tr.appendChild(el('td', 'num', fmt.date(o.checkOut)));
        tr.appendChild(el('td', 'num', String(o.roomCount)));
        tr.appendChild(el('td', 'num', fmt.money(o.totalPrice)));
        var bd = el('td');
        bd.appendChild(badge(o.status));
        tr.appendChild(bd);
        var act = el('td');
        if (o.status === 'CONFIRMED') {
          var inBtn = el('button', 'btn btn-primary btn-sm', '办理入住');
          inBtn.type = 'button';
          inBtn.addEventListener('click', function () { self.doCheckIn(inBtn, o); });
          act.appendChild(inBtn);
        } else if (o.status === 'CHECKED_IN') {
          var outBtn = el('button', 'btn btn-ghost btn-sm', '办理退房');
          outBtn.type = 'button';
          outBtn.addEventListener('click', function () { self.doCheckOut(outBtn, o); });
          act.appendChild(outBtn);
        } else {
          act.appendChild(el('span', 'muted', '—'));
        }
        tr.appendChild(act);
        self.els.tbody.appendChild(tr);
      });
    },

    doCheckIn: function (btn, o) {
      var self = this;
      setBusy(btn, true, '办理中…');
      API.checkIn(o.orderNo).then(function () {
        Toast.success('已为 ' + o.guestName + ' 办理入住（' + o.orderNo + '）');
        self.loadAll();
      }).catch(function (e) {
        Toast.error(e.message);
        setBusy(btn, false);
      });
    },

    doCheckOut: function (btn, o) {
      var self = this;
      setBusy(btn, true, '办理中…');
      API.checkOut(o.orderNo).then(function () {
        Toast.success('已为 ' + o.guestName + ' 办理退房（' + o.orderNo + '）');
        self.loadAll();
      }).catch(function (e) {
        Toast.error(e.message);
        setBusy(btn, false);
      });
    }
  };

  /* ================= 路由 ================= */

  var ROUTES = ['booking', 'orders', 'frontdesk'];
  var views = {
    booking: { el: $('#view-booking'), ensure: function () { booking.ensure(); } },
    orders: { el: $('#view-orders'), ensure: function () { ordersView.ensure(); } },
    frontdesk: { el: $('#view-frontdesk'), ensure: function () { frontdesk.ensure(); } }
  };
  var activeView = null;

  function currentRoute() {
    var h = location.hash.replace(/^#\/?/, '');
    return ROUTES.indexOf(h) >= 0 ? h : 'booking';
  }

  function showView(name) {
    if (!views[name]) return;
    if (activeView === name) {
      views[name].ensure(); // 再触发：刷新数据（前台）或处理待执行查询
      return;
    }
    ROUTES.forEach(function (r) {
      views[r].el.hidden = (r !== name);
    });
    $$('.tab').forEach(function (t) {
      t.classList.toggle('active', t.getAttribute('data-route') === name);
    });
    views[name].ensure();
    activeView = name;
  }

  /* ================= 启动 ================= */

  function boot() {
    initTheme();
    // 视图容器可聚焦，便于切换后键盘导航
    ROUTES.forEach(function (r) {
      views[r].el.setAttribute('tabindex', '-1');
    });
    if (!location.hash) {
      history.replaceState(null, '', '#/booking');
    }
    window.addEventListener('hashchange', function () {
      showView(currentRoute());
      views[currentRoute()].el.focus({ preventScroll: true });
    });
    showView(currentRoute());
  }

  boot();
})();
