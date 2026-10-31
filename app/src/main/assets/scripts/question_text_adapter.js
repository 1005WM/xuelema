// Android text-control adaptations. Upstream modules remain pinned and unchanged.
const originalReadUEditor = readQuestionUEditor;
function readAppUEditor(node, id, targetInput = null) {
  const original = targetInput ? null : originalReadUEditor(node, id);
  if (original?.valid) return original;
  const doc = node.ownerDocument, view = doc.defaultView, registry = view?.UE?.instants;
  if (!registry || typeof registry !== 'object') return original;
  const owner = element => element?.closest?.('.singleQuesId,.TiMu,[data-question-id],[data-questionid],.questionLi,.question-item,.question');
  const fields = [...node.querySelectorAll('textarea')].filter(input => owner(input) === node && (!targetInput || input === targetInput));
  const matches = [];
  for (const editor of Object.values(registry)) {
    if (!editor || ![true,1].includes(editor.isReady)) continue;
    const input = editor.textarea?.nodeType === 1 ? editor.textarea : typeof editor.key === 'string' ? doc.getElementById(editor.key) : null;
    if (!input || !fields.includes(input) || !input.id || [...doc.querySelectorAll('[id]')].filter(el => el.id === input.id).length !== 1) continue;
    if (input.disabled || input.readOnly || input.matches(':disabled') || input.getAttribute('aria-readonly') === 'true') continue;
    if (editor.key && editor.key !== input.id) continue;
    const frame = editor.iframe;
    if (!frame?.isConnected || frame.tagName !== 'IFRAME' || !node.contains(frame) || owner(frame) !== node) continue;
    const container = editor.container, inner = frame.contentDocument, body = inner?.body, form = input.form;
    if (!container?.contains?.(frame) || !node.contains(container) || !inner || !body || !body.isConnected || inner.defaultView?.frameElement !== frame) continue;
    if (editor.document && editor.document !== inner || editor.body && editor.body !== body || editor.form && editor.form !== form || form !== node.closest('form')) continue;
    if (body.contentEditable !== 'true' && body.getAttribute('contenteditable') !== 'true') continue;
    if (editor.options?.readonly || !courseElementVisible(frame) || frame.closest('[aria-disabled="true"],[aria-readonly="true"]')) continue;
    if (['setContent','getContent','sync'].some(name => typeof editor[name] !== 'function')) continue;
    if (Object.values(registry).filter(other => other === editor || other?.iframe === frame || other?.textarea === input).length !== 1) continue;
    matches.push({valid:true,diagnostics:[],id:String(id),mode:'app-ueditor',nativeQuestionId:String(id),registry,editor,input,form,iframe:frame,document:inner,body,container,
      key:input.id,name:input.name,setContent:editor.setContent,getContent:editor.getContent,sync:editor.sync});
  }
  return matches.length === 1 && fields.length === 1 ? matches[0] : original || (fields.length && node.querySelector('.edui-editor') ?
    {valid:false,diagnostics:['富文本编辑器尚未就绪或无法与答案字段唯一关联']} : null);
}
readQuestionUEditor = (node, id) => readAppUEditor(node, id);

function readAppEditable(node) {
  if (node.querySelector('input[type="radio"],input[type="checkbox"],input[type="file"],select')) return null;
  const own = element => element.closest('.singleQuesId,.TiMu,[data-question-id],[data-questionid],.questionLi,.question-item,.question') === node;
  const candidates = [...node.querySelectorAll('[contenteditable="true"]')].filter(own).filter(el => !el.parentElement?.closest('[contenteditable="true"]'));
  let frame = null;
  if (!candidates.length) {
    const frames = [...node.querySelectorAll('iframe')].filter(own);
    if (frames.length === 1) {
      try { const body = frames[0].contentDocument?.body; if (body && (body.contentEditable === 'true' || body.getAttribute('contenteditable') === 'true')) { candidates.push(body); frame = frames[0]; } } catch {}
    }
  }
  if (candidates.length !== 1) return null;
  const body = candidates[0], element = frame || body;
  if (!questionNodeActive(element) || element.closest('[aria-disabled="true"],[aria-readonly="true"],[inert],fieldset[disabled]') || body.getAttribute('aria-readonly') === 'true' || body.getAttribute('aria-disabled') === 'true') return null;
  const textareas = [...node.querySelectorAll('textarea')].filter(own);
  // A hidden textarea is often the editor's native save field. A visible textarea
  // is another answer area, so it must not be silently ignored.
  if (textareas.some(input => questionNodeActive(input) || !/answer/i.test(input.name + ' ' + input.id))) return null;
  const saved = [...node.querySelectorAll('input[type="hidden"]')].filter(own).filter(input => /answer/i.test(input.name + ' ' + input.id)).concat(textareas);
  if (saved.length > 1 || saved.some(input => input.disabled || input.readOnly)) return null;
  return {body,frame,saved:saved[0] || null};
}

function readAppBlankSlots(raw) {
  const {node, inputs} = raw.binding;
  if (canonicalType(raw) !== 'blank' || inputs.length < 2 || raw.options.length ||
      node.querySelector('input[type="radio"],input[type="checkbox"],input[type="file"],select')) return null;
  const owner = element => element.closest('.singleQuesId,.TiMu,[data-question-id],[data-questionid],.questionLi,.question-item,.question');
  const doc = node.ownerDocument, form = node.closest('form');
  if (inputs.some(input => owner(input) !== node || input.closest(MEDIA_EXCLUDED) || input.form !== form ||
      input.id && [...doc.querySelectorAll('[id]')].filter(other => other.id === input.id).length !== 1 ||
      input.name && [...doc.querySelectorAll('input,textarea,select')].some(other => !inputs.includes(other) && other.form === form && other.name === input.name))) return null;
  // DOM order defines blank order only within one explicitly declared fill-in question.
  // A rich-text save field is writable only through its verified editor instance.
  const slots = inputs.map((input, index) => {
    const ueditor = input.tagName === 'TEXTAREA' ? readAppUEditor(node, raw.id, input) : null;
    const pending = Object.values(doc.defaultView?.UE?.instants || {}).filter(editor =>
      editor && (editor.textarea === input || editor.key === input.id));
    const editor = ueditor?.editor || (pending.length === 1 ? pending[0] : null);
    const editorFrame = editor?.iframe, editorContainer = editor?.container;
    const associated = editorFrame?.tagName === 'IFRAME' && owner(editorFrame) === node &&
      node.contains(editorFrame) && editorContainer?.nodeType === 1 && node.contains(editorContainer) && editorContainer.contains(editorFrame);
    const writable = ueditor?.valid || !editor && questionNodeActive(input) && !input.closest('.edui-editor') &&
      !input.disabled && !input.readOnly && !input.matches(':disabled') &&
      input.getAttribute('aria-readonly') !== 'true' && input.getAttribute('aria-disabled') !== 'true';
    return {id:`app-blank-${index + 1}`, text:`第 ${index + 1} 空（共 ${inputs.length} 空），只回答此空`,
      control:input, inputId:input.id, inputName:input.name, form:input.form,
      type:'blank', writable:Boolean(writable), ueditor:ueditor?.valid ? ueditor : null,
      editorFrame:associated ? editorFrame : null, editorContainer:associated ? editorContainer : null};
  });
  // Every rich editor must map to one slot; unrelated response areas stay unsupported.
  if ([...node.querySelectorAll('iframe,[contenteditable="true"]')].some(element =>
      !slots.some(slot => slot.editorFrame === element || slot.editorContainer?.contains(element)))) return null;
  return slots;
}
const originalRawQuestion = rawQuestion;
rawQuestion = function(node,index,adapter,context) {
  const raw = originalRawQuestion(node,index,adapter,context);
  if (!raw.typeLabel) raw.typeLabel = /多选/.test(raw.question) ? '多选' : /单选/.test(raw.question) ? '单选' : /判断/.test(raw.question) ? '判断' : /填空/.test(raw.question) ? '填空' : /简答|问答|论述/.test(raw.question) ? '简答' : '';
  const slots = readAppBlankSlots(raw);
  if (slots) {
    raw.binding.appBlankSlots = slots;
    raw.binding.ueditor = null;
  }
  if (!slots && ['blank','essay'].includes(canonicalType(raw)) && !raw.binding.ueditor) {
    const editable = readAppEditable(node);
    if (editable && raw.binding.inputs.every(input => input === editable.saved)) { raw.binding.appEditable = editable; raw.binding.inputs = [editable.body]; }
  }
  return raw;
};
const originalFinishQuestion = finishQuestion;
finishQuestion = function(raw,type,parser,inferred) {
  const result = originalFinishQuestion(raw,type,parser,inferred);
  if (raw.binding.appEditable && ['blank','essay'].includes(type)) {
    result.binding.writer = 'app-editable'; result.binding.canPrefill = true;
    result.question.capabilities.prefill = true;
    result.question.diagnostics = result.question.diagnostics.filter(text => !/文本框|文本控件/.test(text));
    result.question.answerPolicy = questionLocalAnswerPolicy(result.question);
  }
  return result;
};
const originalParseControls = parseQuestionControls;
parseQuestionControls = function(node,context,hints) {
  if (context?.binding?.appBlankSlots) {
    const result = originalParseControls(node,context,hints);
    for (const control of result.controls) {
      const slot = context.binding.appBlankSlots.find(slot => slot.control === result.runtime.get(control.id));
      if (slot) control.writable = slot.writable;
    }
    return result;
  }
  if (!context?.binding?.appEditable) return originalParseControls(node,context,hints);
  const binding = context.binding, id = `${context.question.id}:control:editable`;
  return {controls:[{id,kind:'text',groupId:context.question.id,recognized:true,writable:true,state:{disabled:false,readOnly:false},diagnostics:[]}],runtime:new Map([[id,binding.appEditable.body]]),diagnostics:[]};
};


const originalParseContent = parseQuestionContent;
parseQuestionContent = function(node, context, hints) {
  const content = originalParseContent(node, context, hints);
  const slots = context?.binding?.appBlankSlots;
  // Keep complete native labels; missing labels fall back to this question's blank order.
  if (slots && !content.slotAmbiguous && content.slots.length !== slots.length) {
    content.blocks = content.blocks.filter(block => block.type !== 'slot');
    content.slots = slots.map(slot => ({...slot,
      text:[slot.text,content.slots.find(native => native.control === slot.control)?.text].filter(Boolean).join('；'),
      evidence:['app-question-blank-order']}));
    content.blocks.push(...content.slots.map(slot => ({type:'slot',id:slot.id,text:slot.text})));
  }
  return content;
};
const originalParseGraph = parseQuestionGraph;
parseQuestionGraph = function(doc, context) {
  const graph = originalParseGraph(doc, context);
  for (const question of graph.questions) {
    const parent = graph.bindings.get(question.id);
    for (const unit of question.responseUnits) {
      const binding = graph.bindings.get(unit.id);
      const slot = parent.appBlankSlots?.find(slot => binding?.inputs.length === 1 && binding.inputs[0] === slot.control);
      if (!slot?.ueditor) continue;
      binding.ueditor = slot.ueditor;
      binding.appEditable = {body:slot.ueditor.body,frame:slot.ueditor.iframe,saved:slot.control};
      binding.writer = 'app-editable';
    }
  }
  return graph;
};
function assertAppBlankSlots(binding) {
  for (const slot of binding.appBlankSlots || []) {
    if (slot.control.id !== slot.inputId || slot.control.name !== slot.inputName || slot.control.form !== slot.form)
      throw new Error('填空答案字段或表单关联已变化，请重新识别题目');
  }
}
const originalAssertSnapshot = assertQuestionSnapshot;
assertQuestionSnapshot = function(snapshot, requirements) {
  const source = snapshot?.rootSnapshot || snapshot?._contextSnapshot || snapshot;
  for (const binding of source.graph.bindings.values()) assertAppBlankSlots(binding);
  return originalAssertSnapshot(snapshot, requirements);
};

function textQuestionIdentity(graph) {
  return JSON.stringify({questions:graph.questions.map(q => ({id:q.id,type:q.type,question:q.question,instructions:q.instructions,options:q.options,
    units:q.responseUnits.filter(unit => unit.type !== 'unknown').map(unit => ({id:unit.id,type:unit.type,question:unit.question}))})),materials:graph.materials,media:graph.media});
}
function refreshTextSnapshot(current) {
  const graph = parseQuestionGraph(document);
  if (textQuestionIdentity(graph) !== current.identity) throw new Error('题干、选项或作答区域已变化，请重新识别题目');
  for (const [id,before] of current.snapshot.graph.bindings) {
    assertAppBlankSlots(before);
    const after = graph.bindings.get(id);
    if (!after || before.node !== after.node || before.stem !== after.stem || before.inputs.some(input => !after.inputs.includes(input)) ||
        [...before.optionTargets].some(([key,target]) => after.optionTargets.get(key) !== target)) throw new Error('作答控件已替换，请重新识别题目');
    if (before.ueditor?.valid && !sameQuestionUEditor(before.ueditor,after.ueditor)) throw new Error('富文本编辑器实例已替换，请重新识别题目');
    if (before.appEditable && (!after.appEditable || ['body','frame','saved'].some(key => before.appEditable[key] !== after.appEditable[key]))) throw new Error('答案框或保存字段已替换，请重新识别题目');
    if (!['blank','essay'].includes(before.type)) {
      const first = questionBindingNodes(before), second = questionBindingNodes(after);
      if (first.length !== second.length || first.some((node,index) => node !== second[index]) ||
          !sameMooc2ChoiceBindings(before.mooc2,after.mooc2) || !sameChapterRowChoiceBindings(before.chapterRows,after.chapterRows) ||
          !sameQuestionMatching(before.matching,after.matching) || before.chapterEvidence !== after.chapterEvidence) throw new Error('选项控件或原生关联已变化，请重新识别题目');
    }
  }
  current.snapshot = createQuestionSnapshot(graph,{doc:document});
  current.projection = buildQuestionRequestUnits(graph);
}
async function fillAppEditable(current, answers, checkCurrent, beforeWrite = async () => {}, afterWrite = () => {}) {
  const result = {filled:0,already:0,skipped:0,unverified:0,details:[],perUnit:[]};
  const record = (entry,status,reason) => { result[status]++;result.perUnit.push({responseUnitId:entry.id,status,reason});if(reason)result.details.push(reason); };
  for (const entry of answers) {
    checkCurrent(); assertQuestionSnapshot(current.snapshot);
    const q = current.projection.questions.find(q => q.id === entry.id), binding = current.projection.bindings.get(entry.id), editable = binding.appEditable;
    const policy = questionPrefillPolicy(q,entry);
    if (!editable || !binding.canPrefill || policy.status !== 'auto' || entry.answer.length !== 1) {record(entry,'skipped',`第${q.number}题：${entry.answer.length !== 1 ? entry.reason || '答案为空或数量不正确' : policy.reason || q.diagnostics.join('；') || '答案框无法唯一关联'}`);continue;}
    const expected = questionUEText(entry.answer[0]), before = questionUEContent(editable.body.innerHTML,document);
    const ue = binding.ueditor;
    if (ue && questionUEState(binding).unknownSaved) {record(entry,'skipped',`第${q.number}题：编辑器与保存内容不一致，未覆盖`);continue;}
    const savedText = () => editable.saved ? [questionUEText(editable.saved.value),questionUEContent(editable.saved.value,document).text] : null;
    if (before.nonText || before.text && before.text !== expected || !before.text && savedText()?.some(Boolean)) {record(entry,'skipped',`第${q.number}题：已有手工作答或保存内容，未覆盖`);continue;}
    const verified = () => {
      if (ue) {
        const state = questionUEState(binding);
        return !state.unknownSaved && state.values.every(value => value === expected);
      }
      return questionUEContent(editable.body.innerHTML,document).text === expected && (!editable.saved || savedText().includes(expected));
    };
    if (before.text === expected && verified()) {record(entry,'already');continue;}
    await beforeWrite(); checkCurrent(); assertQuestionSnapshot(current.snapshot);
    const body = editable.body, realm = body.ownerDocument.defaultView;
    body.focus(); checkCurrent(); assertQuestionSnapshot(current.snapshot);
    if (questionUEContent(body.innerHTML,document).text !== before.text) throw new Error('答案框已被编辑，回填已停止');
    if (ue) {
      if (questionUEState(binding).unknownSaved) throw new Error('编辑器保存内容已变化，回填已停止');
      ue.editor.setContent(questionUEAnswerHTML(entry.answer[0],document),false);
      checkCurrent(); assertQuestionSnapshot(current.snapshot);
      ue.editor.sync();
    } else {
      body.replaceChildren();
      String(entry.answer[0]).replace(/\r\n?/g,'\n').split('\n').forEach((line,index) => {if(index)body.appendChild(body.ownerDocument.createElement('br'));body.appendChild(body.ownerDocument.createTextNode(line));});
      body.dispatchEvent(new realm.InputEvent('input',{bubbles:true,inputType:'insertText',data:String(entry.answer[0])}));
    body.dispatchEvent(new realm.Event('change',{bubbles:true}));
    }
    await waitFor(() => {checkCurrent(); assertQuestionSnapshot(current.snapshot); return verified();},800);
    checkCurrent(); assertQuestionSnapshot(current.snapshot);
    const ok = verified();
    record(entry,ok ? 'filled' : 'unverified',ok ? null : `第${q.number}题：答案框已写入，但保存字段尚未同步，已停止后续回填，请人工检查`);
    if (ok) afterWrite();
    if (!ok) break;
  }
  return result;
}
