"""Optional training/evaluation/export; imports ML dependencies only when invoked."""
import argparse, hashlib, json, shutil, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['train','evaluate','export']);p.add_argument('--data',type=Path);p.add_argument('--weights',type=Path);p.add_argument('--config',type=Path,default=ROOT/'configs/training.json');p.add_argument('--output',type=Path,default=ROOT/'runs');a=p.parse_args()
    config=json.loads(a.config.read_text())
    if a.action in ('train','evaluate') and (a.data is None or not a.data.is_file()):p.error('--data must reference the generated data.yaml')
    if a.action!='train' and (a.weights is None or not a.weights.is_file()):p.error('--weights must be an actual trained checkpoint')
    if a.data:
        metadata=a.data.parent/'dataset-metadata.json'
        if not metadata.is_file():raise SystemExit('Dataset audit metadata is missing; use tools/dataset.py first.')
        if json.loads(metadata.read_text()).get('synthetic'):raise SystemExit('Geometric software fixtures cannot be used for medication model training/evaluation.')
    try:from ultralytics import YOLO
    except ImportError:raise SystemExit('Optional ML environment unavailable. See docs/TRAINING.md; no model has been trained.')
    a.output.mkdir(parents=True,exist_ok=True)
    model=YOLO(str(a.weights) if a.weights else config['architecture'])
    if a.action=='train':
        result=model.train(data=str(a.data),epochs=config['epochs'],imgsz=config['imgsz'],batch=config['batch'],seed=config['seed'],deterministic=True,workers=config['workers'],device=config['device'],project=str(a.output),name='detector',exist_ok=False,pretrained=False)
        (a.output/'training-config.json').write_text(json.dumps(config,indent=2))
    elif a.action=='evaluate':
        result=model.val(data=str(a.data),split='test',imgsz=config['imgsz'],project=str(a.output),name='evaluation',exist_ok=False)
        (a.output/'detector-evaluation.json').write_text(json.dumps({'weights_sha256':hashlib.sha256(a.weights.read_bytes()).hexdigest(),'results':result.results_dict},indent=2))
    else:
        exported=Path(model.export(format='tflite',imgsz=config['imgsz'],int8=False,nms=False,batch=1))
        # Exporters may return a directory or a single file; select float32 only.
        if exported.is_dir():
            matches=list(exported.glob('*_float32.tflite'))
            if len(matches)!=1:raise RuntimeError('Cannot select unambiguous float32 export')
            exported=matches[0]
        import tensorflow as tf
        interpreter=tf.lite.Interpreter(model_path=str(exported));interpreter.allocate_tensors();ins=interpreter.get_input_details();outs=interpreter.get_output_details()
        import numpy as np
        if len(ins)!=1 or len(outs)!=1 or list(ins[0]['shape'])!=[1,config['imgsz'],config['imgsz'],3] or len(outs[0]['shape'])!=3 or list(outs[0]['shape'])[:2]!=[1,5] or outs[0]['shape'][2]<=0 or ins[0]['dtype']!=np.float32 or outs[0]['dtype']!=np.float32:raise RuntimeError('Export incompatible with Android contract')
        output=a.output/'medicine_package.tflite';shutil.copyfile(exported,output)
        metadata={'schemaVersion':1,'class':'medicine_package','input':[1,config['imgsz'],config['imgsz'],3],'output':outs[0]['shape'].tolist(),'dtype':'float32','layout':'1x5xN','coordinates':'normalized_xywh','resize':'letterbox_114','nms_iou':0.45,'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'weights_sha256':hashlib.sha256(a.weights.read_bytes()).hexdigest(),'quality':'NOT YET EVALUATED ON DEVICE'}
        from model_contract import validate_model
        validate_model(output,metadata)
        (a.output/'medicine_package.metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
    print('Completed '+a.action+'; inspect saved artifacts. No clinical validation is implied.')
if __name__=='__main__':main()
