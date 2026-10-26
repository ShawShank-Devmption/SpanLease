import importlib.util
import math
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('summarize', pathlib.Path(__file__).parents[1] / 'summarize.py')
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)


class StatisticsTest(unittest.TestCase):
    def test_zero_failure_upper_bound_uses_independent_runs(self):
        self.assertIsNone(stats.zero_failure_upper_bound(0))
        self.assertAlmostEqual(stats.zero_failure_upper_bound(1), .95)
        self.assertLess(stats.zero_failure_upper_bound(2995), .001)

    def test_bootstrap_is_reproducible_and_requires_multiple_runs(self):
        self.assertIsNone(stats.bootstrap_mean([1]))
        self.assertEqual(stats.bootstrap_mean([1, 2, 3]), stats.bootstrap_mean([1, 2, 3]))
        self.assertEqual(stats.bootstrap_mean([5, 5]), [5, 5])

    def test_percentiles_do_not_pool_runs(self):
        self.assertEqual(stats.percentile([0, 100], .99), 99)
        self.assertIsNone(stats.percentile([], .99))

    def test_running_owner_is_progress_even_with_queue(self):
        job = dict(id='j', service='A', request_id='r', remaining=1)
        queue = dict(id='q', service='A', request_id='q', remaining=1)
        entries = [dict(wall_ms=1, service='A', slot=0, transition='ASSIGN', job=job),
                   dict(wall_ms=2, service='A', slot=-1, transition='QUEUE', job=queue)]
        self.assertEqual(stats.closed_at(entries, {'A': 1}, 3), set())

    def test_positive_evidence_subset_must_itself_be_closed(self):
        owner = dict(id='owner', service='A', request_id='r', remaining=2)
        queued = dict(id='queued', service='A', request_id='r', remaining=1)
        entries = [dict(wall_ms=1, service='A', slot=0, transition='ASSIGN', job=owner),
                   dict(wall_ms=2, service='A', slot=-1, transition='CALL_ENTER', job=owner),
                   dict(wall_ms=3, service='A', slot=-1, transition='QUEUE', job=queued)]
        self.assertEqual(stats.closed_at(entries, {'A': 1}, 4), {'r/2', 'r/1'})
        self.assertFalse(stats.closed_at(entries, {'A': 1}, 4, {'r/2'}))
        self.assertTrue(stats.closed_at(entries, {'A': 1}, 4, {'r/2', 'r/1'}))


if __name__ == '__main__':
    unittest.main()
