const assert=require('node:assert/strict');
async function verify({config,token,request,sql,fixtures:f}) {
  const tokens={};for(const user of f.users) tokens[user.name]=await token(config,user.name);
  let checks=0;
  async function api(user,method,route,body,expected=200) {
    const result=await request(config,tokens[user],method,route,body,expected);checks++;return result;
  }
  const root=c=>`/api/v1/clients/${c.id}`;
  await api(null,'GET','/api/v1/me',null,401);
  await request(config,'invalid-token','GET','/api/v1/me',null,401);
  for(const user of f.users) {
    const me=await api(user.name,'GET','/api/v1/me');
    assert.equal(me.id,user.id);assert.equal(me.role,user.role);assert.equal(me.clientId,user.client_id);
    const clients=await api(user.name,'GET','/api/v1/me/clients');
    assert.deepEqual(clients.map(c=>c.id).sort(),f.clients.filter(c=>!user.client_id||c.id===user.client_id).map(c=>c.id).sort());
  }
  const [a,b]=f.clients;
  await api('alpha-admin','GET',root(b)+'/funds',null,404);
  await api('alpha-admin','GET',`/api/v1/clients/${f.id('unknown-client')}/funds`,null,404);
  for(const client of f.clients) {
    const data=f.financial(client),foreign=f.financial(client===a?b:a),base=root(client);
    const admin=client===a?'alpha-admin':'beta-admin';
    const input={fundId:data.funds[0].id,investorId:data.investors[0].id,date:'2026-01-01',amount:'1.00',type:'CONTRIBUTION'};
    for(const user of [admin,'ark-operations']) {
      await api(user,'GET',base+'/funds/'+foreign.funds[0].id,null,404);
      await api(user,'POST',base+'/transactions',{...input,investorId:foreign.investors[0].id},404);
    }
    for(const user of ['ark-admin','ark-viewer',...(client===a?['alpha-viewer']:[])]) {
      await api(user,'POST',base+'/transactions',input,403);
      await api(user,'DELETE',base+'/transactions/'+data.transactions[0].id,null,403);
    }
    await api('ark-admin','GET',base+'/users');
    await api(admin,'GET',base+'/users');
    await api(admin,'PUT',base+'/users/'+f.users.find(u=>u.name===admin).id,{role:'ARK_ADMIN',status:'ACTIVE'},400);
    await api(admin,'POST',base+'/transactions',{...input,clientId:foreign.funds[0].client_id},400);
    // Compare report labels with the actual persisted entity responses, not only fixtures.
    const persistedFunds=new Map(),persistedInvestors=new Map();
    for(const fund of data.funds) {
      const persisted=await api(admin,'GET',base+'/funds/'+fund.id);
      assert.equal(persisted.name,fund.name);persistedFunds.set(fund.id,persisted);
    }
    for(const investor of data.investors) {
      const persisted=await api(admin,'GET',base+'/investors/'+investor.id);
      assert.equal(persisted.name,investor.name);persistedInvestors.set(investor.id,persisted);
    }
    const scopes=[...data.funds.map(fund=>({fund,route:base+'/funds/'+fund.id+'/report',rows:data.transactions.filter(t=>t.fund_id===fund.id)})),
      ...data.investors.flatMap(investor=>[{investor,fund:null,route:base+'/investors/'+investor.id+'/report',rows:data.transactions.filter(t=>t.investor_id===investor.id)},
        ...data.funds.map(fund=>({investor,fund,route:base+'/investors/'+investor.id+'/report?fundId='+fund.id,rows:data.transactions.filter(t=>t.investor_id===investor.id&&t.fund_id===fund.id)}))])];
    for(const scope of scopes) for(const [from,to] of [[null,null],['2026-03-01','2026-03-31'],[null,'2026-02-15'],['2026-04-01','2026-04-30']]) {
      const params=new URLSearchParams();if(from)params.set('from',from);if(to)params.set('to',to);
      const report=await api('ark-viewer','GET',scope.route+(scope.route.includes('?')?'&':'?')+params);
      assert.equal(report.fundId,scope.fund?.id ?? null);
      assert.equal(report.fundName,scope.fund ? persistedFunds.get(scope.fund.id).name : null);
      if(scope.investor) {
        assert.equal(report.investorId,scope.investor.id);
        assert.equal(report.investorName,persistedInvestors.get(scope.investor.id).name);
      }
      assert.deepEqual(report.totals,f.totals(scope.rows,from,to));
    }
    let fund,investor,transaction;
    try {
      fund=await api(admin,'POST',base+'/funds',{name:'Live verification fixture'},201);
      investor=await api('ark-operations','POST',base+'/investors',{name:'Live verifier',email:'live@example.com'},201);
      await api(admin,'PUT',base+'/funds/'+fund.id+'/investors/'+investor.id,null,204);
      const scratch={...input,fundId:fund.id,investorId:investor.id};
      transaction=await api('ark-operations','POST',base+'/transactions',scratch,201);
      await api('ark-operations','PUT',base+'/transactions/'+transaction.id,{...scratch,amount:'2.50'});
      const report=await api('ark-viewer','GET',base+'/funds/'+fund.id+'/report');assert.equal(report.fundName,fund.name);assert.equal(report.totals.closingBalance,'2.50');
      await api('ark-operations','DELETE',base+'/transactions/'+transaction.id,null,204);
      const actor=f.users.find(u=>u.name==='ark-operations');
      sql(`DO $$ BEGIN IF (SELECT count(*) FROM transaction_audit WHERE client_id='${client.id}' AND transaction_id='${transaction.id}' AND actor_user_id='${actor.id}')<>3 THEN RAISE EXCEPTION 'Audit attribution failed'; END IF; END $$;`);
      transaction=null;
    } finally {
      if(transaction) await api('ark-operations','DELETE',base+'/transactions/'+transaction.id,null,204);
      if(fund&&investor) await api(admin,'DELETE',base+'/funds/'+fund.id+'/investors/'+investor.id,null,204);
      if(investor) await api(admin,'DELETE',base+'/investors/'+investor.id,null,204);
      if(fund) await api(admin,'DELETE',base+'/funds/'+fund.id,null,204);
    }
  }
  const support=require('node:fs').readFileSync(require('node:path').join(__dirname,'support.sql'),'utf8');
  const digest=()=>sql("SELECT md5(string_agg(to_jsonb(f)::text,',' ORDER BY kind,fixture_id)) FROM ark_demo.fixtures f;").trim()+sql("SELECT md5(string_agg(to_jsonb(a)::text,',' ORDER BY id)) FROM transaction_audit a;").trim();
  const before=digest();
  for(let run=0;run<2;run++) {
    sql(support+f.provisionSql(`http://localhost:${config.oidcPort}/realms/ark-dev`));
    sql(support+f.financialSql(`http://localhost:${config.oidcPort}/realms/ark-dev`));
  }
  assert.equal(digest(),before,'Reruns changed fixture registry or audit history');
  console.log(`PASS: ${checks} HTTP checks; all six roles; 184 exact reports; cross-client isolation; API writes/audit; repeated seed`);
}
module.exports={verify};
