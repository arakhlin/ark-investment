const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const f=require('./fixtures.cjs');
const realm=JSON.parse(fs.readFileSync(path.join(__dirname,'../oidc/ark-dev-realm.json'),'utf8'));

test('provider subjects and complete noninteractive profiles match application provisioning',()=>{
  for(const user of f.users) {
    const provider=realm.users.find(p=>p.username===user.name);
    assert.ok(provider);assert.equal(provider.id,user.subject);
    assert.equal(provider.enabled,true);assert.equal(provider.emailVerified,true);
    assert.match(provider.email,/^[^@]+@[^@]+$/);
    assert.ok(provider.firstName?.trim());assert.ok(provider.lastName?.trim());
    assert.deepEqual(provider.requiredActions,[]);
    assert.equal(provider.credentials,undefined,'Committed provider fixture must contain no passwords');
  }
  const audience=realm.clients[0].protocolMappers.find(p=>p.protocolMapper==='oidc-audience-mapper');
  assert.equal(audience.config['included.custom.audience'],'ark-api');
});

test('direct ownership covers all six roles and unique deterministic financial IDs',()=>{
  assert.equal(new Set(f.users.map(u=>u.role)).size,6);
  for(const user of f.users) assert.equal(user.user_type==='CLIENT',user.client_id!==null);
  const ids=[];
  for(const client of f.clients) {
    const data=f.financial(client);
    assert.equal(data.transactions.length,40);
    for(const list of Object.values(data)) for(const row of list) {
      ids.push(row.id);assert.equal(row.client_id,client.id);
    }
  }
  assert.equal(new Set(ids).size,ids.length);
  assert.equal(f.provisionSql('https://issuer.test'),f.provisionSql('https://issuer.test'));
});
