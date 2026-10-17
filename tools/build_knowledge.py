"""Build a deterministic offline SQLite asset. Facts must be source-checked before import."""
from pathlib import Path
import hashlib,json,sqlite3
ROOT=Path(__file__).resolve().parents[1]
def build():
    data=json.loads((ROOT/'knowledge/catalog.json').read_text())
    if data['synthetic']: raise ValueError('Synthetic data cannot enter this app asset')
    if len({m['id'] for m in data['medicines']})!=len(data['medicines']): raise ValueError('Duplicate medicine id')
    asset=ROOT/'app/src/main/assets/knowledge.db';temp=asset.with_suffix('.tmp')
    temp.unlink(missing_ok=True)
    with sqlite3.connect(temp) as db:
        db.executescript((ROOT/'knowledge/schema.sql').read_text())
        for s in data['sources']:
            if not s['url'].startswith('https://') or not s['verificationDate'] or s['verificationStatus']!='SOURCE_CHECKED_NOT_CLINICALLY_REVIEWED':raise ValueError('Missing provenance')
            db.execute('INSERT INTO Source VALUES(?,?,?,?,?,?,?)',tuple(s[k] for k in ['id','name','recordId','url','version','verificationDate','verificationStatus']))
        for m in data['medicines']:
            db.execute('INSERT INTO Medicine VALUES(?,?,?,?,?,?)',(m['id'],m['name'],m['form'],m['sourceId'],json.dumps(m['requiredTokens']),json.dumps(m['excludedTokens'])))
            for alias in m['aliases']:db.execute('INSERT INTO Alias VALUES(?,?)',(m['id'],alias))
            for i in m['ingredients']:
                db.execute('INSERT OR IGNORE INTO Ingredient VALUES(?,?)',(i['id'],i['name']))
                db.execute('INSERT INTO MedicineIngredient VALUES(?,?,?,?,?)',(m['id'],i['id'],i['strength'],i['unit'],m['sourceId']))
        for i in data['interactions']:
            a,b=sorted([i['left'],i['right']]);db.execute('INSERT INTO Interaction VALUES(?,?,?,?,?,?)',(i['id'],a,b,i['severity'],i['explanation'],i['sourceId']))
        for k,v in [('schemaVersion','1'),('datasetVersion',data['datasetVersion']),('sourceJsonSha256',hashlib.sha256((ROOT/'knowledge/catalog.json').read_bytes()).hexdigest()),('buildPolicy','Deterministic; source date retained, no invented timestamp')]:db.execute('INSERT INTO Metadata VALUES(?,?)',(k,v))
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
    temp.replace(asset)
    asset.with_suffix('.sha256').write_text(hashlib.sha256(asset.read_bytes()).hexdigest()+'\n')
    print(f'Built {len(data["medicines"])} formulations, {len(data["interactions"])} interaction rows')
if __name__=='__main__':build()
