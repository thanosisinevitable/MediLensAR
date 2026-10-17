"""Compute real reported identification/tracking/latency metrics. No imputed values."""
import argparse,json,math
from collections import defaultdict
from pathlib import Path

def evaluate(rows):
    latency=defaultdict(list);known=correct=opens=false_confident=0;tracks={};switches=0;tracking_observations=0
    for r in rows:
        if r.get('type')=='resolution':
            if type(r.get('supported')) is not bool or r.get('status') not in ('UNRESOLVED','LOW_CONFIDENCE','MEDIUM_CONFIDENCE','HIGH_CONFIDENCE'):raise ValueError('Invalid resolution result')
            confident=r['status']=='HIGH_CONFIDENCE'
            if confident and not r.get('predicted_id'):raise ValueError('Confident result has no ID')
            if r['supported']:
                if not r.get('expected_id'):raise ValueError('Supported result has no ground truth')
                known+=1;correct+=int(confident and r.get('predicted_id')==r['expected_id'])
            else:opens+=1;false_confident+=int(confident)
        elif r.get('type')=='latency':
            value=r.get('ms')
            if type(value) not in (int,float) or value<0 or not math.isfinite(value) or not r.get('stage'):raise ValueError('Invalid latency')
            latency[r['stage']].append(value)
        elif r.get('type')=='track':
            key=(r['capture_session_id'],r['physical_package_instance_id']);previous=tracks.get(key);tracking_observations+=1
            if previous is not None and previous!=r['track_id']:switches+=1
            tracks[key]=r['track_id']
        else:raise ValueError('Unknown evaluation row type')
    report={'known_trials':known,'known_correct_high_confidence':correct,'known_identification_rate':correct/known if known else 'NOT YET MEASURED',
        'open_set_trials':opens,'false_confident_identifications':false_confident,'false_confident_identification_rate':false_confident/opens if opens else 'NOT YET MEASURED',
        'tracking_observations':tracking_observations,'id_switches':switches if tracking_observations else 'NOT YET MEASURED','latency_ms':{}}
    # One-sided exact 95% upper bound when no false positives were observed.
    if opens and not false_confident:report['false_confident_rate_upper_95_if_zero']=1-0.05**(1/opens)
    for stage,values in latency.items():
        values.sort();report['latency_ms'][stage]={'n':len(values),'p50':values[math.ceil(.5*len(values))-1],'p95':values[math.ceil(.95*len(values))-1]}
    return report

def main():
    p=argparse.ArgumentParser();p.add_argument('observations',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();rows=[json.loads(x) for x in a.observations.read_text().splitlines() if x.strip()];report=evaluate(rows);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
