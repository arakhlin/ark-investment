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

function startupHarness(failCommand) {
  const commands=[];
  const actualRequire=createRequire(filename);
  const {EventEmitter}=require('node:events');
  const record=args=>{
    assert.deepEqual(Array.from(args.slice(0,3)),['compose','-p','ark-demo-test']);
    const command=Array.from(args.slice(7));
    commands.push(command);
    return command[0]===failCommand;
  };
  const childProcess={
    spawn:(executable,args)=>{
      assert.equal(executable,'docker');
      const failed=record(args),child=new EventEmitter();
      queueMicrotask(()=>child.emit('exit',failed?1:0,null));
      return child;
    },
    spawnSync:(executable,args)=>{
      assert.equal(executable,'docker');
      return {status:record(args)?1:0,stdout:'',stderr:''};
    }
  };
  const context={require:name=>name==='node:child_process'?childProcess:
    name==='node:fs'?{...fs,chmodSync:()=>{}}:actualRequire(name),
    module:{exports:{}},__dirname:path.dirname(filename),
    process:{env:{DEMO_PROJECT:'ark-demo-test'}},console:{log:()=>{}},
    config:{apiPort:18080,oidcPort:19080}};
  vm.runInNewContext(source+'\ninitialize=()=>{}; load=()=>globalThis.config; module.exports.up=up;',context,{filename});
  return {commands,up:context.module.exports.up};
}

test('startup builds both images before copying the realm and waiting for healthy services',async()=>{
  const h=startupHarness();
  await h.up();
  assert.deepEqual(h.commands.map(c=>c[0]),['pull','build','create','cp','up']);
  assert.deepEqual(h.commands[0],['pull','db']);
  assert.deepEqual(h.commands[1],['build','--pull','api','keycloak']);
  assert.deepEqual(h.commands[2],['create','--no-build']);
  assert.ok(h.commands[3][1].endsWith('/realm/ark-dev-realm.json'));
  assert.equal(h.commands[3][2],'keycloak:/opt/keycloak/data/import/ark-dev-realm.json');
  assert.deepEqual(h.commands[4],['up','-d','--no-build','--wait','--wait-timeout','240']);
});

for(const command of ['pull','build','create','cp']) test(`startup stops after ${command} failure`,async()=>{
  const h=startupHarness(command);
  await assert.rejects(h.up(),/Docker/);
  assert.equal(h.commands.at(-1)[0],command);
  assert.ok(!h.commands.some(c=>c[0]==='up'));
});

function tokenHarness(fetch,timeout=ms=>AbortSignal.timeout(ms)) {
  const config={project:'ark-demo-test',oidcPort:19080,apiPort:18080,
    userPasswords:Object.fromEntries(require('./fixtures.cjs').users.map(u=>[u.name,'test-placeholder']))};
  const delays=[],deadlines=[],messages=[],sql=[];
  const context={require:createRequire(filename),module:{exports:{}},__dirname:path.dirname(filename),
    process:{env:{}},URLSearchParams,Buffer,fetch,config,runSql:text=>{sql.push(text);return '';},
    AbortSignal:{timeout:ms=>{deadlines.push(ms);return timeout(ms);}},
    setTimeout:(resolve,ms)=>{delays.push(ms);queueMicrotask(resolve);},
    console:{error:message=>messages.push(message),log:()=>{}}};
  vm.runInNewContext(source+'\nload=()=>globalThis.config; sql=globalThis.runSql; module.exports.provisionToken=provisionToken;',context,{filename});
  return {...context.module.exports,config,delays,deadlines,messages,sql};
}
const issued=()=>new Response(JSON.stringify({access_token:'test-token'}),{status:200});

test('provisioning retries the actual timeout abort without increasing the 20-second request deadline',async()=>{
  let calls=0;
  const h=tokenHarness(async(url,options)=>{
    calls++;
    assert.equal(url,'http://localhost:19080/realms/ark-dev/protocol/openid-connect/token');
    assert.equal(options.method,'POST');assert.equal(options.body.get('username'),'alpha-admin');
    if(calls===1) return new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true}));
    return issued();
  },()=>{
    const controller=new AbortController();
    setTimeout(()=>controller.abort(new DOMException('Test deadline','TimeoutError')),5);
    return controller.signal;
  });
  assert.equal(await h.provisionToken(h.config,'alpha-admin'),'test-token');
  assert.equal(calls,2);assert.deepEqual(h.deadlines,[20000,20000]);assert.deepEqual(h.delays,[1000]);
  assert.ok(h.messages[0].includes('TimeoutError'));
  assert.ok(h.messages.every(m=>!m.includes('test-placeholder')&&!m.includes('test-token')));
});

for(const status of [429,502,503,504]) test(`provisioning retries transient HTTP ${status}`,async()=>{
  let calls=0;
  const h=tokenHarness(async()=>++calls===1?new Response('temporary',{status}):issued());
  assert.equal(await h.provisionToken(h.config,'alpha-admin'),'test-token');
  assert.equal(calls,2);assert.deepEqual(h.delays,[1000]);
});

for(const code of ['ECONNREFUSED','ECONNRESET','ETIMEDOUT','EAI_AGAIN','UND_ERR_CONNECT_TIMEOUT','UND_ERR_SOCKET']) test(`provisioning retries transient transport ${code}`,async()=>{
  let calls=0;
  const h=tokenHarness(async()=>{if(++calls===1) throw new TypeError('fetch failed',{cause:{code}});return issued();});
  assert.equal(await h.provisionToken(h.config,'alpha-admin'),'test-token');assert.equal(calls,2);
});

test('real HTTP body timeout retries after headers arrive', {timeout:10000},async(t)=>{
  const http=require('node:http');
  let calls=0,bodyAbort;
  const server=http.createServer((request,response)=>{
    request.resume();calls++;
    response.writeHead(200,{'Content-Type':'application/json'});
    response.flushHeaders();
    if(calls>1) response.end(JSON.stringify({access_token:'test-token'}));
    // First response deliberately never finishes; the client deadline must abort it.
  });
  t.after(async()=>{
    const closed=new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve()));
    server.closeAllConnections();
    await closed;
  });
  await new Promise((resolve,reject)=>{
    server.once('error',reject);
    server.listen(0,'127.0.0.1',resolve);
  });
  const signals=[];
  const h=tokenHarness(async(url,options)=>{
    signals.push(options.signal);
    const response=await fetch(url.replace('localhost','127.0.0.1'),options);
    assert.equal(options.signal.aborted,false,'Headers must arrive before the deadline');
    return {status:response.status,json:async()=>{
      try { return await response.json(); }
      catch(error) { bodyAbort=error;throw error; }
    }};
  });
  h.config.oidcPort=server.address().port;
  assert.equal(await h.provisionToken(h.config,'alpha-admin',500),'test-token');
  assert.ok(['AbortError','TimeoutError'].includes(bodyAbort.name));
  assert.equal(signals[0].reason.name,'TimeoutError');
  assert.notEqual(signals[0],signals[1]);
  assert.equal(calls,2);assert.deepEqual(h.deadlines,[500,500]);
  assert.deepEqual(h.delays,[1000]);assert.match(h.messages[0],/TimeoutError/);
});

test('a body AbortError caused by the request deadline is normalized and retried',async()=>{
  let calls=0;
  const aborted=new DOMException('Body read aborted','AbortError');
  const h=tokenHarness(async(url,options)=>({status:200,json:async()=>{
    if(++calls===1) {
      assert.equal(options.signal.reason.name,'TimeoutError');
      throw aborted;
    }
    return {access_token:'test-token'};
  }}),()=>{
    const controller=new AbortController();
    controller.abort(new DOMException('Request deadline','TimeoutError'));
    return controller.signal;
  });
  assert.equal(await h.provisionToken(h.config,'alpha-admin'),'test-token');
  assert.equal(calls,2);assert.deepEqual(h.delays,[1000]);
  assert.match(h.messages[0],/TimeoutError/);
});

test('an abort with a non-timeout signal reason is not retried',async()=>{
  const reason=new DOMException('Explicit cancellation','AbortError');
  let calls=0;
  const h=tokenHarness(async()=>{calls++;throw reason;},()=>{
    const controller=new AbortController();controller.abort(reason);return controller.signal;
  });
  await assert.rejects(h.provisionToken(h.config,'alpha-admin'),error=>error.cause===reason);
  assert.equal(calls,1);assert.deepEqual(h.delays,[]);
});

test('persistent timeouts stop after four attempts with bounded backoff and endpoint context',async()=>{
  let calls=0;
  const h=tokenHarness(async()=>{calls++;throw new DOMException('Test deadline','TimeoutError');});
  await assert.rejects(h.provisionToken(h.config,'alpha-admin'),error=>{
    assert.match(error.message,/alpha-admin at http:\/\/localhost:19080\/realms\/ark-dev\/protocol\/openid-connect\/token failed after 4 attempt\(s\): TimeoutError/);
    assert.equal(error.cause.name,'TimeoutError');return true;
  });
  assert.equal(calls,4);assert.deepEqual(h.delays,[1000,2000,4000]);assert.deepEqual(h.deadlines,[20000,20000,20000,20000]);
});

for(const status of [400,401,403,404,500]) test(`permanent HTTP ${status} fails immediately without retrying credentials`,async()=>{
  let calls=0;
  const h=tokenHarness(async()=>{calls++;return new Response(JSON.stringify({error:'invalid_grant'}),{status});});
  await assert.rejects(h.provisionToken(h.config,'alpha-admin'),error=>error.cause.status===status);
  assert.equal(calls,1);assert.deepEqual(h.delays,[]);assert.deepEqual(h.messages,[]);
});

for(const [label,error] of [
  ['unknown fetch error',new TypeError('unexpected failure')],
  ['DNS configuration',new TypeError('fetch failed',{cause:{code:'ENOTFOUND'}})],
  ['TLS configuration',new TypeError('fetch failed',{cause:{code:'CERT_HAS_EXPIRED'}})],
  ['explicit cancellation',new DOMException('cancelled','AbortError')]
]) test(`${label} is not retried`,async()=>{
  let calls=0;const h=tokenHarness(async()=>{calls++;throw error;});
  await assert.rejects(h.provisionToken(h.config,'alpha-admin'),e=>e.cause===error);
  assert.equal(calls,1);assert.deepEqual(h.delays,[]);
});

for(const body of ['not-json','{}']) test(`invalid successful token response (${body}) fails immediately`,async()=>{
  let calls=0;const h=tokenHarness(async()=>{calls++;return new Response(body,{status:200});});
  await assert.rejects(h.provisionToken(h.config,'alpha-admin'));
  assert.equal(calls,1);assert.deepEqual(h.delays,[]);
});

test('token acquisition outside provisioning retains its single-attempt behavior',async()=>{
  let calls=0;const h=tokenHarness(async()=>{calls++;throw new DOMException('Test deadline','TimeoutError');});
  await assert.rejects(h.token(h.config,'alpha-admin'),error=>error.name==='TimeoutError');
  assert.equal(calls,1);assert.deepEqual(h.delays,[]);
});

test('a transient token failure recovers before provisioning SQL and identity checks run',async()=>{
  const f=require('./fixtures.cjs');let tokenCalls=0,meCalls=0;
  const h=tokenHarness(async(url,options)=>{
    if(url.endsWith('/token')) {
      if(++tokenCalls===1) throw new DOMException('Cold token endpoint','TimeoutError');
      const user=f.users.find(u=>u.name===options.body.get('username'));
      const claims=Buffer.from(JSON.stringify({iss:'http://localhost:19080/realms/ark-dev',sub:user.subject})).toString('base64url');
      return new Response(JSON.stringify({access_token:'header.'+claims+'.signature'}),{status:200});
    }
    assert.equal(h.sql.length,1,'Identity API checks must follow provisioning');
    assert.equal(url,'http://localhost:18080/api/v1/me');
    const claims=JSON.parse(Buffer.from(options.headers.Authorization.split('.')[1],'base64url'));
    const user=f.users.find(u=>u.subject===claims.sub);meCalls++;
    return new Response(JSON.stringify({id:user.id,subject:user.subject}),{status:200});
  });
  await h.provision();assert.equal(h.sql.length,1);assert.equal(meCalls,7);assert.equal(tokenCalls,15);
  assert.deepEqual(h.delays,[1000]);
});

test('permanent token failures prevent all provisioning SQL writes',async()=>{
  let calls=0;const h=tokenHarness(async()=>{calls++;return new Response(null,{status:401});});
  await assert.rejects(h.provision(),error=>error.cause.status===401);
  assert.equal(calls,1);assert.equal(h.sql.length,0);
});
