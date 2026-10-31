(() => {
  const clean = e => (e?.innerText || e?.textContent || '').replace(/\s+/g, ' ').trim();
  const official = (value, base = location.href) => {
    if (!value) return null;
    try {
      // Decode an encoded *whole address* once. Normal URLs (including enc/tickets)
      // must keep their existing parameter escaping.
      if (/^(?:https?%3a|%2f)/i.test(value)) value = decodeURIComponent(value);
      const u = new URL(value.replace(/&amp;/g, '&'), base);
      if (!(u.hostname === 'chaoxing.com' || u.hostname.endsWith('.chaoxing.com')) || u.username || u.password) return null;
      if (u.protocol === 'http:' && (!u.port || u.port === '80')) { u.protocol = 'https:'; u.port = ''; }
      if (u.protocol !== 'https:' || (u.port && u.port !== '443')) return null;
      u.hash = ''; return u.href;
    } catch (_) { return null; }
  };
  const visible = e => {
    for (let p = e; p && p.nodeType === 1; p = p.parentElement) {
      const s = p.ownerDocument.defaultView.getComputedStyle(p);
      if (p.hidden || s.display === 'none' || s.visibility === 'hidden') return false;
    }
    return true;
  };
  const param = (url, names) => {
    try { const u = new URL(url); for (const [k,v] of u.searchParams) if (names.includes(k.toLowerCase()) && v) return v; } catch (_) {}
    return '';
  };
  const workPath = url => /\/(?:work\/|workHandle\/handle(?:[/?]|$))/i.test(new URL(url).pathname);
  const workIdentity = url => param(url, ['workid']) ||
    (workPath(url) ? param(url, ['taskrefid']) : '');
  const workDetail = url => /\/work\/(do|view|select|review|look|read|task)|\/workHandle\/handle(?:[/?]|$)/i.test(new URL(url).pathname) || !!param(url, ['workid']);
  const submissionStatus = text => {
    if (/打回|退回|重新提交/.test(text)) return 'RETURNED';
    if (/未交|未提交|尚未提交|待提交|待完成|未完成|未做|待做|待交|未作答|待作答|未开始/.test(text)) return 'NOT_SUBMITTED';
    if (/已批阅|已批改/.test(text)) return 'GRADED';
    if (/已提交|已交|待批阅|待批改|已完成|已做/.test(text)) return 'SUBMITTED';
    return 'UNKNOWN';
  };
  const field = (d, names) => [...d.querySelectorAll('input')].find(e => names.includes((e.name || e.id || '').toLowerCase()))?.value || '';
  const fallback = window.__homeworkContext || {};
  const getCourse = (d, url, context = fallback) => {
    const id = param(url, ['courseid']) || field(d, ['courseid']) || d.body?.getAttribute('data-courseid');
    const prior = (context.id || '').split(':');
    const clazz = param(url, ['clazzid','classid']) || field(d, ['clazzid','classid']) || (prior[0] === id ? prior[1] : '');
    const key = id ? `${id}:${clazz || ''}` : context.id;
    if (!key) return null;
    const name = clean(d.querySelector('#coursename, #courseName, .course-name, .courseName, .course-title')) ||
      (key === context.id ? context.name : '') || '未命名课程';
    return {id:key, name, url:official(url) || url};
  };
  const documents = [{doc:document, url:location.href}];
  for (let index = 0; index < documents.length && index < 12 && !window.__homeworkSingleDocument; index++) {
    for (const frame of documents[index].doc.querySelectorAll('iframe')) {
      try {
        if (visible(frame) && frame.contentDocument?.body && !documents.some(x => x.doc === frame.contentDocument))
          documents.push({doc:frame.contentDocument, url:official(frame.getAttribute('src'), documents[index].url) || documents[index].url});
      } catch (_) {}
    }
  }
  const courses = new Map(), items = new Map(), frames = new Set(), navigation = new Set(), next = new Set();
  let currentCourse = getCourse(document, location.href);
  let courseListComplete = false, workListComplete = false, empty = false, loginRequired = false;
  let courseList = false, workList = false, anchorCount = 0, candidates = 0, missingCourseId = 0, missingWorkId = 0, unsupportedNavigation = 0, unresolvedCourses = 0, attributeLinks = 0, referenceLinks = 0, pendingLabels = 0;
  for (const {doc:d,url:base} of documents) {
    const bodyText = clean(d.body);
    pendingLabels += [...d.querySelectorAll('span, .status, .work-status, .workStatus')]
      .filter(e => visible(e) && !e.closest('ul.ks_list, .exam-list') && /^(未交|未提交|待提交|待完成|未完成|未做|待做|待交|退回|打回)$/.test(clean(e))).length;
    if ([...d.querySelectorAll('input[type="password"]')].some(visible) ||
        (bodyText.length < 800 && /请先登录|登录已过期|登录状态失效/.test(bodyText))) loginRequired = true;
    const context = getCourse(d, base, currentCourse || fallback);
    if (!currentCourse && context) currentCourse = context;
    const links = [];
    for (const e of d.querySelectorAll('a[href], [data-url], [data-href], [dataurl], [data], [onclick]')) {
      const rawLinks = ['href','data-url','data-href','dataurl','data'].map(attr => ({attr, raw:e.getAttribute(attr)}));
      for (const literal of (e.getAttribute('onclick') || '').match(/['"]([^'"]+)['"]/g) || [])
        rawLinks.push({attr:'onclick', raw:literal.slice(1,-1)});
      const seen = new Set();
      for (const {attr, raw} of rawLinks) {
        if (!raw || /^(?:javascript:|#)/i.test(raw.trim())) continue;
        // A generic data attribute can also contain JSON or an ID: only accept
        // address-shaped values; never evaluate the page's onclick handler.
        if (attr !== 'href' && !/^(?:https?:\/\/|\/|\.\.?\/|\?|https?%3a|%2f)/i.test(raw.trim())) continue;
        const url = official(raw.trim(), base);
        if (!url || seen.has(url)) continue;
        seen.add(url);
        if (attr !== 'href' && attr !== 'onclick') attributeLinks++;
        links.push({url, title:clean(e) || e.getAttribute('title') || '', element:e});
      }
    }
    anchorCount += links.length;
    referenceLinks += links.filter(x => workPath(x.url) && param(x.url, ['taskrefid'])).length;
    const resolvedElements = new Set(links.map(x => x.element));
    for (const node of d.querySelectorAll('a, button, [onclick]')) {
      const label = clean(node).replace(/\s+/g,'').replace(/[（(]\d+[)）]/g,'');
      if (visible(node) && /^(我的课程|全部课程|课程作业|作业|未完成|已完成|下一页|下页)$/.test(label) &&
          !resolvedElements.has(node) && !node.matches('[disabled], [aria-disabled="true"], [aria-selected="true"], .disabled, .active, .cur')) unsupportedNavigation++;
    }
    for (const card of d.querySelectorAll('li.course, .course-item, .courseItem')) {
      if (visible(card) && !links.some(x => card.contains(x.element) && param(x.url,['courseid']))) unresolvedCourses++;
    }
    for (const frame of d.querySelectorAll('iframe[src]')) {
      const url = official(frame.getAttribute('src'), base);
      if (url && visible(frame)) frames.add(url);
    }
    for (const link of links) {
      const courseId = param(link.url, ['courseid']);
      const clazzId = param(link.url, ['clazzid','classid']);
      const card = link.element.closest('.course, .courseItem, .course-item, .courseList, .course-list, [data-courseid]');
      if (courseId && !workPath(link.url) && (card || /course|visit\/interaction/i.test(new URL(link.url).pathname))) {
        const id = `${courseId}:${clazzId}`;
        if (!courses.has(id)) courses.set(id, {id,
          name:clean((link.element.closest('li') || card)?.querySelector('h3, .course-name, .courseName, .course-name a')) || link.element.getAttribute('title') || link.title || '未命名课程',url:link.url});
      }
      const label = link.title.replace(/\s+/g,'').replace(/[（(]\d+[)）]/g,'');
      if (!workDetail(link.url) && (/^(课程|我的课程|全部课程|作业|全部作业|课程作业|未完成|已完成)$/.test(label) ||
          /\/visit\/courses|\/course\/list|\/work\/(list|student|stu-work|stuwork)|workList/i.test(new URL(link.url).pathname))) navigation.add(link.url);
      const disabled = link.element.matches('[disabled], [aria-disabled="true"], .disabled') || link.element.closest('.disabled, [aria-disabled="true"]');
      if (!disabled && (/^(下一页|下页|next|›|»|>)$/i.test(label) || link.element.rel === 'next')) next.add(link.url);
    }
    const rowMap = new Map();
    for (const link of links.filter(x => workDetail(x.url))) {
      let row = link.element.closest('.work-item, .workItem, .work-list-item, .workLi, .task-item, [role="listitem"], .ulDiv, li, tr');
      if (!row) {
        row = link.element.parentElement;
        for (let n = 0; n < 3 && row?.parentElement && row.parentElement !== d.body; n++) {
          const parent = row.parentElement;
          if (parent.matches('#workList, .work-list, .workList, main, form') || clean(parent).length > 1500) break;
          const ids = new Set([...parent.querySelectorAll('a[href]')].map(a => official(a.getAttribute('href'),base)).filter(Boolean).map(u => param(u,['workid'])).filter(Boolean));
          if (ids.size > 1) break;
          row = parent;
        }
      }
      if (row && !rowMap.has(row)) rowMap.set(row, link);
    }
    for (const row of d.querySelectorAll('[data-workid], [data-work-id], li[workid], li[id^="work_"], li[onclick*="goTask"], .work-item, .workItem, .work-list-item, .workLi, .work-list li, #workList li, .workList li'))
      if (!rowMap.has(row)) rowMap.set(row, null);
    for (const [row,link] of rowMap) {
      if (!visible(row)) continue;
      candidates++;
      const url = link?.url || official(base);
      const wid = (url ? workIdentity(url) || (/\/work\//i.test(new URL(url).pathname) ? param(url, ['id']) : '') : '') ||
        row.getAttribute('data-workid') || row.getAttribute('data-work-id') || row.getAttribute('workid') ||
        row.querySelector('input[name="workId"], input[name="workid"]')?.value || row.id.match(/^work_(\d+)$/)?.[1];
      if (!wid || !url) { missingWorkId++; continue; }
      const linkCourseId = param(url, ['courseid']);
      const clazz = param(url,['clazzid','classid']) || (context?.id?.split(':')[0] === linkCourseId ? context.id.split(':')[1] : '') || '';
      const option = row.querySelector('div[role="option"]');
      const optionSpans = option ? [...option.children].filter(e => e.tagName === 'SPAN') : [];
      const courseName = clean(optionSpans[1]);
      const course = linkCourseId ? {id:`${linkCourseId}:${clazz}`,name:courseName ||
        (context?.id?.split(':')[0] === linkCourseId ? context.name : '') || '未命名课程'} : context;
      if (!course?.id) { missingCourseId++; continue; }
      const text = clean(row);
      const title = option?.querySelector('p') || row.querySelector('h3, .workName, .work-name, .work-title, .tit, p.overHidden2');
      const statusNode = optionSpans[0] || row.querySelector('.status, .work-status, .workStatus, .statusLabel, .workBtn');
      const structuredStatus = [clean(statusNode), statusNode?.getAttribute('title'), statusNode?.getAttribute('aria-label'),
        row.getAttribute('data-status-name'), ...[...row.querySelectorAll('img[alt]')].map(e => e.alt)]
        .filter(Boolean).join(' ').trim();
      let status = submissionStatus(structuredStatus);
      if (status === 'UNKNOWN') {
        const body = row.cloneNode(true);
        body.querySelectorAll('h3, .workName, .work-name, .work-title, .tit, p.overHidden2, div[role="option"] > p, script, style').forEach(e => e.remove());
        status = submissionStatus(clean(body));
      }
      if (status === 'UNKNOWN' && option && statusNode?.classList.contains('status') && workPath(url) && param(url, ['taskrefid'])) status = 'NOT_SUBMITTED';
      const date = label => text.match(new RegExp('(?:'+label+')[：:\\s]*([0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}[日]?\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)'))?.[1] || null;
      const scoreMatch = text.match(/(?:得分|成绩)[：:\s]*([0-9]+(?:\.[0-9]+)?)/) || clean(row.querySelector('.score, .workScore, .work-score')).match(/^([0-9]+(?:\.[0-9]+)?)\s*(?:分|$)/);
      if (scoreMatch && status !== 'NOT_SUBMITTED' && status !== 'RETURNED') status = 'GRADED';
      const id = `${course.id}:${wid}`;
      if (!items.has(id)) items.set(id,{id,courseId:course.id,courseName:course.name,
        title:title?.getAttribute('title') || clean(title) || (link && !/^(查看|去完成|作答|进入)$/.test(link.title) ? link.title : '') || text.split(/截止|未交|未提交|待完成|已提交/)[0].trim().slice(0,120) || '未命名作业',
        detailUrl:url,publishedAt:date('发布时间|发布'),dueAt:date('截止时间|截止日期|截止'),submittedAt:date('提交时间'),
        status,score:scoreMatch ? Number(scoreMatch[1]) : null,rawStatus:text.slice(0,500)});
    }
    const courseContainer = !!d.querySelector('.course-list, .courseList, #courseList, .course-list-container');
    const workContainer = !!d.querySelector('.work-list, .workList, .work-list-container, #workList, .work-item, .workItem, .work-list-item, .workLi');
    const courseEmpty = /暂无课程|没有课程/.test(bodyText) && (courseContainer || bodyText.length < 120);
    const workEmpty = /暂无作业|没有作业|暂无相关作业/.test(bodyText) && (workContainer || bodyText.length < 120);
    empty = empty || courseEmpty || workEmpty;
    courseList = courseList || courseContainer || courseEmpty;
    workList = workList || workContainer || rowMap.size > 0 || workEmpty;
    const end = /没有更多|已加载全部|全部加载完成/.test(bodyText) || !!d.querySelector('[data-homework-complete="true"]');
    const disabledNext = !!d.querySelector('.pagination .next.disabled, .pagination [rel="next"][aria-disabled="true"], .page .next.disabled');
    if (courseContainer || courseEmpty) courseListComplete = courseListComplete || courseEmpty || end || disabledNext;
    if (workContainer || workEmpty || rowMap.size) workListComplete = workListComplete || workEmpty || end || disabledNext;
  }
  const kind = workList ? 'WORK_LIST' : courseList ? 'COURSE_LIST' : courses.size ? 'COURSE_LINKS' : frames.size || navigation.size ? 'PORTAL' : 'UNKNOWN';
  return JSON.stringify({version:'chaoxing-dom-v4-unverified',recognized:loginRequired || empty || items.size > 0 || courses.size > 0 || frames.size > 0 || navigation.size > 0,
    loginRequired,empty,explicitEnd:courseListComplete || workListComplete,courseListComplete,workListComplete,
    currentCourse,courses:[...courses.values()],items:[...items.values()],frames:[...frames],navigation:[...navigation],next:[...next],
    diagnostics:{host:location.hostname,kind,anchors:anchorCount,frames:frames.size,workCandidates:candidates,missingCourseId,missingWorkId,unsupportedNavigation,unresolvedCourses,attributeLinks,referenceLinks,pendingLabels,width:innerWidth,height:innerHeight}});
})()
