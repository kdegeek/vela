"""HTTP integration tests; every listener is ephemeral and closed in tearDown."""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import http.client
import json
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

import fixture
from scenarios import EXTERNAL_TOKEN, SCENARIOS, TOKEN
from validation import MAX_BODY


class FixtureHTTPTests(unittest.TestCase):
    def setUp(self):
        self.server = fixture.FixtureServer(0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': 0.01})
        self.thread.start()
        self.port = self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.assertFalse(self.thread.is_alive())

    def request(self, method, path, body=None, token=TOKEN, headers=None, raw=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        opts = {'Content-Type': 'application/json'}
        if token is not None:
            opts['X-Vela-Token'] = token
        opts.update(headers or {})
        data = json.dumps(body).encode() if body is not None else raw
        try:
            conn.request(method, path, body=data, headers=opts)
            response = conn.getresponse()
            payload = response.read()
            if response.getheader('Content-Type', '').startswith('application/json'):
                payload = json.loads(payload)
            return response.status, payload, dict(response.getheaders())
        finally:
            conn.close()

    def ok(self, method, path, body=None):
        status, value, headers = self.request(method, path, body)
        self.assertEqual(status, 200, value)
        self.assertEqual(headers['Cache-Control'], 'no-store')
        return value

    def refuse(self, status, method, path, body=None, **kwargs):
        actual, value, _ = self.request(method, path, body, **kwargs)
        self.assertEqual(actual, status, value)

    def scenario(self, name):
        return self.ok('POST', '/__fixture/scenario', {'scenario': name})

    def owner(self):
        value = self.ok('GET', '/api/profiles')
        return value, value['profiles'][0]

    @staticmethod
    def draft(profile):
        return {k: deepcopy(profile[k]) for k in ('displayName', 'kind', 'preferences', 'restrictions')}

    def raw_request(self, lines, body=b''):
        with socket.create_connection(('127.0.0.1', self.port), timeout=5) as connection:
            connection.sendall(('\r\n'.join(lines) + '\r\n\r\n').encode() + body)
            response = http.client.HTTPResponse(connection)
            response.begin()
            return response.status, response.read()

    def test_real_asset_exact_bytes_headers_and_no_other_files(self):
        status, html, headers = self.request('GET', '/', token=None)
        self.assertEqual(status, 200)
        self.assertEqual(html, fixture.PHONE_HTML.read_bytes())
        self.assertEqual(headers['X-Vela-Fixture'], 'synthetic-ram-only')
        self.assertEqual(headers['Referrer-Policy'], 'no-referrer')
        self.assertIn("connect-src 'self'", headers['Content-Security-Policy'])
        for path in ('/phone.html', '/README.md', '/assets/phone.html', '/favicon.ico', '/v1/chat/completions'):
            self.refuse(404, 'GET', path)
        for path in ('/../README.md', '/%2e%2e/README.md', '//outside.test/x', 'http://outside.test/x', '/api\\state'):
            self.refuse(400, 'GET', path)

    def test_asset_is_read_fresh_and_failure_is_sanitized(self):
        # Test-only path substitution never modifies a repo asset or the served app UI.
        with tempfile.TemporaryDirectory(prefix='vela-wp5-', dir='/tmp') as directory:
            asset = Path(directory) / 'asset.html'
            with patch.object(fixture, 'PHONE_HTML', asset):
                self.refuse(503, 'GET', '/')
                asset.write_bytes(b'first')
                self.assertEqual(self.request('GET', '/')[1], b'first')
                asset.write_bytes(b'second')
                self.assertEqual(self.request('GET', '/')[1], b'second')

    def test_auth_required_for_data_mutations_and_controls(self):
        for method, path, body in [('GET', '/api/state', None), ('GET', '/api/profiles', None),
                                   ('POST', '/api/stop', {}), ('POST', '/__fixture/reset', {})]:
            for token in (None, '', 'incorrect'):
                self.refuse(401, method, path, body, token=token)
        status, _ = self.raw_request(['GET /api/state HTTP/1.1', f'Host: 127.0.0.1:{self.port}',
                                     f'X-Vela-Token: {TOKEN}', f'X-Vela-Token: {TOKEN}'])
        self.assertEqual(status, 401)
        self.assertFalse(self.ok('GET', '/api/state')['running'])

    def test_loopback_host_origin_no_cors_and_no_bind_override(self):
        self.assertEqual(self.server.server_address[0], '127.0.0.1')
        self.refuse(403, 'GET', '/', headers={'Host': 'outside.test'})
        self.refuse(403, 'POST', '/api/stop', {}, headers={'Origin': 'https://outside.test'})
        self.refuse(403, 'POST', '/api/stop', {}, headers={'Sec-Fetch-Site': 'cross-site'})
        self.refuse(405, 'OPTIONS', '/api/state')
        self.assertNotIn('Access-Control-Allow-Origin', self.request('GET', '/api/state')[2])
        self.assertEqual(self.request('GET', '/api/state', headers={'Origin': f'http://127.0.0.1:{self.port}'})[0], 200)

    def test_all_scenarios_have_route_shaped_state_and_stable_polling(self):
        required = {'settings', 'agentReady', 'running', 'pending', 'pendingId', 'lines', 'tasks', 'device'}
        for name in SCENARIOS:
            with self.subTest(name=name):
                self.scenario(name)
                state = self.ok('GET', '/api/state')
                self.assertEqual(set(state), required)
                self.assertEqual(self.ok('GET', '/api/state'), state)
                self.assertEqual(state['running'], name in ('working', 'pending'))
                self.assertEqual(state['pendingId'] is not None, name == 'pending')
                self.assertIn('SYNTHETIC', state['device']['model'])
                self.assertEqual(len(self.ok('GET', '/api/routines')['routines']), 4)
                self.assertNotIn('memories', self.ok('GET', '/api/profiles'))
        self.scenario('error')
        self.assertIn('error', [line['kind'] for line in self.ok('GET', '/api/state')['lines']])
        self.assertIn('failure', self.ok('POST', '/api/connections/test')['AI'])

    def test_exact_approval_single_use_and_reset_cannot_replay(self):
        self.scenario('pending')
        prompt = self.ok('GET', '/api/state')['pendingId']
        self.refuse(400, 'POST', '/api/answer', {'allow': 'false', 'promptId': prompt})
        self.refuse(400, 'POST', '/api/answer', {'allow': True})
        self.refuse(409, 'POST', '/api/answer', {'allow': False, 'promptId': 'other'})
        self.assertEqual(self.ok('GET', '/api/state')['pendingId'], prompt)
        self.ok('POST', '/api/answer', {'allow': False, 'promptId': prompt})
        self.refuse(409, 'POST', '/api/answer', {'allow': True, 'promptId': prompt})
        self.assertFalse(self.ok('GET', '/api/state')['running'])
        self.ok('POST', '/__fixture/reset', {})
        fresh = self.ok('GET', '/api/state')['pendingId']
        self.assertNotEqual(fresh, prompt)
        self.refuse(409, 'POST', '/api/answer', {'allow': True, 'promptId': prompt})
        self.ok('POST', '/api/answer', {'allow': True, 'promptId': fresh})
        self.assertIn('RAM only', self.ok('GET', '/api/state')['lines'][-1]['text'])

    def test_concurrent_approval_only_one_wins(self):
        self.scenario('pending')
        prompt = self.ok('GET', '/api/state')['pendingId']
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda allow: self.request('POST', '/api/answer', {'allow': allow, 'promptId': prompt})[0], (True, False)))
        self.assertEqual(sorted(results), [200, 409])

    def test_working_guards_stop_clear_and_ask(self):
        self.scenario('working')
        profile_list, profile = self.owner()
        for path, body in [('/api/remote', {'key': 'select'}), ('/api/settings', {'model': 'Synthetic'}),
                           ('/api/profiles', {'revision': profile_list['revision'], 'profile': self.draft(profile)})]:
            self.refuse(409, 'POST', path, body)
        self.refuse(409, 'PUT', '/api/layout', self.ok('GET', '/api/layout'))
        self.ok('POST', '/api/stop')
        for key in ('up', 'down', 'left', 'right', 'select', 'back', 'home'):
            self.ok('POST', '/api/remote', {'key': key})
        self.refuse(400, 'POST', '/api/remote', {'key': 'power'})
        task = self.ok('POST', '/api/ask', {'text': 'Synthetic new request'})
        self.assertEqual(task['taskId'], self.ok('GET', '/api/state')['tasks'][-1]['id'])
        self.ok('POST', '/api/history/clear')
        state = self.ok('GET', '/api/state')
        self.assertEqual((state['running'], state['tasks'], state['lines']), (False, [], []))

    def test_profiles_full_drafts_revisions_and_atomic_conflict(self):
        snapshot, profile = self.owner()
        draft = self.draft(profile)
        draft['displayName'] = 'Synthetic edited owner'
        draft['preferences']['autoplayNext'] = True
        body = dict(revision=snapshot['revision'], profile=draft)
        path = '/api/profiles/' + profile['id']
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.request('PUT', path, body)[0], range(2)))
        self.assertEqual(sorted(results), [200, 409])
        current, saved = self.owner()
        self.assertEqual(current['revision'], snapshot['revision'] + 1)
        self.assertEqual(self.draft(saved), draft)
        self.assertEqual(saved['restrictions'], profile['restrictions'])
        self.assertEqual(self.ok('GET', '/api/state')['lines'], [])

    def test_create_switch_delete_and_memory_safeguards(self):
        snapshot, owner = self.owner()
        revision = snapshot['revision']
        self.refuse(409, 'DELETE', '/api/profiles/' + owner['id'], {'revision': revision, 'deleteOwnedMemories': True})
        other = snapshot['profiles'][1]
        self.refuse(409, 'DELETE', '/api/profiles/' + other['id'], {'revision': revision, 'deleteOwnedMemories': False})
        created = self.ok('POST', '/api/profiles', {'revision': revision, 'profile': {'displayName': 'Synthetic new adult'}})
        self.assertEqual(created['value']['preferences']['audioLanguages'], [])
        self.ok('POST', '/api/ask', {'text': 'Synthetic task'})
        switched = self.ok('POST', '/api/profiles/active', {'revision': created['revision'], 'profileId': created['value']['id']})
        self.assertFalse(self.ok('GET', '/api/state')['running'])
        deleted = self.ok('DELETE', '/api/profiles/' + other['id'], {'revision': switched['revision'], 'deleteOwnedMemories': True})
        self.assertEqual(deleted['value'], other['id'])
        self.refuse(404, 'GET', '/api/profiles/' + other['id'] + '/export')
        self.assertEqual(self.ok('POST', '/api/household/reload', {})['revision'], deleted['revision'])

    def test_strict_profile_types_unknown_fields_and_child_rules(self):
        snapshot, profile = self.owner()
        bad_drafts = [{'displayName': 'Child', 'kind': 'child'},
                      {'displayName': 'Synthetic', 'preferences': {'autoplayNext': 'true'}},
                      {'displayName': 'Synthetic', 'restrictions': {'maxAgeRating': True}},
                      {'displayName': 'Synthetic', 'ownerConfirmed': True}]
        for draft in bad_drafts:
            self.refuse(400, 'POST', '/api/profiles', {'revision': snapshot['revision'], 'profile': draft})
        for revision in ('7', True, 7.0, -1, 9007199254740992):
            self.refuse(400, 'POST', '/api/profiles', {'revision': revision, 'profile': self.draft(profile)})
        self.assertEqual(self.ok('GET', '/api/profiles'), snapshot)

    def test_long_memory_paging_expected_revision_and_export_scope(self):
        self.scenario('longcontent')
        snapshot, owner = self.owner()
        base = '/api/profiles/' + owner['id']
        first = self.ok('GET', base + '/memories?offset=0&limit=20')
        second = self.ok('GET', base + f'/memories?offset=20&limit=20&expectedRevision={first["revision"]}')
        self.assertEqual((first['total'], len(first['entries']), len(second['entries'])), (25, 20, 5))
        self.assertFalse(set(m['id'] for m in first['entries']) & set(m['id'] for m in second['entries']))
        self.assertTrue(any(len(m['text']) > 1000 for m in second['entries']))
        export = self.ok('GET', base + '/export')
        self.assertEqual(len(export['memories']), 25)
        self.assertTrue(all(m['profileId'] == owner['id'] for m in export['memories']))
        self.assertTrue(any(not m['shareWithAgent'] for m in export['memories']))
        self.ok('POST', '/__fixture/conflict', {'target': 'household'})
        self.refuse(409, 'GET', base + f'/memories?expectedRevision={first["revision"]}')
        for query in ('limit=0', 'limit=101', 'offset=-1', 'limit=1&limit=2', 'unknown=1', 'offset=1.5'):
            self.refuse(400, 'GET', base + '/memories?' + query)

    def test_memory_crud_trusted_source_and_stale_rejection(self):
        snapshot, owner = self.owner()
        base = '/api/profiles/' + owner['id'] + '/memories'
        body = dict(revision=snapshot['revision'], kind='preference', text='Synthetic exact words', tags=['synthetic'], shareWithAgent=False)
        self.refuse(400, 'POST', base, dict(body, source='owner_tv'))
        saved = self.ok('POST', base, body)
        self.assertEqual(saved['value']['source'], 'owner_phone')
        self.assertFalse(saved['value']['shareWithAgent'])
        path = base + '/' + saved['value']['id']
        self.refuse(409, 'PUT', path, body)
        edited = self.ok('PUT', path, dict(body, revision=saved['revision'], text='Synthetic edited words'))
        removed = self.ok('DELETE', path, {'revision': edited['revision']})
        self.refuse(404, 'PUT', path, dict(body, revision=removed['revision']))
        self.refuse(400, 'POST', base + '/clear', {'revision': removed['revision'], 'ownerConfirmed': False})
        self.ok('POST', base + '/clear', {'revision': removed['revision'], 'ownerConfirmed': True})
        self.assertEqual(self.ok('GET', base)['total'], 0)

    def test_all_schedule_writes_trusted_owner_and_routine_revisions(self):
        snapshot = self.ok('GET', '/api/routines')
        active = self.ok('GET', '/api/profiles')['activeProfileId']
        for routine in snapshot['routines']:
            spec = dict(routine['spec'])
            self.refuse(400, 'POST', '/api/routines', {'spec': spec})  # Owner cannot be client supplied.
            spec.pop('ownerProfileId')
            created = self.ok('POST', '/api/routines', {'spec': spec})
            self.assertEqual(created['spec']['ownerProfileId'], active)
            self.assertEqual(created['spec']['schedule'], spec['schedule'])
            path = '/api/routines/' + created['id']
            updated = self.ok('PUT', path, {'revision': created['revision'], 'spec': dict(spec, name='Synthetic edited')})
            self.refuse(409, 'POST', path + '/enabled', {'revision': created['revision'], 'enabled': False})
            disabled = self.ok('POST', path + '/enabled', {'revision': updated['revision'], 'enabled': False})
            self.assertFalse(disabled['spec']['enabled'])
            self.ok('DELETE', path, {'revision': disabled['revision']})

    def test_routine_idempotency_cancel_restart_and_definition_conflicts(self):
        routine = self.ok('GET', '/api/routines')['routines'][0]
        path = '/api/routines/' + routine['id']
        first = self.ok('POST', path + '/run', {'requestKey': 'synthetic-deliberate-1'})
        self.assertEqual(self.ok('POST', path + '/run', {'requestKey': 'synthetic-deliberate-1'}), first)
        self.refuse(409, 'POST', path + '/run', {'requestKey': 'synthetic-deliberate-2'})
        canceled = self.ok('POST', '/api/routine-runs/' + first['id'] + '/cancel', {})
        self.assertEqual(canceled['state'], 'CANCELED')
        self.assertEqual(self.ok('POST', path + '/run', {'requestKey': 'synthetic-deliberate-1'})['id'], first['id'])
        restart_path = '/api/routine-runs/' + first['id'] + '/restart'
        self.refuse(400, 'POST', restart_path, {'requestKey': 'synthetic-deliberate-1'})
        restart = self.ok('POST', restart_path, {'requestKey': 'synthetic-restart-1'})
        self.assertEqual(restart['restartedFrom'], first['id'])
        self.assertEqual(restart['source'], 'RESTART')
        self.assertEqual(self.ok('POST', restart_path, {'requestKey': 'synthetic-restart-1'}), restart)
        self.refuse(400, 'POST', path + '/run', {'requestKey': 'synthetic-restart-1'})
        self.ok('POST', '/__fixture/conflict', {'target': 'routine', 'id': routine['id']})
        self.refuse(409, 'DELETE', path, {'revision': routine['revision']})
        self.ok('DELETE', path, {'revision': routine['revision'] + 1})
        history = self.ok('GET', '/api/routines')['history']
        self.assertEqual(next(r for r in history if r['id'] == restart['id'])['state'], 'CANCELED')
        self.refuse(404, 'POST', restart_path, {'requestKey': 'after-delete'})

    def test_queue_remains_available_while_busy_and_stale_binding_fails_closed(self):
        self.scenario('working')
        routine = self.ok('GET', '/api/routines')['routines'][0]
        path = '/api/routines/' + routine['id'] + '/run'
        run = self.ok('POST', path, {'requestKey': 'synthetic-queue'})
        self.assertEqual(run['state'], 'QUEUED')
        profiles, _ = self.owner()
        self.ok('POST', '/api/profiles/active', {'revision': profiles['revision'], 'profileId': profiles['profiles'][1]['id']})
        self.ok('POST', '/api/routine-runs/' + run['id'] + '/cancel', {})
        failed = self.ok('POST', path, {'requestKey': 'synthetic-stale-binding'})
        self.assertEqual(failed['state'], 'FAILED')
        self.assertEqual(failed['ownerProfileId'], routine['spec']['ownerProfileId'])

    def test_external_token_only_on_enable_and_revoke_during_busy(self):
        self.assertFalse(self.ok('GET', '/api/external-control')['credentialPresent'])
        self.refuse(400, 'POST', '/api/external-control/enable', {'ownerConfirmed': 'true'})
        provision = self.ok('POST', '/api/external-control/enable', {'ownerConfirmed': True})
        self.assertEqual(provision['token'], EXTERNAL_TOKEN)
        self.assertNotIn('token', self.ok('GET', '/api/external-control'))
        self.assertNotIn(EXTERNAL_TOKEN, json.dumps(self.ok('GET', '/api/state')))
        self.refuse(409, 'POST', '/api/external-control/enable', {'ownerConfirmed': True})
        self.ok('POST', '/api/ask', {'text': 'Synthetic busy task'})
        self.assertFalse(self.ok('POST', '/api/external-control/revoke', {})['enabled'])
        self.assertTrue(self.ok('GET', '/api/state')['running'])  # Does not stop unrelated phone task.

    def test_settings_redaction_layout_roundtrip_and_atomic_invalid_write(self):
        self.ok('POST', '/api/settings', {'model': 'Synthetic edited', 'apiKey': 'PUBLIC-synthetic', 'highContrast': True})
        settings = self.ok('GET', '/api/state')['settings']
        self.assertEqual(settings['apiKey'], '••••••')
        self.assertTrue(settings['highContrast'])
        self.refuse(400, 'POST', '/api/settings', {'apiKey': 'not-fixture-input'})
        self.assertEqual(self.ok('GET', '/api/state')['settings'], settings)
        layout = self.ok('GET', '/api/layout')
        layout['rows'].reverse()
        self.ok('PUT', '/api/layout', layout)
        self.assertEqual(self.ok('GET', '/api/layout'), layout)
        self.refuse(400, 'PUT', '/api/layout', {'version': 1, 'rows': []})
        self.refuse(400, 'PUT', '/api/layout', {'version': 1, 'rows': [layout['rows'][0]] * 2})
        self.assertEqual(self.ok('GET', '/api/layout'), layout)
        self.assertEqual(len(self.ok('GET', '/api/apps')['apps']), 2)

    def test_fault_recovery_reset_and_unknown_controls(self):
        before, profile = self.owner()
        for status in (503, 401):
            self.ok('POST', '/__fixture/poll-fault', {'status': status})
            self.refuse(status, 'GET', '/api/state')
        self.ok('POST', '/__fixture/poll-fault', {'status': None})
        self.ok('GET', '/api/state')
        self.ok('POST', '/__fixture/reset', {})
        self.refuse(409, 'PUT', '/api/profiles/' + profile['id'], {'revision': before['revision'], 'profile': self.draft(profile)})
        self.refuse(400, 'POST', '/__fixture/scenario', {'scenario': 'unknown'})
        self.refuse(400, 'POST', '/__fixture/reset', {'extra': True})
        self.refuse(404, 'POST', '/__fixture/unknown', {})
        self.assertEqual(self.ok('GET', '/__fixture/state')['scenario'], 'idle')

    def test_malformed_bodies_duplicate_keys_deep_json_and_strict_unicode(self):
        bad = [b'{', b'[]', b'{"text":"a","text":"b"}', b'{"text":NaN}',
               b'{"text":"\xff"}', b'{"text":"\\ud800"}',
               b'{"text":' + b'[' * 25 + b'0' + b']' * 25 + b'}']
        for raw in bad:
            self.refuse(400, 'POST', '/api/ask', raw=raw)
        self.assertFalse(self.ok('GET', '/api/state')['running'])
        self.refuse(400, 'POST', '/api/ask', {'text': 'x' * 8001})
        self.refuse(400, 'POST', '/api/ask', {'text': '   '})
        self.ok('POST', '/api/ask', {'text': 'Synthetic braces { [ ] } and \\" quoted text'})

    def test_transport_bounds_encodings_and_ambiguous_framing(self):
        self.refuse(414, 'GET', '/' + 'x' * 2048)
        self.refuse(413, 'POST', '/api/ask', headers={'Content-Length': str(MAX_BODY + 1)})
        self.refuse(400, 'POST', '/api/ask', {}, headers={'Content-Encoding': 'gzip'})
        common = ['POST /api/ask HTTP/1.1', f'Host: 127.0.0.1:{self.port}', f'X-Vela-Token: {TOKEN}']
        for extra in (['Content-Length: 2', 'Content-Length: 2'], ['Transfer-Encoding: chunked'], ['Content-Length: -1']):
            self.assertEqual(self.raw_request(common + extra)[0], 400)
        with patch.object(fixture, 'READ_TIMEOUT', 0.1):
            self.assertEqual(self.raw_request(common + ['Content-Length: 20'], b'{')[0], 408)

    def test_independent_servers_share_no_mutable_fixture_data(self):
        self.ok('POST', '/api/ask', {'text': 'Synthetic only this server'})
        other = fixture.FixtureServer(0)
        try:
            self.assertFalse(other.store.dispatch('GET', '/api/state', {})['running'])
        finally:
            other.server_close()

    def test_approval_ids_do_not_cross_server_instances(self):
        self.scenario('pending')
        prompt = self.ok('GET', '/api/state')['pendingId']
        other = fixture.FixtureServer(0, 'pending')
        try:
            self.assertNotEqual(other.store.dispatch('GET', '/api/state', {})['pendingId'], prompt)
        finally:
            other.server_close()

    def test_reset_revisions_advance_even_after_many_edits(self):
        # Put counters above the seed epoch without doing thousands of redundant HTTP writes.
        with self.server.store.lock:
            self.server.store.revision = 50000
            self.server.store.routines[0]['revision'] = 60000
        self.ok('POST', '/__fixture/reset', {})
        self.assertGreater(self.ok('GET', '/api/profiles')['revision'], 50000)
        self.assertTrue(all(r['revision'] > 60000 for r in self.ok('GET', '/api/routines')['routines']))


class LifecycleTest(unittest.TestCase):
    def test_cli_sigterm_releases_listener(self):
        process = subprocess.Popen([sys.executable, '-B', str(Path(fixture.__file__)), '--port', '0', '--scenario', 'pending'],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertIn('SYNTHETIC', process.stdout.readline())
            url = process.stdout.readline().strip()
            port = int(url.split(':')[2].split('/')[0])
            self.assertIn('#t=' + TOKEN, url)
            process.send_signal(signal.SIGTERM)
            out, err = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, err)
            self.assertIn('released', out)
            with self.assertRaises(OSError):
                socket.create_connection(('127.0.0.1', port), timeout=1)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
