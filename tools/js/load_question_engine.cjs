const fs = require('node:fs');
const path = require('node:path');
const scripts = path.resolve(__dirname, '../../app/src/main/assets/scripts');
const modules = ['question-context.js','question-parser.js','question-actions.js'];
module.exports = fs.readFileSync(path.join(scripts,'question_engine.js'),'utf8').replace('/*__UPSTREAM__*/',
  modules.map(name=>fs.readFileSync(path.join(scripts,'chaoxing_ai',name),'utf8')).join('\n'))
  .replace('/*__TEXT_ADAPTER__*/',fs.readFileSync(path.join(scripts,'question_text_adapter.js'),'utf8'));
