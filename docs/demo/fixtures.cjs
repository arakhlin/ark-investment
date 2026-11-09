const {createHash} = require('node:crypto');
function id(key) {
  const bytes=createHash('sha256').update(`ark-demo-v2:${key}`).digest().subarray(0,16);
  bytes[6]=(bytes[6]&15)|128; bytes[8]=(bytes[8]&63)|128;
  const h=bytes.toString('hex'); return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
const clients=['alpha','beta'].map(key=>({key,id:id(`client:${key}`),name:`Ark Demo ${key.toUpperCase()}`,status:'ACTIVE'}));
const users=[
  ['alpha-admin','CLIENT_ADMIN',clients[0].id],['alpha-manager','CLIENT_MANAGER',clients[0].id],
  ['alpha-viewer','CLIENT_REPORT_VIEWER',clients[0].id],['beta-admin','CLIENT_ADMIN',clients[1].id],
  ['ark-admin','ARK_ADMIN',null],['ark-operations','ARK_OPERATIONS',null],['ark-viewer','ARK_REPORT_VIEWER',null]
].map(([name,role,client_id],index)=>({name,role,client_id,user_type:client_id?'CLIENT':'ARK',id:id(`user:${name}`),subject:`d4e42c00-b28f-4a15-9f02-5f28783f593${index+1}`}));
const names=['Growth Equity','Infrastructure Income','Sustainable Opportunities'];
const investorNames=['Maya Patel','Daniel Chen','Sofia Rivera','James Okafor','Emma Laurent'];
const pairs=[[0,0],[0,1],[0,2],[1,0],[1,3],[1,4],[2,1],[2,4]];
const types=['CONTRIBUTION','INTEREST_INCOME','DISTRIBUTION','GENERAL_EXPENSE','MANAGEMENT_FEE'];
function financial(client) {
  const funds=names.map((name,index)=>({id:id(`${client.key}:fund:${index}`),client_id:client.id,name:`Ark Demo ${client.key} — ${name}`,description:'ark-demo-v2: trusted fixture',currency:'USD'}));
  const investors=investorNames.map((name,index)=>({id:id(`${client.key}:investor:${index}`),client_id:client.id,name:`${name} (Ark Demo ${client.key})`,email:`ark-demo-v2-${client.key}-${index}@example.com`}));
  const participations=pairs.map(([f,i])=>({id:id(`${client.key}:pair:${f}:${i}`),client_id:client.id,fund_id:funds[f].id,investor_id:investors[i].id}));
  const transactions=pairs.flatMap(([f,i],n)=>types.map((type,k)=>({id:id(`${client.key}:transaction:${n}:${k}`),client_id:client.id,fund_id:funds[f].id,investor_id:investors[i].id,
    transaction_date:['2026-01-15','2026-02-15','2026-03-15','2026-03-20','2026-03-31'][k],
    amount:[`${(n+1)*10000}.00`,`${(n+1)*125}.50`,`${(n+1)*1000}.00`,'75.25',`${(n+1)*100}.00`][k],type,description:`ark-demo-v2: ${n+1} ${type}`})));
  return {funds,investors,participations,transactions};
}
// JSON values are SQL-quoted as data; all identifiers/table names are fixed in source.
const literal=value=>`'${JSON.stringify(value).replaceAll("'","''")}'::jsonb`;
const ensure=(kind,row)=>`SELECT pg_temp.ensure_demo_fixture('${kind}',${literal(row)});\n`;
function provisionSql(issuer) {
  let sql='';
  for(const {key,...client} of clients) sql+=ensure('clients',client);
  for(const {name,...user} of users) {
    const approval={issuer,subject:user.subject,user_type:user.user_type,client_id:user.client_id};
    sql+=`INSERT INTO trusted_identities(issuer,subject,user_type,client_id) SELECT x.issuer,x.subject,x.user_type,x.client_id FROM jsonb_to_record(${literal(approval)}) AS x(issuer text,subject text,user_type text,client_id uuid) ON CONFLICT DO NOTHING;\n`;
    sql+=ensure('app_users',{...user,issuer,status:'ACTIVE'});
  }
  return sql+'COMMIT;\n';
}
function financialSql(issuer) {
  let sql='';
  for(const client of clients) {
    const data=financial(client);
    for(const kind of ['funds','investors','participations']) for(const row of data[kind]) sql+=ensure(kind==='participations'?'fund_investors':kind,row);
    for(const row of data.transactions) {
      sql+=`SELECT pg_temp.ensure_demo_transaction(${literal(row)},'${users[5].id}',${"'"+issuer.replaceAll("'","''")+"'"},'${users[5].subject}');\n`;
    }
  }
  return sql+'COMMIT;\n';
}
function totals(rows,from,to) {
  let opening=0n,credits=0n,debits=0n,count=0;
  const buckets=types.map(type=>({type,amount:0n,transactionCount:0}));
  for(const row of rows) {
    const cents=BigInt(row.amount.replace('.',''));const credit=types.indexOf(row.type)<2;
    if(from&&row.transaction_date<from) { opening+=credit?cents:-cents;continue; }
    if(to&&row.transaction_date>to) continue;
    if(credit) credits+=cents;else debits+=cents;count++;
    const bucket=buckets.find(b=>b.type===row.type);bucket.amount+=cents;bucket.transactionCount++;
  }
  const money=n=>`${n<0n?'-':''}${(n<0n?-n:n)/100n}.${String((n<0n?-n:n)%100n).padStart(2,'0')}`;
  return {openingBalance:money(opening),totalCredits:money(credits),totalDebits:money(debits),netMovement:money(credits-debits),
    closingBalance:money(opening+credits-debits),transactionCount:count,byType:buckets.map(b=>({...b,amount:money(b.amount)}))};
}
module.exports={id,clients,users,pairs,types,financial,provisionSql,financialSql,totals};
