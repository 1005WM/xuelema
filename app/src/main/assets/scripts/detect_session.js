(() => {
  const host = location.hostname.toLowerCase();
  if (!(host === 'chaoxing.com' || host.endsWith('.chaoxing.com')) || location.protocol !== 'https:')
    return JSON.stringify({verified:false, uid:null, loginForm:false, hasPersonalContent:false, ready:false});
  const docs = [document];
  // Only same-origin frames can be inspected. Cross-origin authentication remains in the browser.
  for (let i = 0; i < docs.length && i < 8; i++) {
    for (const frame of docs[i].querySelectorAll('iframe')) {
      try { if (visible(frame) && frame.contentDocument?.body && !docs.includes(frame.contentDocument)) docs.push(frame.contentDocument); } catch (_) {}
    }
  }
  function visible(e) {
    for (let n = e; n && n.nodeType === 1; n = n.parentElement) {
      const style = n.ownerDocument.defaultView.getComputedStyle(n);
      if (n.hidden || style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || style.opacity === '0') return false;
    }
    return true;
  }
  const expired = docs.some(d => /请先登录|登录状态失效|登录已过期|登录已失效/.test((d.body?.innerText || d.body?.textContent || '').slice(0,2000)));
  const loginForm = expired || docs.some(d => [...d.querySelectorAll('input[type="password"]')].some(visible));
  const protectedMarker = docs.some(d => [...d.querySelectorAll('a,button')].some(e =>
    /^(退出登录|安全退出|退出)$/.test((e.innerText || e.textContent || '').trim()) ||
    /logout/i.test((e.getAttribute('href') || '') + (e.getAttribute('onclick') || ''))));
  let uid = null;
  let personal = false;
  for (const d of docs) {
    uid = uid || d.querySelector('input[name="uid"], input[name="_uid"]')?.value || d.querySelector('[data-uid]')?.getAttribute('data-uid');
    for (const a of d.querySelectorAll('a[href]')) {
      try {
        const u = new URL(a.getAttribute('href'), location.href);
        if (u.hostname === 'chaoxing.com' || u.hostname.endsWith('.chaoxing.com')) {
          uid = uid || u.searchParams.get('uid') || u.searchParams.get('userid');
          if (/我的课程|个人空间|我的作业|账户设置/.test(a.textContent || '') || /\/base|\/course|\/space/.test(u.pathname)) personal = true;
        }
      } catch (_) {}
    }
    if (d.querySelector('.course, .course-list, .courseList, #courseList, [data-courseid], #space_nickname, .user-name, .userName, #userName')) personal = true;
    for (const frame of d.querySelectorAll('iframe[src]')) {
      try {
        const u = new URL(frame.getAttribute('src'), location.href);
        if ((u.hostname === 'chaoxing.com' || u.hostname.endsWith('.chaoxing.com')) && /\/course|\/base|\/space|\/visit/.test(u.pathname)) personal = true;
      } catch (_) {}
    }
  }
  const loginUrl = host.startsWith('passport') || /\/login/i.test(location.pathname);
  return JSON.stringify({verified:!loginUrl && !loginForm && protectedMarker, uid:uid || null,
    loginForm:loginForm || loginUrl, hasPersonalContent:!loginUrl && personal,
    ready:document.readyState !== 'loading' && !!document.body,
    hasPageContent:!!document.body && ((document.body.innerText || document.body.textContent || '').trim().length > 0 ||
      !!document.body.querySelector('iframe[src], input, img, canvas'))});
})()
