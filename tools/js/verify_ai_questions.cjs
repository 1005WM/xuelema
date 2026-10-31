const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {JSDOM} = require('jsdom');
const engine = require('./load_question_engine.cjs');
const vendor = path.resolve(__dirname,'../../app/src/main/assets/scripts/chaoxing_ai');
const manifest = JSON.parse(fs.readFileSync(path.join(vendor,'manifest.json'),'utf8').replace(/^\uFEFF/,''));
for (const file of manifest.files) assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(vendor,file.path))).digest('hex'),file.sha256);
let checks = 0;
function run(html, url='https://mooc1.chaoxing.com/work/do', setup) {
  const dom = new JSDOM(html,{url,runScripts:'outside-only'}); if(setup)setup(dom.window); dom.window.eval(engine);
  const page = dom.window.__haQuestionEngine.extract();
  return {dom,page};
}
async function fill(result, values) {
  return JSON.parse(JSON.stringify(await result.dom.window.__haQuestionEngine.fill({page:result.page,currentFingerprint:result.page.fingerprint,
    answers:Object.entries(values).map(([questionId,answer])=>({questionId,answer,explanation:'fixture'}))})));
}
const single = `<div class="question" data-question-id="q1"><div class="stem">单选：2+2？</div><label><input type="radio" name="q1">A. 3</label><label><input type="radio" name="q1">B. 4</label></div>`;
(async()=>{
  const r=run(single);
  assert.equal(r.page.questions[0].type,'SINGLE'); assert.equal(r.page.questions[0].canFill,true);
  assert.deepEqual(JSON.parse(JSON.stringify(r.page.questions[0].optionKeys)),['A','B']); checks++;
  assert.deepEqual((await fill(r,{q1:'B'})).filled,['q1']); assert.equal(r.dom.window.document.querySelectorAll('input')[1].checked,true); checks++;
  assert.deepEqual((await fill(r,{q1:'B'})).already,['q1']); checks++;
  assert.equal((await fill(r,{q1:'A'})).filled.length,0); assert.equal(r.dom.window.document.querySelectorAll('input')[1].checked,true); checks++;
  const changed=run(single);changed.dom.window.document.querySelector('.stem').textContent='单选：2+3？';
  await assert.rejects(()=>fill(changed,{q1:'B'})); assert.equal(changed.dom.window.document.querySelector('input').checked,false); checks++;
  const replaced=run(single);const input=replaced.dom.window.document.querySelector('input');input.replaceWith(input.cloneNode(true));
  await assert.rejects(()=>fill(replaced,{q1:'A'}));checks++;
  const wrong=run(single);wrong.page.fingerprint='bad';assert.equal((await fill(wrong,{q1:'A'})).filled.length,0);checks++;
  const short=run(`<div class="TiMu" data-question-id="s"><div class="Zy_TItle">【简答题】解释导数</div><textarea></textarea></div>`);
  assert.equal(short.page.questions[0].type,'SHORT');assert.deepEqual((await fill(short,{s:'变化率\n保留换行'})).filled,['s']);
  assert.equal(short.dom.window.document.querySelector('textarea').value,'变化率\n保留换行');checks++;
  const occupied=run(`<div class="TiMu" data-question-id="s"><div class="Zy_TItle">【简答题】解释导数</div><textarea>自己的答案</textarea></div>`);
  assert.equal((await fill(occupied,{s:'替换'})).filled.length,0);assert.equal(occupied.dom.window.document.querySelector('textarea').value,'自己的答案');checks++;
  const multi=run(`<div class="TiMu" data-question-id="m"><div class="Zy_TItle">【多选题】选择偶数</div><label><input type="checkbox" value="101">2</label><label><input type="checkbox" value="202">4</label></div>`);
  assert.deepEqual((await fill(multi,{m:'101,202'})).filled,['m']);assert.equal(multi.dom.window.document.querySelectorAll('input:checked').length,2);checks++;
  const judge=run(`<div class="TiMu" data-question-id="j"><div class="Zy_TItle">【判断题】1=1</div><label><input type="radio" name="j" value="yes">正确</label><label><input type="radio" name="j" value="no">错误</label></div>`);
  assert.deepEqual((await fill(judge,{j:'正确'})).filled,['j']);checks++;
  const composite=run(`<div class="TiMu" data-question-id="c"><div class="Zy_TItle">【填空题】分别填写</div><label for="first">第一空</label><input type="text" id="first"><label for="second">第二空</label><input type="text" id="second"></div>`);
  assert.equal(composite.page.questions.length,2);const values=Object.fromEntries(composite.page.questions.map((q,i)=>[q.id,`答案${i+1}`]));
  assert.equal((await fill(composite,values)).filled.length,2);checks++;
  const ordered=run(`<div class="TiMu" data-question-id="c"><div class="Zy_TItle">【填空题】两空</div><input type="text"><input type="text"></div>`);
  assert.equal(ordered.page.questions.length,2);assert.ok(ordered.page.questions.every(q=>q.canFill));
  assert.equal((await fill(ordered,Object.fromEntries(ordered.page.questions.map((q,index)=>[q.id,['一','二'][index]])))).filled.length,2);checks++;
  const math=run(`<div class="TiMu" data-question-id="s"><div class="Zy_TItle">【计算题】求导 <span data-latex="x^2"></span></div><textarea></textarea></div>`);
  assert.equal(math.page.questions[0].type,'SHORT');assert.ok(math.page.questions[0].stem.includes('x^2'));assert.equal(math.page.questions[0].hasUnsupportedMedia,false);checks++;
  const images=run(`<div class="TiMu" data-question-id="p"><div class="Zy_TItle">【单选题】选图<img src="https://p.ananas.chaoxing.com/stem.png"></div><label><input type="radio" name="p" value="A"><img src="https://p.ananas.chaoxing.com/a.png"></label><label><input type="radio" name="p" value="B">文字B</label></div>`);
  assert.equal(images.page.questions[0].images.length,2);assert.equal(images.page.questions[0].images.find(x=>x.optionKey==='A').location,'option');checks++;
  const manyImages=run(`<div class="TiMu" data-question-id="many"><div class="Zy_TItle">【简答题】分析全部图片${Array.from({length:40},(_,i)=>`<img data-src="https://p.ananas.chaoxing.com/image-${i+1}.png" src="/placeholder.png">`).join('')}</div><textarea></textarea></div>`);
  assert.equal(manyImages.page.questions[0].images.length,40);
  assert.ok(manyImages.page.questions[0].images.every((image,i)=>image.url===`https://p.ananas.chaoxing.com/image-${i+1}.png` && image.referer==='https://mooc1.chaoxing.com/work/do'));
  assert.equal(manyImages.page.questions[0].canAnalyze,true);checks++;
  const secret=run(`<div class="TiMu" data-question-id="x"><div class="Zy_TItle font-cxsecret">【简答题】坁坅坆</div><textarea></textarea></div>`);
  assert.equal(secret.page.questions[0].canAnalyze,false);assert.ok(secret.page.questions[0].diagnostics.some(x=>x.includes('混淆字体')));checks++;
  const review=run(`<div class="fanyaMarking_left"><div class="TiMu" data-question-id="r"><div class="Zy_TItle">【简答题】解释导数</div><div class="mark_answer">学生已有作答</div></div></div>`,'https://mooc1.chaoxing.com/work/view/1');
  assert.equal(review.page.questions[0].canFill,false);assert.equal(review.page.questions[0].stem.includes('学生已有作答'),false);checks++;
  const exam=run(single,'https://mooc1.chaoxing.com/exam-ans/exam/test'); assert.equal(exam.page.questions.length,1); assert.deepEqual((await fill(exam,{q1:'B'})).filled,['q1']);checks++;
  assert.throws(()=>run('<h1>考试列表</h1>','https://mooc1.chaoxing.com/exam-ans/exam/test/examcode/examlist'),/没有检测到题目/);checks++;
  const examTiMu=run(single.replace('class="question"','class="TiMu"'),'https://mooc1.chaoxing.com/exam-ans/exam/test');assert.equal(examTiMu.page.questions.length,1);assert.deepEqual((await fill(examTiMu,{q1:'B'})).filled,['q1']);checks++;
  const external=new JSDOM(single,{url:'https://chaoxing.com.evil.test/work/do',runScripts:'outside-only'});external.window.eval(engine);assert.equal(external.window.__haQuestionEngine,undefined);checks++;
  assert.equal(/\.submit\s*\(|requestSubmit\s*\(/.test(engine),false);assert.equal(/GM_xmlhttpRequest|GM_getValue|GM_setValue/.test(engine),false);checks++;
  const moocHtml=`<form><div class="fanyaMarking_left"><div class="questionLi singleQuesId" data="77" typename="单选题"><div class="mark_name">选择正确答案</div><div class="stem_answer">${['A','B'].map((key,i)=>`<div class="answerBg" qid="77" qtype="0" role="radio" onclick="addChoice(this)"><span class="num_option" data="value-${i}">${key}</span><div class="answer_p">选项${key}</div></div>`).join('')}</div><input type="hidden" id="answer77" name="answer77"></div></div></form>`;
  const mooc=run(moocHtml,'https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork',w=>{
    for(const row of w.document.querySelectorAll('.answerBg'))row.addEventListener('click',()=>{
      for(const other of w.document.querySelectorAll('.answerBg')){other.setAttribute('aria-checked','false');other.querySelector('.num_option').classList.remove('check_answer');}
      row.setAttribute('aria-checked','true');row.querySelector('.num_option').classList.add('check_answer');w.document.querySelector('#answer77').value=row.querySelector('.num_option').getAttribute('data');
    });
  });
  assert.equal(mooc.page.questions[0].sourceAdapter,'chaoxing-mooc2-work');assert.equal(mooc.page.questions[0].canFill,true);
  assert.deepEqual((await fill(mooc,{'77':'B'})).filled,['77']);assert.equal(mooc.dom.window.document.querySelector('#answer77').value,'value-1');checks++;
  const examMooc=run(moocHtml,'https://mooc1.chaoxing.com/exam-ans/exam/test');assert.equal(examMooc.page.questions[0].options.length,2);assert.equal(examMooc.page.questions[0].canAnalyze,true);checks++;
  const brokenMooc=run(moocHtml,'https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork');
  assert.equal((await fill(brokenMooc,{'77':'B'})).unverified.length,1);checks++;
  const editorHtml=`<form id="form1"><div class="singleQuesId" data="88" id="question88"><div class="Zy_TItle">【简答题】解释概念</div><textarea id="answer88" name="answer88"></textarea><div class="edui-editor"><iframe></iframe></div></div></form>`;
  const editor=run(editorHtml,'https://mooc1.chaoxing.com/work/doHomeWorkNew',w=>{
    const frame=w.document.querySelector('iframe'),inner=frame.contentDocument;inner.body.contentEditable='true';
    const input=w.document.querySelector('textarea');
    w.UE={instants:{fixture:{key:'answer88',isReady:true,textarea:input,form:w.document.querySelector('form'),iframe:frame,container:frame.parentElement,document:inner,body:inner.body,options:{readonly:false},
      getContent:()=>inner.body.innerHTML,setContent:html=>{inner.body.innerHTML=html},sync:()=>{input.value=inner.body.innerHTML}}}};
  });
  assert.equal(editor.page.questions[0].canFill,true);assert.deepEqual((await fill(editor,{'88':'解释\n<script>alert(1)</script>'})).filled,['88']);
  assert.equal(editor.dom.window.document.querySelector('iframe').contentDocument.querySelector('script'),null);
  assert.ok(editor.dom.window.document.querySelector('textarea').value.includes('&lt;script&gt;'));checks++;
  const shared=run(`<div id="material">共同材料：速度为10</div><div class="TiMu" data-question-id="t" aria-describedby="material"><div class="Zy_TItle">【简答题】解释</div><textarea></textarea></div>`);
  assert.ok(shared.page.questions[0].stem.includes('速度为10'));checks++;
  const posts=[];const bridge=run(single,'https://mooc1.chaoxing.com/work/do',w=>{w.QuestionDomBridge={postMessage:raw=>posts.push(JSON.parse(raw))}});
  bridge.dom.window.__haQuestionEngine.dispatch({type:'HA_QUESTION_COMMAND',op:'read',nonce:'fixture-nonce'});
  assert.equal(posts[0].nonce,'fixture-nonce');assert.equal(posts[0].page.questions.length,1);
  bridge.dom.window.QuestionDomBridge.onmessage({data:JSON.stringify({type:'HA_QUESTION_COMMAND',op:'fill',nonce:'fill-nonce',payload:{page:posts[0].page,currentFingerprint:posts[0].page.fingerprint,answers:[{questionId:'q1',answer:'B'}]}})});
  await new Promise(resolve=>setTimeout(resolve,300));assert.equal(posts.at(-1).nonce,'fill-nonce');assert.deepEqual(posts.at(-1).result.filled,['q1']);checks++;
  console.log(`Chaoxing AI engine: ${checks} cases passed (pinned vendor hashes, recognition, math, options, composite slots, media, protected prefilling).`);
})().catch(error=>{console.error(error);process.exitCode=1});
