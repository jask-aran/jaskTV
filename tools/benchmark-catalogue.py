import json, sqlite3, re, time, statistics
from pathlib import Path
p=Path('app/build/reports');p.mkdir(parents=True,exist_ok=True)
schema=json.loads(Path('app/schemas/app.opentv.data.db.OpenTvDatabase/19.json').read_text())['database']
db=sqlite3.connect(':memory:')
entities={x['tableName']:x for x in schema['entities']}
for table in ['sources','movies','canonical_movies','movie_identity_matches','movie_inventory_snapshots','movie_inventory_assets']:
 e=entities[table]; db.execute(e['createSql'].replace('${TABLE_NAME}',table))
 for ix in e['indices']: db.execute(ix['createSql'].replace('${TABLE_NAME}',table))
# Synthetic assets reproduce the observed inventory size without private provider data.
assets=({'stream_id':i,'name':f'Provider title {i} (2010)','tmdb':str(i % 62182 + 1)} for i in range(187486));titles={};rows=[];links=[]
cols=[f['columnName'] for f in entities['movies']['fields']]
for i,x in enumerate(assets,1):
 tmdb=str(x.get('tmdb','')); name=x.get('name','');sid=str(x['stream_id'])
 data={f['columnName']:('' if f['affinity']=='TEXT' else 0) if f['notNull'] else None for f in entities['movies']['fields']}
 data.update(id=i,sourceId=1,streamId=sid,name=name,tmdbId=tmdb,year=None,streamUrl='fixture',addedMillis=i)
 rows.append(tuple(data[c] for c in cols))
 if tmdb.isdigit() and int(tmdb)>0:
  n=int(tmdb);titles[n]=(n,f'Title {n:08}',f'Title {n:08}',2010,'en',None,None,None,None,8.,100,1)
  links.append((1,sid,name+'\x1f\x1f'+tmdb,n,1.,'benchmark fixture',0,''))
source_cols=[f for f in entities['sources']['fields'] if f['notNull']]
source_values=[('fixture' if f['affinity']=='TEXT' else 0) for f in source_cols]
for key,value in [('id',1),('enabled',1)]: source_values[[f['columnName'] for f in source_cols].index(key)]=value
db.execute('INSERT INTO sources ('+','.join(f['columnName'] for f in source_cols)+') VALUES ('+','.join('?' for _ in source_cols)+')',source_values)
db.executemany('INSERT INTO movies ('+','.join(cols)+') VALUES ('+','.join('?' for _ in cols)+')',rows)
db.executemany('INSERT INTO canonical_movies VALUES ('+','.join('?' for _ in range(12))+')',titles.values())
db.executemany('INSERT INTO movie_identity_matches VALUES (?,?,?,?,?,?,?,?)',links)
db.execute("INSERT INTO movie_inventory_snapshots VALUES (1,'fixture')")
db.executemany("INSERT INTO movie_inventory_assets VALUES (1,'fixture',?)",((r[1],) for r in links))
db.commit()
s=Path('app/src/main/java/app/opentv/catalogue/CanonicalMovieDao.kt').read_text()
q=re.search(r'@Query\("""(.*?)"""\)',s,re.S).group(1)
params=dict(sourceId=None,query='',languages='',includeUnknown=True,sort='TITLE')
report={'fixture_assets':len(rows),'fixture_titles':len(titles),'note':'Provider-shaped synthetic canonical metadata; no TMDB validation or device timing.'}
for label,sql in [('first_page',q+' LIMIT 48'),('deep_page',q+' LIMIT 48 OFFSET 30000'),('count','SELECT COUNT(*) FROM ('+q+')')]:
 times=[]
 for _ in range(3):
  t=time.perf_counter(); result=db.execute(sql,params).fetchall();times.append((time.perf_counter()-t)*1000)
 report[label]={'median_ms':round(statistics.median(times),2),'rows':len(result)}
report['plan']=[r[3] for r in db.execute('EXPLAIN QUERY PLAN '+q+' LIMIT 48',params)]
(p/'catalogue-benchmark.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
