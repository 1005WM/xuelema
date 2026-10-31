const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const engine = require('./load_question_engine.cjs');
function editor(w, ready=true) {
  const input=w.document.querySelector('textarea'),frame=w.document.querySelector('iframe'),inner=frame.contentDocument;
  inner.body.contentEditable='true';
  w.UE={instants:{fixture:{key:input.id,isReady:ready,textarea:input,form:input.form,iframe:frame,container:frame.parentElement,document:inner,body:inner.body,options:{readonly:false},
    getContent:()=>inner.body.innerHTML,setContent:html=>{inner.body.innerHTML=html},sync:()=>{input.value=inner.body.innerHTML}}}};
}
const rich=`<form id="homeworkForm"><div class="questionLi singleQuesId" data="88" id="question88" typename="简答题"><div class="mark_name">【简答题】解释概念</div><textarea id="answerEditor88" name="answer88" style="display:none"></textarea><div class="edui-editor"><iframe></iframe></div></div></form>`;
function open(html,setup,url='https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork') {
  const dom=new JSDOM(html,{url,runScripts:'outside-only'});setup?.(dom.window);dom.window.eval(engine);
  const page=dom.window.__haQuestionEngine.extract();return {w:dom.window,page};
}
async function fill(r,text,id=r.page.questions[0].id) { return JSON.parse(JSON.stringify(await r.w.__haQuestionEngine.fill({page:r.page,currentFingerprint:r.page.fingerprint,answers:[{questionId:id,answer:text}]}))); }
let cases=0;
(async()=>{
  const modern=open(rich,editor);assert.equal(modern.page.questions[0].canFill,true);
  assert.equal((await fill(modern,'第一行\n第二行')).filled.length,1);assert.ok(modern.w.document.querySelector('textarea').value.includes('第二行'));cases++;
  const later=open(rich,w=>editor(w,false));assert.equal(later.page.questions[0].canFill,false);
  later.w.UE.instants.fixture.isReady=true;assert.equal((await fill(later,'延迟就绪')).filled.length,1);cases++;
  const replaced=open(rich,editor);replaced.w.document.querySelector('textarea').replaceWith(replaced.w.document.querySelector('textarea').cloneNode());
  await assert.rejects(()=>fill(replaced,'错误匹配'));cases++;
  const ce=`<div class="TiMu" data-question-id="s"><div class="Zy_TItle">【简答题】解释概念</div><div contenteditable="true" role="textbox"></div></div>`;
  const editable=open(ce);assert.equal(editable.page.questions[0].canFill,true);
  let events=0;editable.w.document.querySelector('[contenteditable]').addEventListener('input',()=>events++);
  assert.equal((await fill(editable,'答案\n<script>literal</script>')).filled.length,1);
  assert.equal(events,1);assert.equal(editable.w.document.querySelector('[contenteditable] script'),null);cases++;
  const existing=open(ce);existing.w.document.querySelector('[contenteditable]').textContent='手动答案';
  assert.equal((await fill(existing,'AI答案')).filled.length,0);assert.equal(existing.w.document.querySelector('[contenteditable]').textContent,'手动答案');cases++;
  const readonly=open(ce.replace('role="textbox"','role="textbox" aria-readonly="true"'));
  assert.equal((await fill(readonly,'不能写')).filled.length,0);cases++;
  const changed=open(ce);changed.w.document.querySelector('.Zy_TItle').textContent='【简答题】另一道题';await assert.rejects(()=>fill(changed,'旧答案'));cases++;
  const synced=open(ce.replace('</div></div>','</div><input type="hidden" id="answers" name="answer"></div>'),w=>{
    w.document.querySelector('[contenteditable]').addEventListener('input',e=>{w.document.querySelector('input').value=e.target.innerHTML});
  });assert.equal((await fill(synced,'同步答案')).filled.length,1);cases++;
  const unsynced=open(ce.replace('</div></div>','</div><input type="hidden" id="answers" name="answer"></div>'));
  assert.equal((await fill(unsynced,'待核对')).unverified.length,1);cases++;
  const iframe=open(`<div class="TiMu" data-question-id="s"><div class="Zy_TItle">【填空题】定义</div><iframe class="answer-editor"></iframe></div>`,w=>{w.document.querySelector('iframe').contentDocument.body.contentEditable='true'});
  assert.equal((await fill(iframe,'定义内容')).filled.length,1);cases++;
  const ambiguous=open(ce.replace('</div></div>','</div><div contenteditable="true"></div></div>'));
  assert.equal((await fill(ambiguous,'不猜测')).filled.length,0);cases++;
  const blank=open(`<div class="TiMu" data-question-id="b"><div class="Zy_TItle">【填空题】定义</div><textarea></textarea></div>`);
  assert.equal((await fill(blank,'["单空答案"]')).filled.length,1);assert.equal(blank.w.document.querySelector('textarea').value,'单空答案');cases++;
  const mixed=open(ce.replace('</div></div>','</div><select><option>A</option></select></div>'));
  assert.equal((await fill(mixed,'不猜测控件')).filled.length,0);cases++;
  const savedReplaced=open(ce.replace('</div></div>','</div><input type="hidden" name="answer"></div>'));
  const oldSaved=savedReplaced.w.document.querySelector('input');oldSaved.replaceWith(oldSaved.cloneNode());
  await assert.rejects(()=>fill(savedReplaced,'不写旧关联'));cases++;
  const readonlyEditor=open(rich.replace('<iframe>','<iframe aria-readonly="true">'),editor);
  assert.equal((await fill(readonlyEditor,'只读不写')).filled.length,0);cases++;
  console.log(`Text-answer filling: ${cases} regression cases passed.`);
})().catch(e=>{console.error(e);process.exitCode=1});
