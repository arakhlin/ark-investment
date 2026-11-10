// Node.js 22+, Docker Compose v2. No npm dependencies; no token/password output.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {randomBytes}=require('node:crypto');
const {spawnSync,spawn}=require('node:child_process');
const fixtures=require('./demo/fixtures.cjs');
const root=path.resolve(__dirname,'..');
const project=process.env.DEMO_PROJECT || 'ark-demo-client-local';
assert.match(project,/^ark-demo-[a-z0-9][a-z0-9-]*$/, 'Use a dedicated DEMO_PROJECT starting with ark-demo-');
const directory=path.join(root,'.ark-demo',project);
const configFile=path.join(directory,'config.json');
const envFile=path.join(directory,'compose.env');
const secret=()=>randomBytes(24).toString('hex');
function initialize() {
  if(fs.existsSync(configFile)) return load();
  // Never regenerate credentials for already persisted provider/database state.
  assert.ok(!fs.existsSync(directory),'Incomplete local setup directory; inspect it instead of regenerating credentials');
  fs.mkdirSync(directory,{recursive:true,mode:0o700});
  const config={project,apiPort:Number(process.env.DEMO_API_PORT || 18080),oidcPort:Number(process.env.DEMO_OIDC_PORT || 19080),
    dbPassword:secret(),adminPassword:secret(),userPasswords:Object.fromEntries(fixtures.users.map(u=>[u.name,secret()]))};
  for(const port of [config.apiPort,config.oidcPort]) assert.ok(Number.isInteger(port)&&port>1024&&port<=65535,'Invalid demo port');
  assert.notEqual(config.apiPort,config.oidcPort,'API and OIDC ports must differ');
  const realm=JSON.parse(fs.readFileSync(path.join(__dirname,'oidc/ark-dev-realm.json'),'utf8'));
  for(const user of realm.users) user.credentials=[{type:'password',value:config.userPasswords[user.username],temporary:false}];
  const realmDir=path.join(directory,'realm'); fs.mkdirSync(realmDir,{mode:0o700});
  // Copy through the Docker API; no host filesystem bind mount is required.
  fs.writeFileSync(path.join(realmDir,'ark-dev-realm.json'),JSON.stringify(realm,null,2),{mode:0o600});

  fs.writeFileSync(envFile,[`DEMO_API_PORT=${config.apiPort}`,`DEMO_OIDC_PORT=${config.oidcPort}`,
    `DEMO_DB_PASSWORD=${config.dbPassword}`,`DEMO_OIDC_ADMIN_PASSWORD=${config.adminPassword}`,
    `DEMO_REALM_DIR=${realmDir}`].join('\n')+'\n',{mode:0o600});
  fs.writeFileSync(configFile,JSON.stringify(config,null,2)+'\n',{mode:0o600});
  return config;
}
function load() {
  const config=JSON.parse(fs.readFileSync(configFile,'utf8'));
  assert.equal(config.project,project,'Project configuration mismatch');
  for(const [env,key] of [['DEMO_API_PORT','apiPort'],['DEMO_OIDC_PORT','oidcPort']])
    if(process.env[env]) assert.equal(Number(process.env[env]),config[key],'Ports are fixed after initialization; choose a new project');
  return config;
}
function composeArgs(args) { return ['compose','-p',project,'--env-file',envFile,'-f',path.join(root,'docker-compose.demo.yml'),...args]; }
function compose(args,input) {
  const result=spawnSync('docker',composeArgs(args),{cwd:root,input,encoding:'utf8',maxBuffer:8*1024*1024,timeout:60000});
  if(result.error || result.status!==0) throw new Error(`Docker Compose command failed: ${args[0]}${result.stderr?'\n'+result.stderr:''}`);
  return result.stdout;
}
async function streamed(args) {
  await new Promise((resolve,reject)=>{
    const child=spawn('docker',composeArgs(args),{cwd:root,stdio:'inherit',timeout:600000});
    child.on('error',reject);child.on('exit',(code,signal)=>code===0?resolve():reject(new Error(`Demo Docker command failed or timed out (${signal||code}); inspect only this project's status`)));
  });
}
async function up() {
  initialize();
  // Host parent directory is owner-only; the provider must read its imported file after copy.
  fs.chmodSync(path.join(directory,'realm/ark-dev-realm.json'),0o644);
  await streamed(['pull','db']);
  // Keycloak augmentation is cached in its image, never in a credential-bearing data volume.
  await streamed(['build','--pull','api','keycloak']);
  compose(['create','--no-build']);
  compose(['cp',path.join(directory,'realm/ark-dev-realm.json'),'keycloak:/opt/keycloak/data/import/ark-dev-realm.json']);
  await streamed(['up','-d','--no-build','--wait','--wait-timeout','240']);
  console.log(`READY: API http://localhost:${load().apiPort}; OIDC http://localhost:${load().oidcPort}`);
}

function issuer(config) { return `http://localhost:${config.oidcPort}/realms/ark-dev`; }
async function token(config,name,timeoutMs=20000) {
  assert.ok(config.userPasswords[name],'Unknown demo user');
  const signal=AbortSignal.timeout(timeoutMs);
  try {
    const response=await fetch(issuer(config)+'/protocol/openid-connect/token',{method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:new URLSearchParams({grant_type:'password',client_id:'ark-local-cli',username:name,password:config.userPasswords[name]}),
      signal});
    if(response.status!==200) {
      await response.body?.cancel();
      throw Object.assign(new Error(`OIDC token issuance failed for ${name}: HTTP ${response.status}`),{status:response.status});
    }
    const value=await response.json(); assert.ok(value.access_token,'Provider returned no access token'); return value.access_token;
  } catch(error) {
    // Body reads can report AbortError even when our deadline caused the abort.
    if(error.name==='AbortError' && signal.aborted && signal.reason?.name==='TimeoutError')
      throw Object.assign(new Error('OIDC token request timed out',{cause:error}),{name:'TimeoutError'});
    throw error;
  }
}
// Provider health does not guarantee a cold token request completes within its deadline.
// Retry only token acquisition during provisioning, never SQL or business API writes.
async function provisionToken(config,name,timeoutMs=20000) {
  for(let attempt=1;attempt<=4;attempt++) {
    try { return await token(config,name,timeoutMs); }
    catch(error) {
      const transient=error.name==='TimeoutError' || [429,502,503,504].includes(error.status) ||
        ['ECONNREFUSED','ECONNRESET','ETIMEDOUT','EAI_AGAIN','UND_ERR_CONNECT_TIMEOUT','UND_ERR_SOCKET'].includes(error.cause?.code || error.code);
      const reason=error.status ? `HTTP ${error.status}` : error.name;
      if(!transient || attempt===4)
        throw new Error(`OIDC token acquisition for ${name} at ${issuer(config)}/protocol/openid-connect/token failed after ${attempt} attempt(s): ${reason}`,{cause:error});
      const delay=1000*2**(attempt-1);
      console.error(`WAIT: OIDC token acquisition for ${name}: ${reason}; retry ${attempt+1}/4 in ${delay}ms`);
      await new Promise(resolve=>setTimeout(resolve,delay));
    }
  }
}
async function request(config,accessToken,method,route,body,expected=200) {
  const response=await fetch(`http://localhost:${config.apiPort}${route}`,{method,
    headers:{...(accessToken?{Authorization:`Bearer ${accessToken}`}:{}) ,...(body?{'Content-Type':'application/json'}:{})},
    body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(20000)});
  assert.equal(response.status,expected,`${method} ${route}: expected ${expected}, got ${response.status}`);
  return response.status===204?null:response.json();
}
function sql(text) {
  return compose(['exec','-T','db','psql','-X','-q','-A','-t','-U','ark_demo','-d','ark_demo','-v','ON_ERROR_STOP=1'],text);
}
function support() { return fs.readFileSync(path.join(__dirname,'demo/support.sql'),'utf8'); }
async function provision() {
  const config=load();
  // Confirm actual issuer-issued identities match the credential-free source definitions.
  for(const user of fixtures.users) {
    const jwt=await provisionToken(config,user.name);
    const claims=JSON.parse(Buffer.from(jwt.split('.')[1],'base64url').toString());
    // This local check does not authorize API access; the Resource Server verifies the signature.
    assert.equal(claims.iss,issuer(config));assert.equal(claims.sub,user.subject,'Provider subject differs from bootstrap definition');
  }
  sql(support()+fixtures.provisionSql(issuer(config)));
  for(const user of fixtures.users) {
    const actual=await request(config,await provisionToken(config,user.name),'GET','/api/v1/me');
    assert.equal(actual.id,user.id);assert.equal(actual.subject,user.subject);
  }
  console.log('PASS: 2 clients, 7 verified users, all 6 roles; existing matching records preserved');
}
async function seed() {
  const config=load();
  const actual=await request(config,await token(config,'ark-operations'),'GET','/api/v1/me');
  assert.equal(actual.id,fixtures.users[5].id,'Unexpected seed actor');
  sql(support()+fixtures.financialSql(issuer(config)));
  console.log('PASS: each client has 3 funds, 5 investors, 8 participations, 40 transactions with CREATE audits');
}
async function main() {
  const command=process.argv[2];
  if(command==='start') { await up(); await provision(); }
  else if(command==='up') await up();
  else if(command==='provision') await provision();
  else if(command==='seed') await seed();
  else if(command==='verify') await require('./demo/verify.cjs').verify({config:load(),token,request,sql,fixtures});
  else if(command==='down') { load(); compose(['down']); console.log('Stopped demo project; named volumes and local credentials preserved'); }
  else if(command==='info') {
    const config=load();
    console.log(JSON.stringify({project,api:`http://localhost:${config.apiPort}`,issuer:issuer(config),
      clients:fixtures.clients,users:fixtures.users},null,2));
  }
  else if(command==='status') { load(); console.log(compose(['ps'])); }
  else if(command==='call') {
    const config=load(); const user=process.argv[3];const method=process.argv[4];const route=process.argv[5];
    assert.ok(method&&route,'Usage: node docs/demo.cjs call USER METHOD /api/v1/... [JSON] [EXPECTED_STATUS]');
    const result=await request(config,await token(config,user),method,route,process.argv[6]?JSON.parse(process.argv[6]):undefined,Number(process.argv[7]||200));
    console.log(JSON.stringify(result,null,2));
  } else throw new Error('Use start, up, provision, seed, verify, status, info, call or down');
}
if(require.main===module) main().catch(error=>{console.error(error.message);process.exitCode=1;});
module.exports={initialize,load,issuer,token,request,sql,provision,seed};
