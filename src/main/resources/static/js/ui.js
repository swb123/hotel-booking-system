/**
 * 通用 UI 工具：DOM 构造（仅 textContent / createElement，杜绝 innerHTML 注入）、
 * 格式化、状态徽标、Toast、确认弹窗、剪贴板、按钮忙碌态。
 */
(function () {
  'use strict';

  var WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  /* ---------- DOM 构造 ---------- */
  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  /* ---------- 日期解析（本地时区，避免 UTC 偏移） ---------- */
  function parseDate(s) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(s));
    if (!m) return null;
    return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  }

  /* ---------- 格式化 ---------- */
  var fmt = {
    money: function (v) {
      var n = Number(v);
      if (v === null || v === undefined || v === '' || isNaN(n)) return '—';
      return '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    },
    num: function (v) {
      var n = Number(v);
      if (v === null || v === undefined || v === '' || isNaN(n)) return '—';
      return n.toLocaleString('zh-CN');
    },
    pct: function (v, digits) {
      var n = Number(v);
      if (v === null || v === undefined || v === '' || isNaN(n)) return '—';
      return n.toFixed(digits === undefined ? 1 : digits) + '%';
    },
    date: function (v) {
      if (!v) return '—';
      return String(v).slice(0, 10);
    },
    datetime: function (v) {
      if (!v) return '—';
      var s = String(v);
      if (s.length < 16) return s;
      return s.slice(0, 10) + ' ' + s.slice(11, 16);
    },
    weekday: function (dateStr) {
      var d = parseDate(dateStr);
      return d ? WEEKDAYS[d.getDay()] : '';
    },
    isWeekendNight: function (dateStr) {
      var d = parseDate(dateStr);
      if (!d) return false;
      var dow = d.getDay();
      return dow === 5 || dow === 6; // 周五 / 周六晚（与后端定价策略一致）
    }
  };

  /* ---------- 订单状态元数据 ---------- */
  var STATUS = {
    PENDING_PAYMENT: { label: '待支付', tone: 'amber', icon: '⏳' },
    CONFIRMED:       { label: '已确认', tone: 'blue',  icon: '✓' },
    CHECKED_IN:      { label: '已入住', tone: 'green', icon: '🛏️' },
    CHECKED_OUT:     { label: '已退房', tone: 'gray',  icon: '🚪' },
    CANCELLED:       { label: '已取消', tone: 'red',   icon: '✕' }
  };

  var TONE_INK = {
    amber: 'var(--bdg-amber-ink)',
    blue: 'var(--bdg-blue-ink)',
    green: 'var(--bdg-green-ink)',
    gray: 'var(--bdg-gray-ink)',
    red: 'var(--bdg-red-ink)'
  };

  function statusOf(code) {
    return STATUS[code] || { label: code || '未知', tone: 'gray', icon: '•' };
  }

  /** 状态徽标：点 + 图标 + 文字（状态色永不单独承载语义） */
  function badge(status) {
    var meta = statusOf(status);
    var b = el('span', 'badge badge--' + meta.tone, meta.icon + ' ' + meta.label);
    b.title = meta.label;
    return b;
  }

  var OPERATOR_LABEL = { guest: '客人', frontdesk: '前台', system: '系统' };
  function operatorLabel(op) {
    return OPERATOR_LABEL[op] || op || '系统';
  }

  /* ---------- Toast ---------- */
  var toastRoot = document.getElementById('toast-root');
  var Toast = {
    show: function (message, kind, duration) {
      kind = kind || 'info';
      duration = duration || (kind === 'error' ? 6000 : 4200);
      var icons = { success: '✅', error: '⚠️', info: 'ℹ️' };
      while (toastRoot.children.length >= 4) {
        toastRoot.removeChild(toastRoot.firstChild);
      }
      var toast = el('div', 'toast toast--' + kind);
      toast.setAttribute('role', kind === 'error' ? 'alert' : 'status');
      var ico = el('span', 'toast-ico', icons[kind] || icons.info);
      var msg = el('div', 'toast-msg', String(message));
      var close = el('button', 'toast-close', '✕');
      close.type = 'button';
      close.setAttribute('aria-label', '关闭提示');
      toast.appendChild(ico);
      toast.appendChild(msg);
      toast.appendChild(close);
      var removed = false;
      var remove = function () {
        if (removed) return;
        removed = true;
        toast.classList.add('is-leaving');
        setTimeout(function () {
          if (toast.parentNode) toast.parentNode.removeChild(toast);
        }, 260);
      };
      close.addEventListener('click', remove);
      toastRoot.appendChild(toast);
      setTimeout(remove, duration);
    },
    success: function (m, d) { this.show(m, 'success', d); },
    error: function (m, d) { this.show(m, 'error', d); },
    info: function (m, d) { this.show(m, 'info', d); }
  };

  /* ---------- 确认弹窗（Promise<boolean>） ---------- */
  function confirmDialog(opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      var overlay = el('div', 'modal-overlay');
      var modal = el('div', 'modal');
      modal.setAttribute('role', 'alertdialog');
      modal.setAttribute('aria-modal', 'true');
      modal.setAttribute('aria-label', opts.title || '确认操作');
      var title = el('div', 'modal-title', opts.title || '确认操作');
      var body = el('div', 'modal-body', opts.message || '');
      var actions = el('div', 'modal-actions');
      var cancelBtn = el('button', 'btn btn-ghost', opts.cancelText || '取消');
      cancelBtn.type = 'button';
      var confirmBtn = el('button', 'btn ' + (opts.danger ? 'btn-danger-solid' : 'btn-primary'),
        opts.confirmText || '确认');
      confirmBtn.type = 'button';
      actions.appendChild(cancelBtn);
      actions.appendChild(confirmBtn);
      modal.appendChild(title);
      modal.appendChild(body);
      modal.appendChild(actions);
      overlay.appendChild(modal);
      document.body.appendChild(overlay);

      var done = false;
      var finish = function (v) {
        if (done) return;
        done = true;
        document.removeEventListener('keydown', onKey);
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        resolve(v);
      };
      var onKey = function (e) {
        if (e.key === 'Escape') finish(false);
      };
      cancelBtn.addEventListener('click', function () { finish(false); });
      confirmBtn.addEventListener('click', function () { finish(true); });
      overlay.addEventListener('click', function (e) {
        if (e.target === overlay) finish(false);
      });
      document.addEventListener('keydown', onKey);
      confirmBtn.focus();
    });
  }

  /* ---------- 剪贴板 ---------- */
  function copyText(text) {
    return Promise.resolve().then(function () {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        return navigator.clipboard.writeText(text).then(function () { return true; })
          .catch(function () { return fallbackCopy(text); });
      }
      return fallbackCopy(text);
    });
  }
  function fallbackCopy(text) {
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch (e) {
      return false;
    }
  }

  /* ---------- 按钮忙碌态 ---------- */
  function setBusy(btn, busy, busyText) {
    if (!btn) return;
    if (busy) {
      if (!btn.dataset.idleText) btn.dataset.idleText = btn.textContent;
      btn.disabled = true;
      btn.textContent = '';
      var sp = el('span', 'spinner');
      sp.setAttribute('aria-hidden', 'true');
      btn.appendChild(sp);
      btn.appendChild(document.createTextNode(' ' + (busyText || '处理中…')));
    } else {
      btn.disabled = false;
      btn.textContent = btn.dataset.idleText || btn.textContent;
      delete btn.dataset.idleText;
    }
  }

  window.UI = {
    el: el,
    clear: clear,
    fmt: fmt,
    parseDate: parseDate,
    STATUS: STATUS,
    statusOf: statusOf,
    badge: badge,
    toneInk: function (tone) { return TONE_INK[tone] || TONE_INK.gray; },
    operatorLabel: operatorLabel,
    Toast: Toast,
    confirmDialog: confirmDialog,
    copyText: copyText,
    setBusy: setBusy
  };
})();
