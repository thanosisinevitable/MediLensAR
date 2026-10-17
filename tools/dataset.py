"""IndiMed-AR manifest validation, connected-component split, and YOLO conversion.
No random frame split; sessions AND physical package instances are indivisible.
"""
from __future__ import annotations
import argparse, hashlib, json, math, random, shutil
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath

def load(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]

def validate(rows,root,check_images=True):
    root=Path(root).resolve();ids=set();paths=set();hashes=defaultdict(list);support_by_medicine={};medicine_by_instance={}
    for row in rows:
        for key in ('image_id','image_path','capture_session_id','condition_group','width','height','objects'):
            if key not in row: raise ValueError('Missing '+key)
        if not row['image_id'] or row['image_id'] in ids: raise ValueError('Duplicate/empty image ID')
        ids.add(row['image_id']);p=PurePosixPath(row['image_path'])
        if p.is_absolute() or '..' in p.parts or '\\' in str(p) or ':' in str(p): raise ValueError('Unsafe image path')
        path=(root/str(p)).resolve()
        if not path.is_relative_to(root): raise ValueError('Image escapes root')
        if str(p) in paths: raise ValueError('Repeated image path')
        paths.add(str(p))
        if not row['capture_session_id'] or not row['condition_group']: raise ValueError('Missing capture metadata')
        if type(row['width']) is not int or type(row['height']) is not int or min(row['width'],row['height'])<=0: raise ValueError('Invalid dimensions')
        for obj in row['objects']:
            if not obj.get('medicine_id') or not obj.get('physical_package_instance_id') or type(obj.get('supported')) is not bool: raise ValueError('Missing object identity/support metadata')
            medicine=obj['medicine_id'];instance=obj['physical_package_instance_id']
            if medicine in support_by_medicine and support_by_medicine[medicine]!=obj['supported']:raise ValueError('Inconsistent support status for medicine')
            if instance in medicine_by_instance and medicine_by_instance[instance]!=medicine:raise ValueError('Physical instance assigned to different medicines')
            support_by_medicine[medicine]=obj['supported'];medicine_by_instance[instance]=medicine
            if obj.get('class')!='medicine_package': raise ValueError('Only medicine_package is supported')
            box=obj.get('bbox',[])
            if len(box)!=4 or any(type(v) not in (int,float) or not math.isfinite(v) for v in box): raise ValueError('Invalid bounding box')
            x,y,w,h=box
            if min(x,y)<0 or min(w,h)<=0 or x+w>row['width'] or y+h>row['height']: raise ValueError('Bounding box outside image')
        if check_images:
            if not path.is_file(): raise ValueError('Missing image: '+str(p))
            hashes[hashlib.sha256(path.read_bytes()).hexdigest()].append(row['image_id'])
    return hashes

def split(rows,seed=42,held_out_conditions=(),hashes=None):
    if not rows: raise ValueError('Empty dataset')
    parent=list(range(len(rows)))
    def find(i):
        while parent[i]!=i: parent[i]=parent[parent[i]];i=parent[i]
        return i
    def union(a,b): parent[find(a)]=find(b)
    owners={};by_id={r['image_id']:i for i,r in enumerate(rows)}
    for i,row in enumerate(rows):
        keys=['session:'+row['capture_session_id']]+['instance:'+o['physical_package_instance_id'] for o in row['objects']]
        for key in keys:
            if key in owners: union(i,owners[key])
            else: owners[key]=i
    for same in (hashes or {}).values():
        for image_id in same[1:]: union(by_id[same[0]],by_id[image_id])
    groups=defaultdict(list)
    for i,row in enumerate(rows):groups[find(i)].append(row)
    groups=list(groups.values());groups.sort(key=lambda g:min(r['image_id'] for r in g));random.Random(seed).shuffle(groups)
    result={};regular=[]
    for group in groups:
        # Mixed scenes with an unsupported object belong entirely to the open-set partition.
        if any(any(not o['supported'] for o in r['objects']) for r in group): name='test_open_set'
        elif any(r['condition_group'] in held_out_conditions for r in group): name='test_conditions'
        else: regular.append(group);continue
        for r in group: result[r['image_id']]=name
    n=len(regular)
    if n<3: raise ValueError('Need at least three independent supported capture/instance groups after holdouts')
    val=max(1,round(n*.15));test=max(1,round(n*.15))
    for i,g in enumerate(regular):
        name='val' if i<val else 'test_known' if i<val+test else 'train'
        for r in g:result[r['image_id']]=name
    audit(rows,result,hashes,held_out_conditions)
    return {'schemaVersion':1,'seed':seed,'held_out_conditions':list(held_out_conditions),'assignments':dict(sorted(result.items())),'counts':dict(Counter(result.values())),'note':'Connected session/instance/exact-image groups. Near-duplicate image audit still required.'}

def audit(rows,assignments,hashes=None,held_out_conditions=()):
    if set(assignments)!={r['image_id'] for r in rows}: raise ValueError('Split membership mismatch')
    keys=defaultdict(set)
    for row in rows:
        name=assignments[row['image_id']]
        if name not in ('train','val','test_known','test_conditions','test_open_set'): raise ValueError('Invalid partition')
        if any(not o['supported'] for o in row['objects']) and name!='test_open_set': raise ValueError('Unsupported package leaked outside open set')
        if row['condition_group'] in held_out_conditions and name not in ('test_conditions','test_open_set'):raise ValueError('Held-out condition leaked into development/known partition')
        keys['session:'+row['capture_session_id']].add(name)
        for o in row['objects']:keys['instance:'+o['physical_package_instance_id']].add(name)
    for digest,ids in (hashes or {}).items():keys['hash:'+digest].update(assignments[i] for i in ids)
    if any(len(v)>1 for v in keys.values()): raise ValueError('Dataset leakage across partitions')

def export_yolo(rows,root,split_data,destination):
    hashes=validate(rows,root);audit(rows,split_data['assignments'],hashes,split_data.get('held_out_conditions',()))
    destination=Path(destination)
    if destination.exists() and any(destination.iterdir()): raise ValueError('YOLO destination must be empty to prevent stale-file leakage')
    for row in rows:
        name=split_data['assignments'][row['image_id']];stem=hashlib.sha256(row['image_id'].encode()).hexdigest()[:20]
        src=Path(root)/row['image_path'];images=destination/'images'/name;labels=destination/'labels'/name;images.mkdir(parents=True,exist_ok=True);labels.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(src,images/(stem+src.suffix.lower()))
        lines=[]
        for obj in row['objects']:
            x,y,w,h=obj['bbox'];lines.append(f"0 {(x+w/2)/row['width']:.8f} {(y+h/2)/row['height']:.8f} {w/row['width']:.8f} {h/row['height']:.8f}")
        (labels/(stem+'.txt')).write_text('\n'.join(lines)+'\n')
    # JSON is valid YAML and avoids another dependency for a tiny configuration.
    config={'path':str(destination.resolve()),'train':'images/train','val':'images/val','test':'images/test_known','names':{0:'medicine_package'}}
    (destination/'data.yaml').write_text(json.dumps(config,indent=2)+'\n')
    (destination/'dataset-metadata.json').write_text(json.dumps({'synthetic':any(r.get('synthetic',False) for r in rows),'manifest_sha256':hashlib.sha256(json.dumps(rows,sort_keys=True).encode()).hexdigest(),'split':split_data},indent=2)+'\n')

def main():
    p=argparse.ArgumentParser();p.add_argument('manifest',type=Path);p.add_argument('--root',type=Path,required=True);p.add_argument('--split',type=Path,required=True);p.add_argument('--held-out-condition',action='append',default=[]);p.add_argument('--seed',type=int,default=42);p.add_argument('--yolo',type=Path);a=p.parse_args()
    rows=load(a.manifest);hashes=validate(rows,a.root);result=split(rows,a.seed,a.held_out_condition,hashes);a.split.parent.mkdir(parents=True,exist_ok=True);a.split.write_text(json.dumps(result,indent=2)+'\n')
    if a.yolo:export_yolo(rows,a.root,result,a.yolo)
    print(json.dumps(result['counts']))
if __name__=='__main__':main()
