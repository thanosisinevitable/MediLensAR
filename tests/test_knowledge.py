import hashlib,json,sqlite3,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class KnowledgeTests(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        with sqlite3.connect(ROOT/'app/src/main/assets/knowledge.db') as source: source.backup(self.db)
        self.db.execute('PRAGMA foreign_keys=ON')
    def tearDown(self):self.db.close()
    def test_integrity(self):self.assertEqual(self.db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
    def test_foreign_keys(self):self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(),[])
    def test_version(self):self.assertEqual(self.db.execute('PRAGMA user_version').fetchone()[0],1)
    def test_expanded_formulations(self):self.assertEqual(self.db.execute('SELECT COUNT(*) FROM Medicine').fetchone()[0],32)
    def test_sources_for_every_composition(self):self.assertEqual(self.db.execute('SELECT COUNT(*) FROM MedicineIngredient mi LEFT JOIN Source s ON s.id=mi.source_id WHERE s.id IS NULL').fetchone()[0],0)
    def test_no_synthetic_records(self):self.assertEqual(self.db.execute("SELECT COUNT(*) FROM Medicine WHERE id LIKE 'test%' OR name LIKE 'TEST%'").fetchone()[0],0)
    def test_provenance_not_overstated(self):self.assertEqual({r[0] for r in self.db.execute('SELECT status FROM Source')},{'SOURCE_CHECKED_NOT_CLINICALLY_REVIEWED'})
    def test_alias_lookup(self):self.assertEqual(len(self.db.execute('SELECT medicine_id FROM Alias WHERE value=?',('NITROSTAT',)).fetchall()),3)
    def test_ingredient_join(self):self.assertTrue(self.db.execute('SELECT ingredient_id FROM MedicineIngredient WHERE medicine_id=?',('nitrostat-0p4mg',)).fetchone())
    def test_unknown_interaction_no_row(self):self.assertIsNone(self.db.execute('SELECT id FROM Interaction WHERE ingredient_a=? AND ingredient_b=?',('acetaminophen','sildenafil')).fetchone())
    def test_interaction_has_provenance(self):self.assertEqual(self.db.execute('SELECT count(*) FROM Interaction i JOIN Source s ON i.source_id=s.id').fetchone()[0],1)
    def test_canonical_pair_order(self):self.assertEqual(self.db.execute('SELECT COUNT(*) FROM Interaction WHERE ingredient_a>=ingredient_b').fetchone()[0],0)
    def test_unique_alias_scope(self):
        with self.assertRaises(sqlite3.IntegrityError):self.db.execute('INSERT INTO Alias VALUES(?,?)',('nitrostat-0p4mg','NITROSTAT'))
    def test_missing_source_rejected(self):
        with self.assertRaises(sqlite3.IntegrityError):self.db.execute("INSERT INTO Medicine VALUES('bad','bad','bad','missing','[]','[]')")
    def test_sql_parameters_do_not_execute(self):self.assertEqual(self.db.execute('SELECT id FROM Medicine WHERE id=?',("' OR 1=1 --",)).fetchall(),[])
    def test_checksum(self):
        file=ROOT/'app/src/main/assets/knowledge.db'
        self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(),file.with_suffix('.sha256').read_text().strip())
    def test_source_json_matches_asset(self):self.assertEqual(self.db.execute("SELECT value FROM Metadata WHERE key='sourceJsonSha256'").fetchone()[0],hashlib.sha256((ROOT/'knowledge/catalog.json').read_bytes()).hexdigest())
    def test_source_urls_dates_and_versions(self):
        for url,version,date in self.db.execute('SELECT url,version,checked_at FROM Source'):
            self.assertTrue(url.startswith('https://'));self.assertTrue(version);self.assertRegex(date,r'^\d{4}-\d{2}-\d{2}$')
    def test_transaction_rollback(self):
        self.db.execute('BEGIN');self.db.execute("DELETE FROM Alias");self.db.rollback();self.assertGreater(self.db.execute('SELECT COUNT(*) FROM Alias').fetchone()[0],0)
