const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {createRequire}=require('node:module');

const filename=path.join(__dirname,'../demo.cjs');
const source=fs.readFileSync(filename,'utf8');
function cli(up,provision) {
  const module={exports:{}};
  const require=createRequire(filename);
  require.main=module;
  const errors=[];
  const context={require,module,__dirname:path.dirname(filename),console:{error:e=>errors.push(e)},
    process:{argv:['node',filename,'start'],env:{},exitCode:undefined},
    startUp:up,startProvision:provision};
  // Replace only external operations; exercise the real dispatcher and CLI error handler.
  const entry='if(require.main===module) main().catch';
  assert.ok(source.includes(entry));
  vm.runInNewContext(source.replace(entry,
    'up=globalThis.startUp; provision=globalThis.startProvision; seed=()=>{throw new Error("Unexpected seed")};\n'+
    'if(require.main===module) globalThis.completion=main().catch'),context,{filename});
  return {context,errors,done:context.completion};
}

test('start awaits healthy startup before provisioning and does not seed',async()=>{
  let ready;
  const healthy=new Promise(resolve=>{ready=resolve;});
  const calls=[];
  const run=cli(async()=>{calls.push('up');await healthy;calls.push('healthy');},async()=>{calls.push('provision');});
  assert.deepEqual(calls,['up']);
  ready();await run.done;
  assert.deepEqual(calls,['up','healthy','provision']);
  assert.equal(run.context.process.exitCode,undefined);
  assert.deepEqual(run.errors,[]);
});

for(const stage of ['startup','provisioning']) test(`start exits nonzero on ${stage} failure`,async()=>{
  let provisioned=false;
  const run=cli(async()=>{if(stage==='startup') throw new Error('startup failed');},async()=>{
    provisioned=true;throw new Error('provisioning failed');
  });
  await run.done;
  assert.equal(run.context.process.exitCode,1);
  assert.equal(provisioned,stage==='provisioning');
  assert.deepEqual(run.errors,[`${stage} failed`]);
});
