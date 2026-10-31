(() => {
  if (window.__homeworkReadFrames) return;
  const official = value => {
    try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password &&
      (!u.port || u.port === '443') && (u.hostname === 'chaoxing.com' || u.hostname.endsWith('.chaoxing.com')); }
    catch (_) { return false; }
  };
  const frameId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const extract = () => /*__HOMEWORK_EXTRACTOR__*/;
  const visible = node => {
    for (let e = node; e; e = e.parentElement) {
      const s = getComputedStyle(e);
      if (e.hidden || s.display === 'none' || s.visibility === 'hidden') return false;
    }
    return true;
  };
  const read = request => {
    if (!request || request.type !== 'HOMEWORK_READ' || typeof request.nonce !== 'string' || request.nonce.length > 80 || !official(location.href)) return;
    let page;
    const oldSingle = window.__homeworkSingleDocument, oldContext = window.__homeworkContext;
    try {
      window.__homeworkSingleDocument = true;
      window.__homeworkContext = request.course || null;
      page = JSON.parse(extract());
      window.HomeworkDomBridge.postMessage(JSON.stringify({nonce:request.nonce,round:request.round,frameId,url:location.href,page}));
    } catch (_) { /* Native timeout/fallback reports unreadable documents. */ }
    finally { window.__homeworkSingleDocument = oldSingle; window.__homeworkContext = oldContext; }
    const childRequest = {...request, course:page?.currentCourse || request.course || null};
    for (const frame of document.querySelectorAll('iframe, frame')) {
      if (!visible(frame)) continue;
      try {
        const raw = frame.getAttribute('src');
        const url = !raw || raw === 'about:blank' || frame.hasAttribute('srcdoc') ? location.href : new URL(raw, location.href).href;
        if (official(url)) frame.contentWindow?.postMessage(childRequest, new URL(url).origin);
      } catch (_) {}
    }
  };
  window.__homeworkReadFrames = read;
  addEventListener('message', event => {
    if (event.source === parent && official(event.origin)) read(event.data);
  });
})()
