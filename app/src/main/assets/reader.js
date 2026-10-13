/*
 * Paginates one EPUB/TXT chapter inside the WebView.
 *
 * The chapter is laid out in CSS columns exactly one screen wide, and the document is scrolled
 * horizontally a screen at a time. Taps and swipes are interpreted here and reported to the app
 * through the `Android` bridge (see WebBookController).
 *
 * window.__readerInit = { settings: {...}, target: {type: 'start'|'end'|'fraction'|'anchor', value} }
 */
(function () {
  'use strict';
  if (window.reader) return;

  var init = window.__readerInit || {};
  var S = init.settings || {};
  var target = init.target || { type: 'start' };
  var noop = function () {};
  var bridge = window.Android || {};
  ['onPage', 'onTap', 'onBoundary', 'onLink', 'onSelection'].forEach(function (name) {
    if (typeof bridge[name] !== 'function') bridge[name] = noop;
  });

  var root = document.documentElement;
  var page = 0;
  var pageCount = 1;
  var userMoved = false;
  var ready = false;

  // One screen-wide layout viewport at scale 1 (normally added by the app already).
  var viewport = document.createElement('meta');
  viewport.name = 'viewport';
  viewport.content = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no';
  (document.head || root).appendChild(viewport);

  root.classList.add('__rd_loading');
  var style = document.createElement('style');
  (document.head || document.getElementsByTagName('head')[0] || root).appendChild(style);

  // The app passes the real page size (CSS px = dp). Trust it over innerWidth, which some
  // WebViews report wrong (e.g. 980) while the view is still being laid out.
  function viewW() { var v = num(S.width, 0); return v > 0 ? Math.floor(v) : (window.innerWidth || root.clientWidth); }
  function viewH() { var v = num(S.height, 0); return v > 0 ? Math.floor(v) : (window.innerHeight || root.clientHeight); }
  function scroller() { return document.scrollingElement || root; }
  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }

  function buildCss() {
    var w = viewW();
    var h = viewH();
    var p = S.padding || {};
    var t = num(p.t, 44), r = num(p.r, 26), b = num(p.b, 44), l = num(p.l, 26);
    var cols = S.twoPage && w > h && w >= 700 ? 2 : 1;
    // The gap between columns equals left + right margin, so every screen starts at a
    // multiple of the viewport width with the same margins.
    var gap = l + r;
    var colH = Math.max(100, h - t - b);
    var bg = S.bg || '#ffffff';
    var fg = S.fg || '#1d1d1f';
    var link = S.link || fg;
    var css = [];
    css.push('html{margin:0!important;padding:0!important;width:' + w + 'px!important;height:' + h + 'px!important;' +
      'max-width:none!important;max-height:none!important;overflow:hidden!important;background:' + bg + '!important;' +
      '-webkit-text-size-adjust:none!important;text-size-adjust:none!important;font-size:' + num(S.fontScale, 100) + '%!important;}');
    css.push('body{margin:0!important;box-sizing:border-box!important;width:' + w + 'px!important;height:' + h + 'px!important;' +
      'max-width:none!important;max-height:none!important;min-height:0!important;' +
      'padding:' + t + 'px ' + r + 'px ' + b + 'px ' + l + 'px!important;' +
      '-webkit-column-count:' + cols + '!important;column-count:' + cols + '!important;' +
      '-webkit-column-gap:' + gap + 'px!important;column-gap:' + gap + 'px!important;' +
      'column-fill:auto!important;overflow:visible!important;background:transparent!important;' +
      'color:' + fg + '!important;position:static!important;transform:none!important;}');
    css.push('body *{color:' + fg + '!important;background-color:transparent!important;}');
    css.push('a,a *{color:' + link + '!important;}');
    css.push('img,svg,video,canvas,object{max-width:100%!important;max-height:' + colH + 'px!important;' +
      'object-fit:contain;box-sizing:border-box;}');
    css.push('img,svg,figure{-webkit-column-break-inside:avoid;break-inside:avoid;}');
    if (S.font) {
      css.push('body,body *:not(pre):not(code):not(kbd):not(samp){font-family:' + S.font + '!important;}');
    }
    if (S.lineHeight) {
      css.push('body,body p,body li,body div,body span,body blockquote,body dd,body dt,body td{line-height:' +
        S.lineHeight + '!important;}');
    }
    if (S.justify) css.push('html body p{text-align:justify;-webkit-hyphens:auto;hyphens:auto;}');
    css.push('html.__rd_loading body{opacity:0!important;}');
    css.push('::selection{background:' + (S.selection || 'rgba(255,204,0,0.4)') + ';}');
    css.push('*{-webkit-tap-highlight-color:transparent;}');
    return css.join('\n');
  }

  // The last column ends before the right margin, so the document is not quite as wide as
  // its pages and the last page could not be scrolled fully into view. This marker sits at the
  // right edge of the last page to make room for it.
  var spacer = document.createElement('div');
  spacer.setAttribute('aria-hidden', 'true');
  spacer.style.cssText = 'position:absolute;top:0;width:1px;height:1px;margin:0;padding:0;pointer-events:none;';

  function measure() {
    var w = viewW();
    if (spacer.parentNode) spacer.parentNode.removeChild(spacer);
    pageCount = Math.max(1, Math.ceil((scroller().scrollWidth - 4) / w));
    spacer.style.left = (pageCount * w - 1) + 'px';
    root.appendChild(spacer);
    if (page > pageCount - 1) page = pageCount - 1;
  }

  function show(p, smooth) {
    page = Math.max(0, Math.min(pageCount - 1, p));
    var left = page * viewW();
    var s = scroller();
    if (smooth && S.animate !== false && typeof s.scrollTo === 'function') {
      try { s.scrollTo({ left: left, top: 0, behavior: 'smooth' }); } catch (e) { s.scrollLeft = left; }
    } else {
      s.scrollLeft = left;
      s.scrollTop = 0;
    }
    if (ready) bridge.onPage(page, pageCount);
  }

  function anchorPage(id) {
    if (!id) return 0;
    var el = document.getElementById(id);
    if (!el) {
      var named = document.getElementsByName(id);
      el = named && named.length ? named[0] : null;
    }
    if (!el) return 0;
    var rect = el.getBoundingClientRect();
    return Math.max(0, Math.floor((rect.left + scroller().scrollLeft + 1) / viewW()));
  }

  function targetPage() {
    switch (target.type) {
      case 'end': return pageCount - 1;
      case 'fraction': return Math.floor(num(target.value, 0) * pageCount + 1e-4);
      case 'anchor': return anchorPage(target.value);
      default: return 0;
    }
  }

  // Size or settings changed: keep the reader at the same relative position.
  function layout() {
    var fraction = pageCount > 0 ? page / pageCount : 0;
    style.textContent = buildCss();
    measure();
    show(userMoved ? Math.floor(fraction * pageCount + 1e-4) : targetPage(), false);
  }

  // Content changed (images or fonts finished loading): re-count pages.
  function refresh() {
    measure();
    show(userMoved ? page : targetPage(), false);
  }

  function start() {
    if (ready) return;
    style.textContent = buildCss();
    measure();
    ready = true;
    show(targetPage(), false);
    // Reveal on the next frame so the jump to the saved page is never visible.
    requestAnimationFrame(function () { root.classList.remove('__rd_loading'); });
  }

  style.textContent = buildCss();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
  window.addEventListener('load', function () {
    if (!ready) start();
    refresh();
  });
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () { if (ready) refresh(); });
  }
  var resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (ready) layout(); }, 60);
  });

  var api = window.reader = {
    next: function () {
      userMoved = true;
      if (page < pageCount - 1) {
        show(page + 1, true);
      } else {
        show(page, true);
        bridge.onBoundary('next');
      }
    },
    prev: function () {
      userMoved = true;
      if (page > 0) {
        show(page - 1, true);
      } else {
        show(page, true);
        bridge.onBoundary('prev');
      }
    },
    goToFraction: function (f) {
      userMoved = true;
      measure();
      show(Math.floor(num(f, 0) * pageCount + 1e-4), false);
    },
    goToAnchor: function (id) {
      userMoved = true;
      show(anchorPage(id), false);
    },
    // Instant moves used by the native page-curl animation.
    step: function (delta) {
      userMoved = true;
      var target = page + delta;
      if (target < 0 || target > pageCount - 1) return 'boundary';
      show(target, false);
      return 'ok';
    },
    goTo: function (p) {
      userMoved = true;
      show(p, false);
    },
    applySettings: function (s) {
      if (s) S = s;
      if (ready) layout(); else style.textContent = buildCss();
    }
  };

  // ---- Touch: swipe to turn pages (the page follows the finger), tap zones -------------

  function closestLink(el) {
    while (el && el !== root) {
      if (el.nodeType === 1 && el.localName === 'a' &&
          (el.getAttribute('href') || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href'))) {
        return el;
      }
      el = el.parentNode;
    }
    return null;
  }

  function handleTap(x, el) {
    if (closestLink(el)) return; // the click handler below deals with links
    var sel = window.getSelection ? window.getSelection() : null;
    if (sel && !sel.isCollapsed) {
      sel.removeAllRanges();
      return;
    }
    var w = viewW();
    bridge.onTap(x < w * 0.3 ? 'left' : x > w * 0.7 ? 'right' : 'center');
  }

  var touch = null;
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { touch = null; return; }
    var t = e.touches[0];
    touch = { x: t.clientX, y: t.clientY, time: Date.now(), scroll: scroller().scrollLeft, axis: null };
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    if (!touch || e.touches.length !== 1) return;
    var t = e.touches[0];
    var dx = t.clientX - touch.x;
    var dy = t.clientY - touch.y;
    if (!touch.axis && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
      touch.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
    }
    // With the page-curl animation the app handles horizontal drags itself.
    if (touch.axis === 'x' && S.swipe !== false) {
      if (e.cancelable) e.preventDefault();
      scroller().scrollLeft = touch.scroll - dx;
    }
  }, { passive: false });

  document.addEventListener('touchend', function (e) {
    if (!touch) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - touch.x;
    var dt = Date.now() - touch.time;
    var axis = touch.axis;
    touch = null;
    if (axis === 'x' && S.swipe !== false) {
      var w = viewW();
      if (dx < -w * 0.12 || (dx < -30 && dt < 250)) api.next();
      else if (dx > w * 0.12 || (dx > 30 && dt < 250)) api.prev();
      else show(page, true);
    } else if (!axis && dt < 500) {
      handleTap(t.clientX, e.target);
    }
  }, { passive: true });

  document.addEventListener('touchcancel', function () {
    if (touch && touch.axis === 'x' && S.swipe !== false) show(page, true);
    touch = null;
  }, { passive: true });

  var selecting = false;
  document.addEventListener('selectionchange', function () {
    var sel = window.getSelection ? window.getSelection() : null;
    var now = !!(sel && !sel.isCollapsed);
    if (now !== selecting) {
      selecting = now;
      bridge.onSelection(now);
    }
  });

  // ---- Links: in-chapter anchors are handled here, everything else goes to the app ------

  document.addEventListener('click', function (e) {
    var a = closestLink(e.target);
    if (!a) return;
    e.preventDefault();
    var href = a.getAttribute('href') || a.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
    var url;
    try { url = new URL(href, document.baseURI); } catch (err) { return; }
    if (url.origin === location.origin && url.pathname === location.pathname) {
      if (url.hash) {
        userMoved = true;
        show(anchorPage(decodeURIComponent(url.hash.slice(1))), false);
      }
      return;
    }
    bridge.onLink(url.href);
  }, true);
})();
