import unittest
from tools.evaluate import evaluate
class EvaluationTests(unittest.TestCase):
    def test_no_fake_empty_metrics(self):self.assertEqual(evaluate([])['false_confident_identification_rate'],'NOT YET MEASURED')
    def test_false_confident_rate(self):
        result=evaluate([{'type':'resolution','supported':False,'status':'HIGH_CONFIDENCE','predicted_id':'wrong'},{'type':'resolution','supported':False,'status':'UNRESOLVED'}]);self.assertEqual(result['false_confident_identification_rate'],.5)
    def test_known_correct(self):self.assertEqual(evaluate([{'type':'resolution','supported':True,'status':'HIGH_CONFIDENCE','predicted_id':'a','expected_id':'a'}])['known_identification_rate'],1)
    def test_known_wrong_not_success(self):self.assertEqual(evaluate([{'type':'resolution','supported':True,'status':'HIGH_CONFIDENCE','predicted_id':'b','expected_id':'a'}])['known_identification_rate'],0)
    def test_quantiles(self):
        q=evaluate([{'type':'latency','stage':'detector','ms':i} for i in range(1,101)])['latency_ms']['detector'];self.assertEqual(q,{'n':100,'p50':50,'p95':95})
    def test_negative_latency_rejected(self):
        with self.assertRaises(ValueError):evaluate([{'type':'latency','stage':'x','ms':-1}])
    def test_nan_rejected(self):
        with self.assertRaises(ValueError):evaluate([{'type':'latency','stage':'x','ms':float('nan')}])
    def test_tracking_session_isolation(self):
        rows=[{'type':'track','capture_session_id':'a','physical_package_instance_id':'x','track_id':1},{'type':'track','capture_session_id':'b','physical_package_instance_id':'x','track_id':2}];self.assertEqual(evaluate(rows)['id_switches'],0)
    def test_id_switch(self):
        rows=[{'type':'track','capture_session_id':'a','physical_package_instance_id':'x','track_id':i} for i in [1,1,2,2]];self.assertEqual(evaluate(rows)['id_switches'],1)
    def test_zero_failures_still_uncertain(self):self.assertGreater(evaluate([{'type':'resolution','supported':False,'status':'UNRESOLVED'}])['false_confident_rate_upper_95_if_zero'],.9)
