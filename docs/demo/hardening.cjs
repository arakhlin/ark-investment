// Live security regression checks against this project's isolated, provisioned demo only.
// Credentials and tokens stay in memory. Restore temporary administrative changes on exit.
const assert=require('node:assert/strict');
const demo=require('../demo.cjs');
const fixtures=require('./fixtures.cjs');
async function verify() {
  const config=demo.load();
  const admin=await demo.token(config,'ark-admin');
  const clientAdmin=await demo.token(config,'alpha-admin');
  const operations=await demo.token(config,'ark-operations');
  const client=fixtures.clients[0];
  const manager=fixtures.users.find(u=>u.name==='alpha-manager');
  const ark=fixtures.users.find(u=>u.name==='ark-admin');
  const call=(token,method,path,body,status)=>demo.request(config,token,method,'/api/v1'+path,body,status);
  for(const change of [{role:'ARK_OPERATIONS',status:'ACTIVE'},{role:'ARK_ADMIN',status:'DISABLED'}])
    await call(admin,'PUT','/users/'+ark.id,change,409);
  const unverified={issuer:demo.issuer(config),subject:'nonexistent-hardening-identity',userType:'ARK',role:'ARK_ADMIN',status:'ACTIVE'};
  const missing=await call(admin,'POST','/users',unverified,409);
  const foreign=await call(admin,'POST','/users',{...unverified,issuer:'https://untrusted.invalid'},409);
  assert.equal(missing.detail,foreign.detail);
  try {
    await call(admin,'PUT','/clients/'+client.id,{name:client.name,status:'SUSPENDED'},200);
    await call(admin,'GET',`/clients/${client.id}/users`,undefined,200);
    await call(clientAdmin,'GET',`/clients/${client.id}/users`,undefined,404);
    await call(operations,'GET',`/clients/${client.id}/funds`,undefined,404);
    await call(clientAdmin,'GET',`/clients/${client.id}/funds`,undefined,404);
    await call(admin,'PUT',`/clients/${client.id}/users/${manager.id}`,{role:'CLIENT_MANAGER',status:'DISABLED'},204);
    const audit=JSON.parse(demo.sql(`SELECT json_build_object('actor',actor_user_id,'before',previous_values->>'status','after',new_values->>'status') FROM administrative_audit WHERE target_id='${manager.id}' ORDER BY occurred_at DESC LIMIT 1;`).trim());
    assert.equal(audit.actor,ark.id);assert.equal(audit.before,'ACTIVE');assert.equal(audit.after,'DISABLED');
  } finally {
    await call(admin,'PUT',`/clients/${client.id}/users/${manager.id}`,{role:'CLIENT_MANAGER',status:'ACTIVE'},204);
    await call(admin,'PUT','/clients/'+client.id,{name:client.name,status:'ACTIVE'},200);
  }
  console.log('PASS: live last-ARK-admin, unverified identity, suspended Client, and durable administrative audit checks');
}
if(require.main===module) verify().catch(error=>{console.error(error.message);process.exitCode=1;});
module.exports={verify};
