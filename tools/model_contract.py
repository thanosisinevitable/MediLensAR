"""The same explicit contract is checked by the Unity build helper and Android startup."""
import hashlib
from pathlib import Path

def validate_metadata(metadata):
    expected={'schemaVersion':1,'class':'medicine_package','layout':'1x5xN','coordinates':'normalized_xywh','dtype':'float32','resize':'letterbox_114','nms_iou':0.45}
    if not isinstance(metadata,dict) or type(metadata.get('schemaVersion')) is not int or any(metadata.get(k)!=v for k,v in expected.items()):raise ValueError('Unsupported model metadata')
    ins=metadata.get('input');outs=metadata.get('output')
    if not isinstance(ins,list) or len(ins)!=4 or any(type(v) is not int for v in ins) or ins[0]!=1 or not 32<=ins[1]<=1024 or ins[1]!=ins[2] or ins[3]!=3:raise ValueError('Unsupported model input shape')
    if not isinstance(outs,list) or len(outs)!=3 or any(type(v) is not int for v in outs) or outs[:2]!=[1,5] or not 0<outs[2]<=100000:raise ValueError('Unsupported model output shape')
    return metadata

def validate_model(path,metadata):
    validate_metadata(metadata)
    with Path(path).open('rb') as stream:
        if stream.read(8)[4:8]!=b'TFL3':raise ValueError('Not a TFLite model')
        stream.seek(0);digest=hashlib.file_digest(stream,'sha256').hexdigest()
    if metadata.get('sha256')!=digest:raise ValueError('Model checksum mismatch')
    return metadata
