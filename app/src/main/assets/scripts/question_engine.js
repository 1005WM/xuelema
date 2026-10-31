(() => {
  'use strict';
  if (window.__haQuestionEngine?.version === '1.4') return;
  const allowed = value => { try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') && (u.hostname === 'chaoxing.com' || u.hostname.endsWith('.chaoxing.com')); } catch { return false; } };
  if (!allowed(location.href)) return;
  const clean = value => String(value ?? '').replace(/\s+/g, ' ').trim();
  // Small host adapters for the upstream parser. No userscript storage/network APIs are installed.
  function questionId(el, index) { return String(el.getAttribute('data-question-id') || el.getAttribute('data-questionid') || el.getAttribute('questionid') || el.getAttribute('data') || el.id || `local-${index + 1}`); }
  function detectType(title) {
    if (/连线|匹配/.test(title)) return 'matching';
    if (/多选|多项选择/.test(title)) return 'multiple';
    if (/单选|单项选择/.test(title)) return 'single';
    if (/判断|是非/.test(title)) return 'judge';
    if (/填空/.test(title)) return 'blank';
    return 'unknown';
  }
  function optionKey(li, index, type) { return clean(li.querySelector('.num_option')?.getAttribute('data')) || (type === 'judge' ? ['true', 'false'][index] : String.fromCharCode(65 + index)); }
  function optionText(li, key) {
    const copy = li.cloneNode(true); copy.querySelectorAll('.num_option').forEach(node => node.remove());
    let text = clean(li.querySelector('a.after')?.textContent || li.getAttribute('aria-label') || copy.textContent);
    if (/^[A-Z]$/.test(key)) text = text.replace(new RegExp('^' + key + '\\s*[、.．:：)）]\\s*'), '');
    return text;
  }
  function selected(li) { return li.matches('input[type=radio],input[type=checkbox]') ? li.checked : li.getAttribute('aria-checked') === 'true' || li.getAttribute('aria-pressed') === 'true' || ['on','active','checked','selected'].some(c => li.classList.contains(c)) || !!li.querySelector('.num_option.check,.num_option.checked,.num_option.on,input:checked'); }
  function savedKeys(saved, optionMap) { if (optionMap.has(saved)) return [saved]; const keys = saved.split(/[,;|\s]+/).filter(Boolean); return keys.length === 1 && /^[A-Z]+$/.test(saved) ? [...saved] : keys; }
  async function waitFor(test, timeout = 450) { const end = Date.now() + timeout; await new Promise(r => setTimeout(r, 25)); while (!test() && Date.now() < end) await new Promise(r => setTimeout(r, 25)); }
  function courseElementVisible(node) { return questionNodeActive(node); }
  // SVG/canvas content needs a separate verified image adapter; never silently send a partial question.
  function captureVisualMediaSignature() { throw new Error('原生图形尚未适配，请对照官方页面'); }

  /*__UPSTREAM__*/
  /*__TEXT_ADAPTER__*/

  // Keep the existing generic HTML fixtures/pages as a final fallback after Chaoxing-specific adapters.
  registerPageAdapter({ id:'app-generic', collect:doc => [...doc.querySelectorAll('[data-question-id],.questionLi,.question-item,.question')].filter(node => node.querySelector(STEM_SELECTOR)),
    read:(node,index) => {
      const raw = rawQuestion(node,index,'app-generic');
      if (!raw.typeLabel) raw.typeLabel = /多选/.test(raw.question) ? '多选' : /单选/.test(raw.question) ? '单选' : /判断/.test(raw.question) ? '判断' : /填空/.test(raw.question) ? '填空' : /简答|问答|论述/.test(raw.question) ? '简答' : '';
      return raw;
    }
  });
  const frameId = window.crypto?.randomUUID?.() || `frame-${Date.now()}-${Math.random()}`;
  const typeMap = {single:'SINGLE',multiple:'MULTIPLE',judge:'BOOLEAN',blank:'FILL',essay:'SHORT',matching:'MATCHING',unknown:'UNKNOWN'};
  let active = null, stopped = false;
  const hash = text => { let h = 2166136261; for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619); return (h >>> 0).toString(16); };
  function extract() {
    active = null;
    const graph = parseQuestionGraph(document);
    const snapshot = createQuestionSnapshot(graph, {doc:document});
    const projection = buildQuestionRequestUnits(graph);
    const questions = projection.questions.map((q,index) => {
      const binding = projection.bindings.get(q.id), related = new Set([...(q.imageIds || []),...(q.contextImageIds || [])]);
      const media = graph.media.filter(item => related.has(item.id));
      const images = media.filter(item => !item.kind || item.kind === 'img').map(item => ({id:item.id,url:item.src,optionKey:item.optionKey || '',location:item.location,referer:location.href}));
      const shared = graph.materials.filter(item => (q.sharedMaterialIds || []).includes(item.id)).map(item => item.text).filter(Boolean);
      const hasSecretFont = [...binding.node.querySelectorAll('.font-cxsecret'),...(binding.node.matches('.font-cxsecret') ? [binding.node] : [])].some(node => clean(node.textContent));
      const unsupported = hasSecretFont || media.some(item => item.unreadable || item.kind && item.kind !== 'img') || q.recognition?.materials === 'unreadable' || !!binding.node.querySelector('audio,video,object,embed');
      const diagnostics = [...q.diagnostics, ...(hasSecretFont ? ['题目使用混淆字体，当前版本不猜测解码，请使用官方原文核对'] : []), ...(unsupported && !hasSecretFont ? ['含尚未完整读取的材料或图形'] : [])];
      return {id:q.id,number:index + 1,type:typeMap[q.type] || 'UNKNOWN',stem:[q.question,q.instructions,...shared].filter(Boolean).join('\n'),
        options:q.options.map(option => `${option.key}. ${option.text}`),optionKeys:q.options.map(option => option.key),images,
        hasUnsupportedMedia:unsupported,canAnalyze:q.capabilities.analyze && !unsupported,canFill:q.capabilities.prefill && !unsupported,
        diagnostics,sourceAdapter:q.source.adapter,sourceNumber:q.parentNumber || q.number,
        sourcePart:q.parentQuestionId ? clean(graph.questions.find(parent=>parent.id===q.parentQuestionId)?.responseUnits.find(unit=>unit.id===q.id)?.question).slice(0,80) : ''};
    });
    const url = location.href;
    const fingerprint = hash(JSON.stringify({url,content:questionGraphContentSignature(graph)}));
    active = {snapshot,projection,identity:textQuestionIdentity(graph),page:{url,fingerprint,questions,frameId,engine:'chaoxing-ai-assistant'}};
    return JSON.parse(JSON.stringify(active.page));
  }
  function answerValues(question, text) {
    const value = String(text ?? '').trim();
    if (!value) return [];
    if (question.type === 'blank') { try { const array = JSON.parse(value); if (Array.isArray(array)) return array.length === 1 && typeof array[0] === 'string' ? array : []; } catch {} }
    if (['blank','essay'].includes(question.type)) return [value];
    if (question.type === 'matching') { try { const a = JSON.parse(value); return Array.isArray(a) ? a.map(String) : []; } catch { return value.split(/[,，;；\s]+/).filter(Boolean); } }
    const keys = question.options.map(o => o.key);
    if (keys.includes(value)) return [value];
    // Accept presentation differences without guessing an option by position or extracting
    // arbitrary letters from an explanation. Native keys still take precedence.
    const normalize = text => clean(text).normalize('NFKC');
    const plain = text => normalize(text).replace(/^\*\*([^*]+)\*\*$/, '$1');
    const token = plain(value).replace(/^(?:答案|正确答案|选项)\s*[:：]\s*/,'');
    const resolve = text => {
      const input = plain(text);
      if (keys.includes(input)) return input;
      const matches = question.options.filter(option =>
        /^[A-Z]$/i.test(input) && /^[A-Z]$/i.test(option.key) && input.toUpperCase() === option.key.toUpperCase()
        || normalize(option.text) === input
        || normalize(`${option.key}. ${option.text}`) === input
        || new RegExp('^' + option.key.replace(/[.*+?^${}()|[\]\\]/g,'\\$&') + '\\s*[.、:)]\\s*','i').test(input)
          && normalize(input.replace(/^[^\s.、:)]+\s*[.、:)]\s*/,'')) === normalize(option.text));
      return matches.length === 1 ? matches[0].key : null;
    };
    const one = resolve(token);
    if (one !== null) return [one];
    try {
      const array = JSON.parse(value);
      if (Array.isArray(array)) {
        if (!array.length || !array.every(item => typeof item === 'string')) return [];
        const resolved = array.map(resolve);
        return resolved.every(key => key !== null) && new Set(resolved).size === resolved.length ? resolved : [];
      }
    } catch {}
    if (question.type === 'judge') {
      const yes = /^(正确|对|是|true|√)$/i.test(token), no = /^(错误|错|否|false|×)$/i.test(token);
      if (yes || no) {
        const matches = question.options.filter(o => (yes ? /^(true|正确|对|是|√)$/i : /^(false|错误|错|否|×)$/i).test(clean(o.key)) || (yes ? /^(正确|对|是|√)$/ : /^(错误|错|否|×)$/).test(clean(o.text)));
        return matches.length === 1 ? [matches[0].key] : [];
      }
    }
    const parts = token.split(/[,，;；、\s]+/).filter(Boolean).map(resolve);
    if (parts.length && parts.every(key => key !== null) && new Set(parts).size === parts.length) return parts;
    const letters = /^[A-Z]+$/i.test(token) ? [...token].map(resolve) : [];
    return letters.length && letters.every(key => key !== null) && new Set(letters).size === letters.length ? letters : [];
  }
  async function fill(payload) {
    if (!active || payload.page?.url !== location.href || payload.page.fingerprint !== active.page.fingerprint || payload.currentFingerprint !== active.page.fingerprint) return {filled:[],failed:['页面已变化，请重新提取题目'],already:[],unverified:[]};
    stopped = false;
    const current = active;
    const checkCurrent = () => { if (stopped || current !== active || location.href !== current.page.url) throw new Error('页面已变化或回填已停止'); };
    const requestedInterval = Number(payload.fillIntervalMillis);
    const interval = Number.isFinite(requestedInterval) ? Math.max(500, Math.min(5000, requestedInterval)) : 1500;
    let nextUnitAt = 0;
    const beforeUnit = async () => {
      checkCurrent();
      while (Date.now() < nextUnitAt) {
        await new Promise(resolve => setTimeout(resolve, Math.min(50, nextUnitAt - Date.now())));
        checkCurrent();
      }
    };
    const afterUnit = () => { nextUnitAt = Date.now() + interval; };
    refreshTextSnapshot(current);
    const answers = payload.answers.map(answer => {
      const q = current.projection.questions.find(q => q.id === answer.questionId), values = q ? answerValues(q,answer.answer) : [];
      return {id:answer.questionId,answer:values,reason:values.length ? '' : '答案格式无法与该题作答区域对应，请核对答案或选项键'};
    });
    const snapshot = {...current.snapshot,rootSnapshot:current.snapshot,questions:current.projection.questions};
    const editableAnswers = answers.filter(answer => current.projection.bindings.get(answer.id)?.appEditable);
    const normalAnswers = answers.filter(answer => !current.projection.bindings.get(answer.id)?.appEditable);
    const plan = planQuestionActions(snapshot, normalAnswers, {checkCurrent});
    let result, aborted = false;
    try { result = await executeQuestionActions(plan, {checkCurrent,beforeUnit,afterUnit}); }
    catch (error) { if (!error.prefillSummary) throw error; result = error.prefillSummary; result.details.push(error.message); aborted = true; }
    const editableResult = aborted || result.unverified ? {filled:0,already:0,skipped:editableAnswers.length,unverified:0,perUnit:[],details:editableAnswers.length ? ['前面的回填未能确认，后续文本答案未写入'] : []} : await fillAppEditable(current,editableAnswers,checkCurrent,beforeUnit,afterUnit);
    for (const key of ['filled','already','skipped','unverified']) result[key] += editableResult[key];
    result.details.push(...editableResult.details); result.perUnit.push(...editableResult.perUnit);
    const feedback = Object.fromEntries(answers.map(entry => {
      const outcome = result.perUnit.find(item => item.responseUnitId === entry.id);
      const status = outcome?.status;
      const q = current.projection.questions.find(item => item.id === entry.id);
      const reason = outcome?.reason || (status === 'unverified' ? '页面或保存字段未能确认，请人工检查' :
        status === 'skipped' ? q?.diagnostics.join('；') || '答案或控件无法可靠对应，请核对页面' : '前面的回填已停止，本题尚未写入');
      return [entry.id,status === 'filled' ? '已回填' : status === 'already' ? '已有相同答案' : `${status === 'unverified' ? '待检查' : '未回填'}：${reason}`];
    }));
    return {filled:result.perUnit.filter(x => x.status === 'filled').map(x => x.responseUnitId),already:result.perUnit.filter(x => x.status === 'already').map(x => x.responseUnitId),
      unverified:result.perUnit.filter(x => x.status === 'unverified').map(x => x.responseUnitId),failed:result.details,skipped:result.skipped,feedback};
  }
  function post(value) { window.QuestionDomBridge?.postMessage(JSON.stringify({...value,frameId,url:location.href})); }
  async function command(data) {
    if (data?.type !== 'HA_QUESTION_COMMAND' || typeof data.nonce !== 'string' || !allowed(location.href)) return;
    if (data.op === 'cancel') { stopped = true; return; }
    try {
      if (data.op === 'read') post({nonce:data.nonce,page:extract()});
      else if (data.op === 'fill') post({nonce:data.nonce,result:await fill(data.payload)});
    } catch (error) { post({nonce:data.nonce,error:String(error.message || '题目读取失败')}); }
  }
  function dispatch(data) {
    command(data);
    for (const frame of document.querySelectorAll('iframe')) {
      // Editor frames are handled by their owning question, never as independent question pages.
      if (frame.closest('.edui-editor') || /ueditor/i.test(frame.id)) continue;
      try { const target = new URL(frame.getAttribute('src') || '',location.href); if (allowed(target.href)) frame.contentWindow.postMessage(data,target.origin); } catch {}
    }
  }
  window.addEventListener('message', event => { if (event.source === window.parent && allowed(event.origin) && event.data?.op === 'read') dispatch(event.data); });
  if (window.QuestionDomBridge) window.QuestionDomBridge.onmessage = event => { try { command(JSON.parse(event.data)); } catch {} };
  window.__haQuestionEngine = {version:'1.4',extract,fill,dispatch,cancel:() => { stopped = true; }};
})();
