const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const engine = require('./load_question_engine.cjs');
const wrap = fields => '<div class="TiMu" data-question-id="b"><div class="Zy_TItle">【填空题】分别填写各空</div>'+fields+'</div>';
const plain = wrap('<input type="text" id="first"><input type="text" id="second">');
function open(html, setup) {
  const dom = new JSDOM(html,{url:'https://mooc1.chaoxing.com/work/do',runScripts:'outside-only'});
  setup?.(dom.window); dom.window.eval(engine);
  return {w:dom.window,page:dom.window.__haQuestionEngine.extract()};
}
async function fill(r, answers) {
  return r.w.__haQuestionEngine.fill({page:r.page,currentFingerprint:r.page.fingerprint,fillIntervalMillis:500,
    answers:r.page.questions.map((q,i)=>({questionId:q.id,answer:answers[i]}))});
}
let cases = 0;
(async()=>{
  const two = open(plain);
  assert.equal(two.page.questions.length,2); assert.ok(two.page.questions.every(q=>q.canFill));
  assert.ok(two.page.questions.every(q=>q.sourceNumber===1 && /第 [12] 空/.test(q.sourcePart)));
  assert.equal((await fill(two,['第一空','第二空'])).filled.length,2);
  assert.deepEqual([...two.w.document.querySelectorAll('input')].map(x=>x.value),['第一空','第二空']); cases++;

  const three = open(wrap('<textarea></textarea><textarea></textarea><textarea></textarea>'));
  const values = ['一，二','第三；四','第五\n第六'];
  assert.equal((await fill(three,values)).filled.length,3);
  assert.deepEqual([...three.w.document.querySelectorAll('textarea')].map(x=>x.value),values); cases++;

  const array = open('<form>'+wrap('<input type="text" name="answer[]"><input type="text" name="answer[]">')+'</form>');
  assert.equal((await fill(array,['一','二'])).filled.length,2); cases++;

  const manual = open(plain); manual.w.document.querySelector('#first').value='自己的答案';
  assert.equal((await fill(manual,['一','二'])).filled.length,1);
  assert.equal(manual.w.document.querySelector('#first').value,'自己的答案'); cases++;

  const readonly = open(plain.replace('id="second"','id="second" readonly'));
  assert.equal((await fill(readonly,['一','二'])).filled.length,1);
  assert.equal(readonly.w.document.querySelector('#second').value,''); cases++;

  const aggregate = open(plain);
  assert.equal((await fill(aggregate,['["一","二"]','第二空'])).filled.length,1);
  assert.equal(aggregate.w.document.querySelector('#first').value,''); cases++;

  const duplicate = open(plain.replace('id="second"','id="first"'));
  assert.equal((await fill(duplicate,['一'])).filled.length,0); cases++;

  const mixed = open(plain.replace('</div>','<select><option>A</option></select></div>'));
  assert.equal((await fill(mixed,['一'])).filled.length,0); cases++;

  const replaced = open(plain), input = replaced.w.document.querySelector('#first');
  input.replaceWith(input.cloneNode());
  await assert.rejects(()=>fill(replaced,['一','二'])); cases++;

  const reordered = open(plain);
  reordered.w.document.querySelector('#first').before(reordered.w.document.querySelector('#second'));
  await assert.rejects(()=>fill(reordered,['一','二'])); cases++;

  const renamed = open(plain); renamed.w.document.querySelector('#first').name='changed';
  await assert.rejects(()=>fill(renamed,['一','二'])); cases++;

  const rich = '<form>'+wrap('<textarea id="answer1" name="answer1" style="display:none"></textarea><div class="edui-editor"><iframe></iframe></div><textarea id="answer2" name="answer2" style="display:none"></textarea><div class="edui-editor"><iframe></iframe></div>')+'</form>';
  function editors(w) {
    w.UE={instants:{}};
    [...w.document.querySelectorAll('textarea')].forEach((input,index)=>{
      const frame=w.document.querySelectorAll('iframe')[index], inner=frame.contentDocument;
      inner.body.contentEditable='true';
      w.UE.instants[input.id]={key:input.id,isReady:true,textarea:input,form:input.form,iframe:frame,container:frame.parentElement,document:inner,body:inner.body,options:{readonly:false},
        getContent:()=>inner.body.innerHTML,setContent:html=>{inner.body.innerHTML=html},sync:()=>{input.value=inner.body.innerHTML}};
    });
  }
  const ue = open(rich,editors);
  assert.equal(ue.page.questions.length,2); assert.ok(ue.page.questions.every(q=>q.canFill));
  const answers=['第一空','第二空\n<script>原文</script>'];
  assert.equal((await fill(ue,answers)).filled.length,2);
  assert.ok(ue.w.document.querySelector('#answer1').value.includes('第一空'));
  assert.ok(ue.w.document.querySelector('#answer2').value.includes('第二空'));
  assert.equal(ue.w.document.querySelectorAll('iframe')[1].contentDocument.querySelector('script'),null);
  assert.equal((await fill(ue,answers)).already.length,2); cases++;

  const pending = open(rich,w=>{editors(w);Object.values(w.UE.instants).forEach(editor=>{editor.isReady=false;});});
  assert.equal(pending.page.questions.length,2); assert.ok(pending.page.questions.every(q=>!q.canFill));
  Object.values(pending.w.UE.instants).forEach(editor=>{editor.isReady=true;});
  assert.equal((await fill(pending,['一','二'])).filled.length,2); cases++;

  const unsynced = open(rich,w=>{editors(w);w.UE.instants.answer1.sync=()=>{};});
  assert.equal((await fill(unsynced,['一','二'])).unverified.length,1);
  assert.equal(unsynced.w.document.querySelectorAll('iframe')[1].contentDocument.body.textContent,''); cases++;

  const instance = open(rich,editors); instance.w.UE.instants.answer1={...instance.w.UE.instants.answer1};
  await assert.rejects(()=>fill(instance,['一','二'])); cases++;

  const partlyLabeled = open(wrap('<label for="first">质量</label><input type="text" id="first"><input type="text" id="second">'));
  assert.ok(partlyLabeled.page.questions[0].stem.includes('质量'));
  assert.equal((await fill(partlyLabeled,['质量答案','其他答案'])).filled.length,2); cases++;

  const richManual = open(rich,editors);
  richManual.w.UE.instants.answer1.setContent('<p>手动答案</p>'); richManual.w.UE.instants.answer1.sync();
  assert.equal((await fill(richManual,['一','二'])).filled.length,1);
  assert.ok(richManual.w.document.querySelector('#answer1').value.includes('手动答案')); cases++;

  const richReadonly = open(rich,w=>{editors(w);w.UE.instants.answer1.options.readonly=true;});
  assert.equal((await fill(richReadonly,['一','二'])).filled.length,1);
  assert.equal(richReadonly.w.document.querySelector('#answer1').value,''); cases++;

  console.log(`Multi-blank filling: ${cases} regression cases passed.`);
})().catch(error=>{console.error(error);process.exitCode=1});
