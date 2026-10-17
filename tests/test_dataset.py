import copy,tempfile,unittest
from pathlib import Path
from tools.dataset import audit,split,validate,export_yolo

def rows():
    return [{'image_id':f'image-{i}','image_path':f'image-{i}.png','capture_session_id':f'session-{i}','condition_group':'glare' if i==8 else 'normal','width':100,'height':100,'objects':[{'medicine_id':'unknown' if i==9 else 'test-a','supported':i!=9,'physical_package_instance_id':f'package-{i}','class':'medicine_package','bbox':[10,10,50,50]}]} for i in range(10)]
class DatasetTests(unittest.TestCase):
    def test_valid_metadata(self):self.assertEqual(validate(rows(),'.',False),{})
    def test_box_outside(self):
        r=rows();r[0]['objects'][0]['bbox']=[90,0,50,50]
        with self.assertRaises(ValueError):validate(r,'.',False)
    def test_nan_box(self):
        r=rows();r[0]['objects'][0]['bbox'][0]=float('nan')
        with self.assertRaises(ValueError):validate(r,'.',False)
    def test_path_traversal(self):
        for path in ('../secret.png','/tmp/image.png','C:/image.png','..\\secret.png'):
            r=rows();r[0]['image_path']=path
            with self.subTest(path=path),self.assertRaises(ValueError):validate(r,'.',False)
    def test_duplicate_ids(self):
        r=rows();r.append(r[0])
        with self.assertRaises(ValueError):validate(r,'.',False)
    def test_one_class_enforced(self):
        r=rows();r[0]['objects'][0]['class']='brand_a'
        with self.assertRaises(ValueError):validate(r,'.',False)
    def test_reproducible_split(self):self.assertEqual(split(rows()),split(rows()))
    def test_open_set(self):self.assertEqual(split(rows())['assignments']['image-9'],'test_open_set')
    def test_condition_holdout(self):self.assertEqual(split(rows(),held_out_conditions=['glare'])['assignments']['image-8'],'test_conditions')
    def test_session_atomicity(self):
        r=rows();r[1]['capture_session_id']=r[0]['capture_session_id'];a=split(r)['assignments'];self.assertEqual(a['image-0'],a['image-1'])
    def test_instance_atomicity(self):
        r=rows();r[1]['objects'][0]['physical_package_instance_id']=r[0]['objects'][0]['physical_package_instance_id'];a=split(r)['assignments'];self.assertEqual(a['image-0'],a['image-1'])
    def test_transitive_group(self):
        r=rows();r[1]['capture_session_id']=r[0]['capture_session_id'];r[2]['objects'][0]['physical_package_instance_id']=r[1]['objects'][0]['physical_package_instance_id'];a=split(r)['assignments'];self.assertEqual(a['image-0'],a['image-2'])
    def test_mixed_scene_holdout(self):
        r=rows();r[0]['objects'].append(copy.deepcopy(r[9]['objects'][0]));a=split(r)['assignments'];self.assertEqual(a['image-0'],'test_open_set');self.assertEqual(a['image-9'],'test_open_set')
    def test_exact_duplicate_hash_group(self):
        a=split(rows(),hashes={'same':['image-0','image-4']})['assignments'];self.assertEqual(a['image-0'],a['image-4'])
    def test_leakage_audit(self):
        r=rows();r[1]['capture_session_id']=r[0]['capture_session_id'];a=split(r)['assignments'];a['image-0']='val';a['image-1']='train'
        with self.assertRaises(ValueError):audit(r,a)
    def test_too_few_groups(self):
        with self.assertRaises(ValueError):split(rows()[:2])
    def test_unknown_train_rejected(self):
        r=rows();a=split(r)['assignments'];a['image-9']='train'
        with self.assertRaises(ValueError):audit(r,a)
    def test_empty_dataset(self):
        with self.assertRaises(ValueError):split([])
    def test_missing_image_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):validate(rows(),tmp)
    def test_yolo_export_normalized_and_no_stale_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);r=rows()
            for i,row in enumerate(r):(root/row['image_path']).write_bytes(bytes([i]))
            out=root/'yolo';s=split(r);export_yolo(r,root,s,out)
            labels=list((out/'labels').rglob('*.txt'));self.assertEqual(len(labels),len(r));self.assertEqual(labels[0].read_text().strip(),'0 0.35000000 0.35000000 0.50000000 0.50000000')
            self.assertTrue((out/'dataset-metadata.json').is_file())
            with self.assertRaises(ValueError):export_yolo(r,root,s,out)
